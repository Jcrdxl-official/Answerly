import asyncio
import base64
import binascii
import time
import uuid
from dataclasses import dataclass
from typing import Any

from fastapi import WebSocket

from .history import HistoryStore


class TaskError(Exception):
    status_code = 503


class NoDeviceConnected(TaskError):
    pass


class AllDevicesDisconnected(TaskError):
    pass


class TaskTimedOut(TaskError):
    status_code = 504


@dataclass
class QueuedTask:
    task_id: str
    model: str
    messages: list[dict[str, Any]]
    created: int
    future: asyncio.Future[dict[str, Any]]
    status: str = "waiting"
    device_id: str | None = None
    websocket: WebSocket | Any | None = None


class TaskManager:
    def __init__(self, history: HistoryStore | None = None) -> None:
        self._devices: dict[str, WebSocket | Any] = {}
        self._tasks: dict[str, QueuedTask] = {}
        self._auto_replies: dict[str, str] = {}
        self._lock = asyncio.Lock()
        self._history = history

    async def configure_auto_reply(self, device_id: str, enabled: bool, content: str) -> None:
        async with self._lock:
            if enabled and content.strip():
                self._auto_replies[device_id] = content
            else:
                self._auto_replies.pop(device_id, None)

    async def auto_reply_if_offline(self, payload: dict[str, Any]) -> dict[str, Any] | None:
        async with self._lock:
            if self._devices or not self._auto_replies:
                return None
            content = next(iter(self._auto_replies.values()))
        return {
            "task_id": str(uuid.uuid4()),
            "content": content,
            "finish_reason": "stop",
            "created": int(time.time()),
        }

    async def connect(self, device_id: str, websocket: WebSocket | Any) -> None:
        async with self._lock:
            old = self._devices.get(device_id)
            if old is not None and old is not websocket:
                try:
                    await old.close(code=4009, reason="replaced by a new connection")
                except Exception:
                    pass
            self._devices[device_id] = websocket
            await self._send_available_locked(websocket)

    async def disconnect(self, device_id: str, websocket: WebSocket | Any) -> None:
        async with self._lock:
            if self._devices.get(device_id) is websocket:
                del self._devices[device_id]
            for task in self._tasks.values():
                if task.websocket is websocket and task.status == "claimed":
                    task.status = "waiting"
                    task.device_id = None
                    task.websocket = None

    async def dispatch(self, payload: dict[str, Any], timeout: float) -> dict[str, Any]:
        automatic = await self.auto_reply_if_offline(payload)
        if automatic is not None:
            self._record_history(
                entry_id=automatic["task_id"],
                model=payload["model"],
                messages=payload["messages"],
                response=automatic["content"],
                finish_reason=automatic.get("finish_reason", "stop"),
                source="auto_reply",
                device_id=None,
            )
            return automatic
        async with self._lock:
            task_id = str(uuid.uuid4())
            task = QueuedTask(
                task_id=task_id,
                model=payload["model"],
                messages=payload["messages"],
                created=int(time.time()),
                future=asyncio.get_running_loop().create_future(),
            )
            self._tasks[task_id] = task
            await self._broadcast_available_locked()

        try:
            result = await asyncio.wait_for(task.future, timeout=timeout)
        except asyncio.TimeoutError as exc:
            raise TaskTimedOut("device did not answer before timeout") from exc
        finally:
            async with self._lock:
                self._tasks.pop(task_id, None)
        return result

    def _record_history(
        self,
        *,
        entry_id: str,
        model: str,
        messages: list[dict[str, Any]],
        response: Any,
        finish_reason: str,
        source: str,
        device_id: str | None,
    ) -> None:
        if self._history is None:
            return
        try:
            self._history.add(
                entry_id=entry_id,
                model=model,
                request_messages=messages,
                response_content=response,
                finish_reason=finish_reason,
                source=source,
                device_id=device_id,
            )
        except Exception:
            pass

    async def claim(self, device_id: str, websocket: WebSocket | Any, task_id: str) -> str:
        async with self._lock:
            task = self._tasks.get(task_id)
            if task is None:
                return "not_found"
            if task.status != "waiting":
                return "unavailable"
            if self._devices.get(device_id) is not websocket:
                return "not_connected"
            if any(
                other.status == "claimed" and other.websocket is websocket
                for other in self._tasks.values()
            ):
                return "busy"
            task.status = "claimed"
            task.device_id = device_id
            task.websocket = websocket
            return "claimed"

    async def resolve(self, device_id: str, websocket: WebSocket | Any, task_id: str, result: dict[str, Any]) -> bool:
        async with self._lock:
            task = self._tasks.get(task_id)
            if (
                task is None
                or task.status != "claimed"
                or task.device_id != device_id
                or task.websocket is not websocket
                or task.future.done()
            ):
                return False
            _validate_result_content(result.get("content"))
            task.future.set_result(result)
            self._record_history(
                entry_id=task_id,
                model=task.model,
                messages=task.messages,
                response=result.get("content"),
                finish_reason=result.get("finish_reason", "stop"),
                source="manual",
                device_id=device_id,
            )
            self._tasks.pop(task_id, None)
            return True

    async def reject(self, device_id: str, websocket: WebSocket | Any, task_id: str, message: str) -> bool:
        async with self._lock:
            task = self._tasks.get(task_id)
            if (
                task is None
                or task.status != "claimed"
                or task.device_id != device_id
                or task.websocket is not websocket
                or task.future.done()
            ):
                return False
            task.future.set_exception(TaskError(message))
            self._tasks.pop(task_id, None)
            return True

    def history_list(self, limit: int = 100, offset: int = 0) -> list[dict[str, Any]]:
        if self._history is None:
            return []
        return self._history.list(limit=limit, offset=offset)

    def history_get(self, entry_id: str) -> dict[str, Any] | None:
        if self._history is None:
            return None
        return self._history.get(entry_id)

    async def history_delete(self, entry_id: str) -> bool:
        if self._history is None:
            return False
        deleted = self._history.delete(entry_id)
        if deleted:
            await self._broadcast({"type": "history_deleted", "id": entry_id})
        return deleted

    async def history_clear(self) -> int:
        if self._history is None:
            return 0
        removed = self._history.clear()
        await self._broadcast({"type": "history_cleared"})
        return removed

    async def _broadcast(self, message: dict[str, Any]) -> None:
        for websocket in list(self._devices.values()):
            try:
                await websocket.send_json(message)
            except Exception:
                pass
    async def _broadcast_available_locked(self) -> None:
        for websocket in list(self._devices.values()):
            await self._send_available_locked(websocket)

    async def _send_available_locked(self, websocket: WebSocket | Any) -> None:
        tasks = [
            {
                "task_id": task.task_id,
                "model": task.model,
                "messages": task.messages,
                "created": task.created,
            }
            for task in self._tasks.values()
            if task.status == "waiting"
        ]
        try:
            await websocket.send_json({"type": "task_available", "tasks": tasks})
        except Exception:
            pass


def _validate_result_content(content: Any) -> None:
    if isinstance(content, str):
        return
    if not isinstance(content, list) or not content:
        raise TaskError("task result content must be text or a non-empty content array")
    image_count = 0
    total_bytes = 0
    allowed_mime_types = {"image/jpeg", "image/png", "image/webp"}
    for item in content:
        if not isinstance(item, dict):
            raise TaskError("task result content items must be objects")
        item_type = item.get("type")
        if item_type == "text":
            if not isinstance(item.get("text"), str):
                raise TaskError("text content must contain a string text field")
        elif item_type == "image":
            image_count += 1
            mime_type = item.get("mime_type")
            data = item.get("data")
            if mime_type not in allowed_mime_types or not isinstance(data, str):
                raise TaskError("image content requires a supported mime_type and base64 data")
            try:
                decoded = base64.b64decode(data, validate=True)
            except (ValueError, binascii.Error) as exc:
                raise TaskError("image content contains invalid base64") from exc
            if len(decoded) > 10 * 1024 * 1024:
                raise TaskError("each image must be 10 MB or smaller")
            total_bytes += len(decoded)
        else:
            raise TaskError("content item type must be text or image")
    if image_count > 5:
        raise TaskError("a task result may contain at most 5 images")
    if total_bytes > 20 * 1024 * 1024:
        raise TaskError("total image content must be 20 MB or smaller")
