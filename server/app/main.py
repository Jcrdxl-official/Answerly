from __future__ import annotations

import json
from typing import Any

from fastapi import FastAPI, Header, HTTPException, WebSocket, WebSocketDisconnect
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

from .config import Settings
from .history import HistoryStore
from .tasks import AllDevicesDisconnected, NoDeviceConnected, TaskError, TaskManager, TaskTimedOut

settings = Settings.from_env()
history = HistoryStore(settings.history_db_path, settings.history_limit)
manager = TaskManager(history)
app = FastAPI(title="Answerly API", version="0.1.0")


class ChatRequest(BaseModel):
    model: str = "answerly"
    messages: list[dict[str, Any]] = Field(min_length=1)
    stream: bool = False
    temperature: float | None = None
    max_tokens: int | None = None
    metadata: dict[str, Any] | None = None


def _token(authorization: str | None, x_api_key: str | None) -> str | None:
    if x_api_key:
        return x_api_key
    if authorization and authorization.lower().startswith("bearer "):
        return authorization[7:].strip()
    return None


def _require_api_key(authorization: str | None, x_api_key: str | None) -> None:
    if _token(authorization, x_api_key) not in settings.api_keys:
        raise HTTPException(status_code=401, detail={"error": {"message": "invalid API key", "type": "authentication_error"}})


def _require_history_access(
    authorization: str | None,
    x_api_key: str | None,
    x_device_key: str | None,
) -> None:
    if _token(authorization, x_api_key) in settings.api_keys:
        return
    if x_device_key and x_device_key in settings.device_keys.values():
        return
    raise HTTPException(status_code=401, detail={"error": {"message": "invalid API key", "type": "authentication_error"}})


def _require_device_key(websocket: WebSocket, device_id: str) -> bool:
    provided = websocket.headers.get("x-device-key") or _token(websocket.headers.get("authorization"), None)
    return device_id in settings.device_ids and provided == settings.device_keys.get(device_id)


def _openai_content(content: Any) -> Any:
    if not isinstance(content, list):
        return content
    normalized = []
    for item in content:
        if not isinstance(item, dict) or item.get("type") != "image":
            normalized.append(item)
            continue
        mime_type = item.get("mime_type", "image/jpeg")
        normalized.append({
            "type": "image_url",
            "image_url": {"url": f"data:{mime_type};base64,{item['data']}"},
        })
    return normalized


@app.get("/healthz")
async def healthz() -> dict[str, str]:
    return {"status": "ok"}


@app.get("/v1/models")
async def models(authorization: str | None = Header(default=None), x_api_key: str | None = Header(default=None)) -> dict[str, Any]:
    _require_api_key(authorization, x_api_key)
    return {
        "object": "list",
        "data": [{
            "id": settings.model_id,
            "object": "model",
            "created": 0,
            "owned_by": "answerly",
            "capabilities": {
                "input_modalities": ["text", "image"],
                "output_modalities": ["text", "image"],
            },
        }],
    }


@app.post("/v1/chat/completions", response_model=None)
async def chat_completions(
    request: ChatRequest,
    authorization: str | None = Header(default=None),
    x_api_key: str | None = Header(default=None),
) -> dict[str, Any] | StreamingResponse:
    _require_api_key(authorization, x_api_key)
    if request.model != settings.model_id:
        raise HTTPException(status_code=400, detail={"error": {"message": f"model must be {settings.model_id}", "type": "invalid_request_error"}})
    try:
        result = await manager.dispatch(request.model_dump(), settings.task_timeout_seconds)
    except NoDeviceConnected as exc:
        raise HTTPException(status_code=503, detail={"error": {"message": str(exc), "type": "device_unavailable"}}) from exc
    except AllDevicesDisconnected as exc:
        raise HTTPException(status_code=503, detail={"error": {"message": str(exc), "type": "device_disconnected"}}) from exc
    except TaskTimedOut as exc:
        raise HTTPException(status_code=504, detail={"error": {"message": str(exc), "type": "timeout"}}) from exc
    except TaskError as exc:
        raise HTTPException(status_code=502, detail={"error": {"message": str(exc), "type": "device_error"}}) from exc

    response = {
        "id": f"chatcmpl-{result['task_id']}",
        "object": "chat.completion",
        "created": result.get("created", 0),
        "model": request.model,
        "choices": [{
            "index": 0,
            "message": {"role": "assistant", "content": _openai_content(result["content"])},
            "finish_reason": result.get("finish_reason", "stop"),
        }],
        "usage": {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0},
    }
    if not request.stream:
        return response

    chunk_id = response["id"]
    created = response["created"]
    content = _openai_content(result["content"])

    def event_stream():
        role_chunk = {
            "id": chunk_id,
            "object": "chat.completion.chunk",
            "created": created,
            "model": request.model,
            "choices": [{"index": 0, "delta": {"role": "assistant"}, "finish_reason": None}],
        }
        content_chunk = {
            "id": chunk_id,
            "object": "chat.completion.chunk",
            "created": created,
            "model": request.model,
            "choices": [{"index": 0, "delta": {"content": content}, "finish_reason": None}],
        }
        final_chunk = {
            "id": chunk_id,
            "object": "chat.completion.chunk",
            "created": created,
            "model": request.model,
            "choices": [{"index": 0, "delta": {}, "finish_reason": response["choices"][0]["finish_reason"]}],
        }
        for chunk in (role_chunk, content_chunk, final_chunk):
            yield f"data: {json.dumps(chunk, ensure_ascii=False)}\n\n"
        yield "data: [DONE]\n\n"

    return StreamingResponse(event_stream(), media_type="text/event-stream")


@app.get("/v1/history")
async def history_list(
    limit: int = 100,
    offset: int = 0,
    authorization: str | None = Header(default=None),
    x_api_key: str | None = Header(default=None),
    x_device_key: str | None = Header(default=None),
) -> dict[str, Any]:
    _require_history_access(authorization, x_api_key, x_device_key)
    limit = max(1, min(limit, 500))
    offset = max(0, offset)
    items = manager.history_list(limit=limit, offset=offset)
    return {"object": "list", "data": items}


@app.get("/v1/history/{entry_id}")
async def history_get(
    entry_id: str,
    authorization: str | None = Header(default=None),
    x_api_key: str | None = Header(default=None),
    x_device_key: str | None = Header(default=None),
) -> dict[str, Any]:
    _require_history_access(authorization, x_api_key, x_device_key)
    entry = manager.history_get(entry_id)
    if entry is None:
        raise HTTPException(status_code=404, detail={"error": {"message": "history entry not found", "type": "not_found"}})
    return entry


@app.delete("/v1/history/{entry_id}")
async def history_delete(
    entry_id: str,
    authorization: str | None = Header(default=None),
    x_api_key: str | None = Header(default=None),
    x_device_key: str | None = Header(default=None),
) -> dict[str, Any]:
    _require_history_access(authorization, x_api_key, x_device_key)
    deleted = await manager.history_delete(entry_id)
    if not deleted:
        raise HTTPException(status_code=404, detail={"error": {"message": "history entry not found", "type": "not_found"}})
    return {"deleted": True, "id": entry_id}


@app.delete("/v1/history")
async def history_clear(
    authorization: str | None = Header(default=None),
    x_api_key: str | None = Header(default=None),
    x_device_key: str | None = Header(default=None),
) -> dict[str, Any]:
    _require_history_access(authorization, x_api_key, x_device_key)
    removed = await manager.history_clear()
    return {"deleted": True, "removed": removed}


@app.websocket("/ws/device")
async def device_socket(websocket: WebSocket, device_id: str) -> None:
    if not _require_device_key(websocket, device_id):
        await websocket.close(code=4401, reason="invalid device credentials")
        return
    await websocket.accept()
    await manager.connect(device_id, websocket)
    try:
        while True:
            message = await websocket.receive_json()
            if not isinstance(message, dict):
                continue
            message_type = message.get("type")
            task_id = message.get("task_id")
            if message_type == "auto_reply_config":
                await manager.configure_auto_reply(
                    device_id,
                    bool(message.get("enabled", False)),
                    str(message.get("content", "")),
                )
                await websocket.send_json({"type": "auto_reply_configured", "enabled": bool(message.get("enabled", False))})
            elif message_type == "task_claim" and isinstance(task_id, str):
                result = await manager.claim(device_id, websocket, task_id)
                await websocket.send_json({"type": "task_claimed" if result == "claimed" else "task_unavailable", "task_id": task_id, "reason": result})
            elif message_type == "task_result" and isinstance(task_id, str) and isinstance(message.get("content"), str):
                await manager.resolve(device_id, websocket, task_id, {
                    "task_id": task_id,
                    "content": message["content"],
                    "finish_reason": message.get("finish_reason", "stop"),
                    "created": message.get("created", 0),
                })
            elif message_type == "task_result" and isinstance(task_id, str) and isinstance(message.get("content"), list):
                try:
                    await manager.resolve(device_id, websocket, task_id, {
                        "task_id": task_id,
                        "content": message["content"],
                        "finish_reason": message.get("finish_reason", "stop"),
                        "created": message.get("created", 0),
                    })
                except TaskError as exc:
                    await manager.reject(device_id, websocket, task_id, str(exc))
            elif message_type == "task_error" and isinstance(task_id, str):
                await manager.reject(device_id, websocket, task_id, str(message.get("message", "device rejected task")))
            elif message_type == "history_list":
                limit = int(message.get("limit", 100))
                offset = int(message.get("offset", 0))
                await websocket.send_json({
                    "type": "history_list",
                    "data": manager.history_list(limit=max(1, min(limit, 500)), offset=max(0, offset)),
                })
            elif message_type == "history_delete" and isinstance(message.get("id"), str):
                await manager.history_delete(message["id"])
            elif message_type == "history_clear":
                await manager.history_clear()
    except WebSocketDisconnect:
        pass
    finally:
        await manager.disconnect(device_id, websocket)


