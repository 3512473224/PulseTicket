from datetime import datetime
import json
from pathlib import Path
from typing import Any, Dict, List, Optional
import uuid

import aiosqlite

from app.config import settings


class AgentTracer:
    """
    【P3 可观测性与安全审计模块】：
    将每一次主图路由、子 Agent 执行、工具调用、RAG 条款引用、安全护栏拦截、人工确认挂起与耗时
    持久化写入 SQLite (agent_trace_log)，供后台 Telemetry 面板实时观测与离线复盘。
    """

    def __init__(self, db_path: Optional[str] = None):
        default_dir = Path(settings.CHECKPOINT_DB_PATH).resolve().parent
        self.db_path = db_path or str(default_dir / "ticket_agent_traces.sqlite")
        self._initialized = False

    async def ensure_schema(self):
        if self._initialized:
            return
        Path(self.db_path).parent.mkdir(parents=True, exist_ok=True)
        async with aiosqlite.connect(self.db_path) as db:
            await db.execute(
                """
                CREATE TABLE IF NOT EXISTS agent_trace_log (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    trace_id TEXT NOT NULL,
                    thread_id TEXT NOT NULL,
                    user_id INTEGER NOT NULL,
                    username TEXT NOT NULL,
                    endpoint TEXT NOT NULL,
                    user_message TEXT NOT NULL,
                    intents_json TEXT NOT NULL,
                    subgraphs_json TEXT NOT NULL,
                    tool_steps_json TEXT NOT NULL,
                    citations_json TEXT NOT NULL,
                    guard_blocked INTEGER NOT NULL DEFAULT 0,
                    interrupted INTEGER NOT NULL DEFAULT 0,
                    write_executed INTEGER NOT NULL DEFAULT 0,
                    latency_ms INTEGER NOT NULL DEFAULT 0,
                    status TEXT NOT NULL,
                    reply_preview TEXT NOT NULL,
                    created_at TEXT NOT NULL
                )
                """
            )
            await db.execute(
                "CREATE INDEX IF NOT EXISTS idx_trace_created ON agent_trace_log(id DESC)"
            )
            await db.commit()
        self._initialized = True

    async def record_trace(
        self,
        *,
        trace_id: Optional[str] = None,
        thread_id: str,
        user_id: int,
        username: str,
        endpoint: str,
        user_message: str,
        intents: List[str],
        subgraphs: List[str],
        tool_steps: List[Dict[str, Any]],
        citations: List[str],
        guard_blocked: bool = False,
        interrupted: bool = False,
        write_executed: bool = False,
        latency_ms: int = 0,
        status: str = "COMPLETED",
        reply: str = "",
    ) -> str:
        await self.ensure_schema()
        tid = trace_id or f"ag-{uuid.uuid4().hex[:12]}"
        preview = (reply or "").strip().replace("\n", " ")[:220]
        now_str = datetime.now().strftime("%Y-%m-%d %H:%M:%S")

        async with aiosqlite.connect(self.db_path) as db:
            await db.execute(
                """
                INSERT INTO agent_trace_log (
                    trace_id, thread_id, user_id, username, endpoint, user_message,
                    intents_json, subgraphs_json, tool_steps_json, citations_json,
                    guard_blocked, interrupted, write_executed, latency_ms, status,
                    reply_preview, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    tid,
                    thread_id,
                    int(user_id),
                    username,
                    endpoint,
                    (user_message or "")[:500],
                    json.dumps(intents or [], ensure_ascii=False),
                    json.dumps(subgraphs or [], ensure_ascii=False),
                    json.dumps(tool_steps or [], ensure_ascii=False),
                    json.dumps(citations or [], ensure_ascii=False),
                    1 if guard_blocked else 0,
                    1 if interrupted else 0,
                    1 if write_executed else 0,
                    max(0, int(latency_ms)),
                    status,
                    preview,
                    now_str,
                ),
            )
            await db.commit()
        return tid

    async def list_recent_traces(self, limit: int = 25) -> Dict[str, Any]:
        await self.ensure_schema()
        cap = max(1, min(100, int(limit)))
        async with aiosqlite.connect(self.db_path) as db:
            db.row_factory = aiosqlite.Row
            cursor = await db.execute(
                "SELECT * FROM agent_trace_log ORDER BY id DESC LIMIT ?",
                (cap,),
            )
            rows = await cursor.fetchall()

            stat_cur = await db.execute(
                """
                SELECT
                    COUNT(*) AS totalRuns,
                    COALESCE(SUM(guard_blocked), 0) AS guardBlockedCount,
                    COALESCE(SUM(interrupted), 0) AS interruptedCount,
                    COALESCE(SUM(write_executed), 0) AS writeExecutedCount,
                    COALESCE(ROUND(AVG(latency_ms), 0), 0) AS avgLatencyMs
                FROM agent_trace_log
                """
            )
            stat_row = await stat_cur.fetchone()

        traces = []
        for r in rows:
            traces.append({
                "id": r["id"],
                "traceId": r["trace_id"],
                "threadId": r["thread_id"],
                "userId": r["user_id"],
                "username": r["username"],
                "endpoint": r["endpoint"],
                "userMessage": r["user_message"],
                "intents": json.loads(r["intents_json"] or "[]"),
                "subgraphs": json.loads(r["subgraphs_json"] or "[]"),
                "toolSteps": json.loads(r["tool_steps_json"] or "[]"),
                "citations": json.loads(r["citations_json"] or "[]"),
                "guardBlocked": bool(r["guard_blocked"]),
                "interrupted": bool(r["interrupted"]),
                "writeExecuted": bool(r["write_executed"]),
                "latencyMs": r["latency_ms"],
                "status": r["status"],
                "replyPreview": r["reply_preview"],
                "createdAt": r["created_at"],
            })

        summary = {
            "totalRuns": int(stat_row["totalRuns"] or 0) if stat_row else 0,
            "guardBlockedCount": int(stat_row["guardBlockedCount"] or 0) if stat_row else 0,
            "interruptedCount": int(stat_row["interruptedCount"] or 0) if stat_row else 0,
            "writeExecutedCount": int(stat_row["writeExecutedCount"] or 0) if stat_row else 0,
            "avgLatencyMs": int(stat_row["avgLatencyMs"] or 0) if stat_row else 0,
        }
        return {"summary": summary, "traces": traces}


agent_tracer = AgentTracer()
