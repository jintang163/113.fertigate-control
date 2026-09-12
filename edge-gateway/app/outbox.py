"""SQLite 断网缓存（outbox 模式）。

采集即落盘；MQTT PUBACK 后按批删除。断网期间数据持久留存，
重连后从最旧记录开始补传，消息中的 ts 为原始采样时间。
"""
from __future__ import annotations

import json
import os
import sqlite3
import threading
from typing import Any, Dict, List, Optional

_SCHEMA = """
CREATE TABLE IF NOT EXISTS outbox (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    topic TEXT NOT NULL,
    payload TEXT NOT NULL,
    created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_outbox_id ON outbox(id);
"""


class Outbox:
    def __init__(self, path: str):
        os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
        self._lock = threading.Lock()
        self._conn = sqlite3.connect(path, check_same_thread=False)
        self._conn.execute("PRAGMA journal_mode=WAL")
        self._conn.executescript(_SCHEMA)
        self._conn.commit()

    def add(self, topic: str, payload: Dict[str, Any]) -> None:
        with self._lock:
            self._conn.execute(
                "INSERT INTO outbox(topic, payload, created_at) VALUES (?,?,?)",
                (topic, json.dumps(payload, ensure_ascii=False), payload.get("ts", 0)))
            self._conn.commit()

    def pending(self, limit: int) -> List[tuple[int, str, str]]:
        """返回 (id, topic, payload) 最旧的一批，不删除。"""
        with self._lock:
            rows = self._conn.execute(
                "SELECT id, topic, payload FROM outbox ORDER BY id ASC LIMIT ?", (limit,)
            ).fetchall()
            return [(r[0], r[1], r[2]) for r in rows]

    def delete_up_to(self, max_id: int) -> None:
        with self._lock:
            self._conn.execute("DELETE FROM outbox WHERE id <= ?", (max_id,))
            self._conn.commit()

    def depth(self) -> int:
        with self._lock:
            return self._conn.execute("SELECT COUNT(*) FROM outbox").fetchone()[0]

    def close(self) -> None:
        with self._lock:
            self._conn.close()
