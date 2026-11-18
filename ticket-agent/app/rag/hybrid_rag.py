import re
from pathlib import Path
from typing import Any, Dict, List, Tuple
import httpx
import jieba
import numpy as np
from rank_bm25 import BM25Okapi
from sklearn.feature_extraction.text import HashingVectorizer

from app.config import settings

STOPWORDS = {
    "的", "了", "呢", "吗", "啊", "吧", "呀", "我", "你", "他", "是", "在", "有", "和", "与",
    "就", "都", "而", "及", "着", "或", "一个", "请问", "你好", "您好", "这个", "那个", "怎么", "可以",
    "想问", "一下", "帮我", "看看", "能不能"
}

# 票务领域同义词语义桥接词典（提升口语化提问对规范条款的向量召回率）
TICKET_SYNONYM_BRIDGES: List[Tuple[List[str], str]] = [
    (["退票", "退钱", "不去", "去不了", "临时有事", "手续费", "扣多少", "改签", "换时间", "取消订单"],
     "阶梯退票 手续费率 开演前48小时 开演前7天 免费退票 停止退票 UNPAID POL-REFUND-001"),
    (["实名", "身份证", "小孩", "儿童", "身高", "检票", "核验", "二维码", "迟到", "入场"],
     "强实名制 一人一证一票 动态电子票核验码 1.2米儿童全价票 幕间休息 POL-VERIFY-002"),
    (["地铁", "怎么走", "路线", "停车", "梅赛德斯", "天桥", "万象城", "大麦", "上海", "北京", "深圳", "杭州", "梅溪湖", "长沙", "雨伞", "长柄伞", "喝水", "寄存", "存包"],
     "场馆交通 地铁出站 梅赛德斯-奔驰文化中心 天桥艺术中心 万象城IMAX 大麦66 禁带物品 长柄雨伞 免费自助存包柜 寄存处 POL-VENUE-003 POL-VENUE-005"),
    (["连座", "挨着", "坐一起", "选座", "锁座", "多久支付", "15分钟", "超时", "自动取消"],
     "智能选座 同排连续相邻 中轴线 Redisson分布式锁 15分钟倒计时 RabbitMQ死信关单 POL-LOCK-004"),
]

_CHAR_HASH = HashingVectorizer(
    n_features=384,
    analyzer="char_wb",
    ngram_range=(2, 4),
    alternate_sign=False,
    norm=None,
)
_WORD_HASH = HashingVectorizer(
    n_features=128,
    analyzer="word",
    token_pattern=r"(?u)\b\w+\b",
    alternate_sign=False,
    norm=None,
)


def expand_ticket_concepts(text: str) -> str:
    lower = (text or "").lower()
    extra = []
    for triggers, expansion in TICKET_SYNONYM_BRIDGES:
        if any(t in lower for t in triggers):
            extra.append(expansion)
    return (text + " " + " ".join(extra)).strip() if extra else text


def tokenize_zh(text: str) -> List[str]:
    raw_tokens = jieba.lcut((text or "").lower())
    out: List[str] = []
    for tok in raw_tokens:
        t = tok.strip()
        if not t or t in STOPWORDS or re.match(r"^[\W_]+$", t):
            continue
        out.append(t)
    for code in re.findall(r"pol-[a-z]+-\d+", (text or "").lower()):
        if code not in out:
            out.append(code)
    return out


def compute_local_dense_vectors(texts: List[str]) -> Tuple[np.ndarray, str]:
    """
    计算 512 维 float32 L2 归一化稠密语义向量（无需外部 GPU / API Key 即可零延迟运行）
    """
    expanded = [expand_ticket_concepts(t) for t in texts]
    word_joined = [" ".join(tokenize_zh(t)) for t in expanded]

    m_char = _CHAR_HASH.transform(expanded).toarray().astype(np.float32)
    m_word = _WORD_HASH.transform(word_joined).toarray().astype(np.float32) * 1.35

    mat = np.hstack([m_char, m_word])
    norms = np.linalg.norm(mat, axis=1, keepdims=True)
    norms[norms == 0] = 1.0
    return (mat / norms).astype(np.float32), "local-hybrid-dense-512d"


async def compute_embeddings(texts: List[str]) -> Tuple[np.ndarray, str]:
    if settings.EMBEDDING_API_KEY:
        try:
            async with httpx.AsyncClient(timeout=8.0) as client:
                resp = await client.post(
                    f"{settings.EMBEDDING_BASE_URL}/embeddings",
                    headers={"Authorization": f"Bearer {settings.EMBEDDING_API_KEY}"},
                    json={"model": settings.EMBEDDING_MODEL, "input": texts},
                )
                resp.raise_for_status()
                data = resp.json().get("data", [])
                vecs = np.array([item["embedding"] for item in data], dtype=np.float32)
                norms = np.linalg.norm(vecs, axis=1, keepdims=True)
                norms[norms == 0] = 1.0
                return (vecs / norms).astype(np.float32), settings.EMBEDDING_MODEL
        except Exception:
            pass
    return compute_local_dense_vectors(texts)


class TicketHybridRagEngine:
    """
    PulseTicket 票务知识库混合检索引擎 (Hybrid RAG):
    1. 自动扫描 knowledge/*.md 并按二级标题 (## ) 切分语义块 (Chunking)
    2. 稀疏检索 (Sparse): jieba 分词 + BM25Okapi 精确命中数字、站名、规则编号
    3. 稠密检索 (Dense): 512 维语义向量余弦相似度捕捉口语化意图
    4. 倒数秩融合 (RRF): score = 1/(60 + rank_bm25) + 1/(60 + rank_vec) + 关键词覆盖度精排
    """

    def __init__(self, knowledge_dir: Path = settings.KNOWLEDGE_DIR):
        self.knowledge_dir = knowledge_dir
        self._chunks: List[Dict[str, Any]] = []
        self._tokens: List[List[str]] = []
        self._bm25: BM25Okapi | None = None
        self._matrix: np.ndarray | None = None
        self._model_name: str = "local-hybrid-dense-512d"

    def load_and_index(self) -> int:
        """读取 knowledge/ 目录下的 Markdown 文件并构建内存 BM25 + 向量双索引"""
        self._chunks.clear()
        self._tokens.clear()

        if not self.knowledge_dir.exists():
            return 0

        md_files = sorted(self.knowledge_dir.glob("*.md"))
        chunk_id = 1
        for md_path in md_files:
            raw = md_path.read_text(encoding="utf-8").strip()
            if not raw:
                continue

            # 提取第一行标题与条款编号，如 # [POL-REFUND-001] 阶梯退票与改签手续费规则
            first_line = raw.splitlines()[0].lstrip("# ").strip()
            code_match = re.search(r"\[(POL-[A-Z]+-\d+)\]", first_line)
            doc_code = code_match.group(1) if code_match else md_path.stem.upper()
            doc_title = re.sub(r"\[POL-[A-Z]+-\d+\]\s*", "", first_line).strip()

            # 按二级标题 ## 切分为独立语义分片，保证每个分片上下文完整
            sections = re.split(r"\n(?=##\s+)", raw)
            for sec in sections:
                sec_clean = sec.strip()
                if not sec_clean or sec_clean.startswith("# ["):
                    # 如果第一段只有主标题没有正文则跳过
                    lines = [ln for ln in sec_clean.splitlines() if not ln.startswith("# ")]
                    if not "".join(lines).strip():
                        continue
                content_with_header = (
                    sec_clean
                    if doc_code in sec_clean
                    else f"[{doc_code} 《{doc_title}》]\n{sec_clean}"
                )
                toks = tokenize_zh(content_with_header)
                self._chunks.append({
                    "chunkId": chunk_id,
                    "docCode": doc_code,
                    "docTitle": doc_title,
                    "sourceFile": md_path.name,
                    "content": content_with_header,
                })
                self._tokens.append(toks)
                chunk_id += 1

        if self._chunks:
            self._bm25 = BM25Okapi(self._tokens)
            self._matrix, self._model_name = compute_local_dense_vectors(
                [c["content"] for c in self._chunks]
            )
        return len(self._chunks)

    async def search(self, query: str, top_k: int = 3) -> List[Dict[str, Any]]:
        if not self._chunks or self._bm25 is None or self._matrix is None:
            self.load_and_index()
        if not self._chunks or self._bm25 is None or self._matrix is None:
            return []

        expanded_query = expand_ticket_concepts(query)
        q_tokens = tokenize_zh(expanded_query)
        bm25_scores = (
            self._bm25.get_scores(q_tokens) if q_tokens else np.zeros(len(self._chunks))
        )

        q_vecs, _ = compute_local_dense_vectors([query])
        q_vec = q_vecs[0]
        vec_scores = self._matrix @ q_vec

        bm25_order = np.argsort(-bm25_scores)
        vec_order = np.argsort(-vec_scores)

        bm25_rank = {int(idx): r + 1 for r, idx in enumerate(bm25_order)}
        vec_rank = {int(idx): r + 1 for r, idx in enumerate(vec_order)}

        max_bm25 = float(np.max(bm25_scores)) if np.max(bm25_scores) > 0 else 1.0
        raw_q_tokens = set(tokenize_zh(query))
        rrf_k = 60

        results: List[Dict[str, Any]] = []
        for i, chunk in enumerate(self._chunks):
            r_b = bm25_rank.get(i, 50)
            r_v = vec_rank.get(i, 50)
            rrf_score = (1.0 / (rrf_k + r_b)) + (1.0 / (rrf_k + r_v))

            norm_bm25 = float(bm25_scores[i]) / max_bm25
            cos_sim = float(max(0.0, vec_scores[i]))
            coverage = len(raw_q_tokens & set(self._tokens[i])) / max(1, len(raw_q_tokens))
            final_score = round(0.35 * norm_bm25 + 0.45 * cos_sim + 0.20 * coverage + rrf_score * 2.0, 4)

            results.append({
                **chunk,
                "bm25Score": round(float(bm25_scores[i]), 4),
                "vectorScore": round(cos_sim, 4),
                "rrfScore": round(rrf_score, 5),
                "finalScore": final_score,
            })

        results.sort(key=lambda x: x["finalScore"], reverse=True)
        return results[:top_k]


hybrid_rag_engine = TicketHybridRagEngine()
