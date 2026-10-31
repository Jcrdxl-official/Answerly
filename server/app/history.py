from __future__ import annotations

import hashlib
import json
import os
import sqlite3
import threading
import time
import uuid
from typing import Any


def _content_text(content: Any) -> str:
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts = []
        for item in content:
            if isinstance(item, dict) and item.get("type") == "text":
                parts.append(str(item.get("text", "")))
        return "".join(parts)
    if content is None:
        return ""
    return str(content)


def conversation_key(messages: list[dict[str, Any]]) -> str:
    """Stable key for a conversation, derived from its first user message."""
    for message in messages:
        if isinstance(message, dict) and message.get("role") == "user":
            return hashlib.sha256(
                _content_text(message.get("content")).encode("utf-8")
            ).hexdigest()
    payload = json.dumps(messages, ensure_ascii=False, sort_keys=True)
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


class HistoryStore:
    """SQLite backed chat history store.

    One row per conversation: a follow-up request that starts with the same
    first user message updates the existing row instead of adding a new one.
    """

    def __init__(self, path: str, limit: int = 500) -> None:
        self._path = path
        self._limit = max(1, limit)
        self._lock = threading.Lock()
        directory = os.path.dirname(os.path.abspath(path))
        os.makedirs(directory, exist_ok=True)
        self._conn = sqlite3.connect(path, check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        with self._lock:
            self._conn.execute(
                """
                CREATE TABLE IF NOT EXISTS chat_history (
                    id TEXT PRIMARY KEY,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL DEFAULT 0,
                    conversation_key TEXT,
                    model TEXT NOT NULL,
                    request_messages TEXT NOT NULL,
                    response_content TEXT NOT NULL,
                    finish_reason TEXT NOT NULL,
                    source TEXT NOT NULL,
                    device_id TEXT
                )
                """
            )
            self._migrate_locked()
            self._conn.commit()

    def _migrate_locked(self) -> None:
        columns = {
            row["name"]
            for row in self._conn.execute("PRAGMA table_info(chat_history)").fetchall()
        }
        if "updated_at" not in columns:
            self._conn.execute(
                "ALTER TABLE chat_history ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0"
            )
            self._conn.execute("UPDATE chat_history SET updated_at = created_at")
        if "conversation_key" not in columns:
            self._conn.execute("ALTER TABLE chat_history ADD COLUMN conversation_key TEXT")
        self._conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_chat_history_key ON chat_history(conversation_key)"
        )

    def add(
        self,
        *,
        model: str,
        request_messages: list[dict[str, Any]],
        response_content: Any,
        finish_reason: str = "stop",
        source: str = "manual",
        device_id: str | None = None,
        entry_id: str | None = None,
    ) -> dict[str, Any]:
        key = conversation_key(request_messages)
        now = int(time.time())
        messages_json = json.dumps(request_messages, ensure_ascii=False)
        response_json = json.dumps(response_content, ensure_ascii=False)
        with self._lock:
            existing = self._conn.execute(
                "SELECT id FROM chat_history WHERE conversation_key = ? ORDER BY updated_at DESC LIMIT 1",
                (key,),
            ).fetchone()
            if existing is not None:
                entry_id = existing["id"]
                self._conn.execute(
                    """
                    UPDATE chat_history
                    SET updated_at = ?, model = ?, request_messages = ?,
                        response_content = ?, finish_reason = ?, source = ?, device_id = ?
                    WHERE id = ?
                    """,
                    (
                        now,
                        model,
                        messages_json,
                        response_json,
                        finish_reason,
                        source,
                        device_id,
                        entry_id,
                    ),
                )
            else:
                entry_id = entry_id or str(uuid.uuid4())
                self._conn.execute(
                    """
                    INSERT INTO chat_history
                        (id, created_at, updated_at, conversation_key, model,
                         request_messages, response_content, finish_reason, source, device_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        entry_id,
                        now,
                        now,
                        key,
                        model,
                        messages_json,
                        response_json,
                        finish_reason,
                        source,
                        device_id,
                    ),
                )
            self._conn.execute(
                """
                DELETE FROM chat_history WHERE id NOT IN (
                    SELECT id FROM chat_history ORDER BY updated_at DESC, rowid DESC LIMIT ?
                )
                """,
                (self._limit,),
            )
            self._conn.commit()
        entry = self.get(entry_id)
        assert entry is not None
        return entry

    def list(self, limit: int = 100, offset: int = 0) -> list[dict[str, Any]]:
        with self._lock:
            rows = self._conn.execute(
                """
                SELECT * FROM chat_history
                ORDER BY updated_at DESC, rowid DESC
                LIMIT ? OFFSET ?
                """,
                (limit, offset),
            ).fetchall()
        return [self._row(row) for row in rows]

    def get(self, entry_id: str) -> dict[str, Any] | None:
        with self._lock:
            row = self._conn.execute(
                "SELECT * FROM chat_history WHERE id = ?", (entry_id,)
            ).fetchone()
        return self._row(row) if row is not None else None

    def delete(self, entry_id: str) -> bool:
        with self._lock:
            cursor = self._conn.execute(
                "DELETE FROM chat_history WHERE id = ?", (entry_id,)
            )
            self._conn.commit()
        return cursor.rowcount > 0

    def clear(self) -> int:
        with self._lock:
            cursor = self._conn.execute("DELETE FROM chat_history")
            self._conn.commit()
        return cursor.rowcount

    @staticmethod
    def _row(row: sqlite3.Row) -> dict[str, Any]:
        return {
            "id": row["id"],
            "created_at": row["created_at"],
            "updated_at": row["updated_at"] or row["created_at"],
            "model": row["model"],
            "messages": json.loads(row["request_messages"]),
            "response": json.loads(row["response_content"]),
            "finish_reason": row["finish_reason"],
            "source": row["source"],
            "device_id": row["device_id"],
        }
