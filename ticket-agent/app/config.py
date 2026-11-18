import os
from pathlib import Path
from dotenv import load_dotenv

# 依次尝试加载 ticket-agent/.env、pulseticket/.env、pulseflow/.env
AGENT_ROOT = Path(__file__).resolve().parents[1]
PROJECT_ROOT = AGENT_ROOT.parent
WORKSPACE_ROOT = PROJECT_ROOT.parent

for env_candidate in [
    AGENT_ROOT / ".env",
    PROJECT_ROOT / ".env",
    WORKSPACE_ROOT / "pulseflow" / ".env",
]:
    if env_candidate.exists():
        load_dotenv(env_candidate, override=False)


class Settings:
    # Agent 服务监听端口
    AGENT_PORT: int = int(os.getenv("AGENT_PORT", "8204"))

    # 开发离线联调开关（默认严格关闭！仅显式设置 DEV_MOCK=true 时才允许无 Java 环境下的本地模拟数据）
    DEV_MOCK: bool = os.getenv("DEV_MOCK", "false").strip().lower() in ("1", "true", "yes")

    # Java 微服务直连地址（开发联调默认直连 show-service:8202 与 order-service:8203）
    SHOW_SERVICE_URL: str = os.getenv("SHOW_SERVICE_URL", "http://127.0.0.1:8202").rstrip("/")
    ORDER_SERVICE_URL: str = os.getenv("ORDER_SERVICE_URL", "http://127.0.0.1:8203").rstrip("/")
    GATEWAY_URL: str = os.getenv("GATEWAY_URL", "http://127.0.0.1:8200").rstrip("/")

    # DeepSeek / OpenAI 兼容大模型配置（严禁在源码中硬编码密钥）
    LLM_API_KEY: str = (os.getenv("LLM_API_KEY") or os.getenv("DEEPSEEK_API_KEY") or "").strip()
    LLM_BASE_URL: str = os.getenv("LLM_BASE_URL", "https://api.deepseek.com").rstrip("/")
    LLM_MODEL: str = os.getenv("LLM_MODEL", "deepseek-chat")
    LLM_TIMEOUT_SEC: float = float(os.getenv("LLM_TIMEOUT_SEC", "25.0"))

    # 可选远程 Embedding API（未配置时自动使用本地 512 维稠密语义向量引擎）
    EMBEDDING_API_KEY: str = os.getenv("EMBEDDING_API_KEY", "").strip()
    EMBEDDING_BASE_URL: str = os.getenv("EMBEDDING_BASE_URL", "https://api.siliconflow.cn/v1").rstrip("/")
    EMBEDDING_MODEL: str = os.getenv("EMBEDDING_MODEL", "BAAI/bge-m3")

    # 知识库 Markdown 目录与 LangGraph SQLite 状态持久化路径
    KNOWLEDGE_DIR: Path = AGENT_ROOT / "knowledge"
    CHECKPOINT_DB_PATH: str = os.getenv(
        "CHECKPOINT_DB_PATH",
        str(AGENT_ROOT / "data" / "ticket_agent_checkpoints.sqlite"),
    )


settings = Settings()
