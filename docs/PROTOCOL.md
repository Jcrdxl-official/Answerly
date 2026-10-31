# Answerly Protocol

## HTTP API

### `GET /v1/models`

Requires `Authorization: Bearer <api-key>` or `X-API-Key: <api-key>`.

Response:

```json
{
  "object": "list",
  "data": [
    {
      "id": "answerly",
      "object": "model",
      "created": 0,
      "owned_by": "answerly"
    }
  ]
}
```

### `POST /v1/chat/completions`

The request accepts the usual `model`, `messages`, and optional `temperature`, `max_tokens`, and `metadata` fields. Both `stream: false` and `stream: true` are supported. Streaming emits standard Server-Sent Events after the Android client has supplied the complete manual answer; it does not generate tokens incrementally.

The server keeps each HTTP request as an independent queued task. If no device is connected when a request arrives, the request remains queued and waits for a device to connect. The request waits until its task is claimed and answered, or until `RELAY_TASK_TIMEOUT_SECONDS` is reached (HTTP 504). If a device disconnects after claiming a task, that task returns to the waiting list when a device reconnects.

## Device WebSocket

Connect to:

```text
ws://host:8000/ws/device?device_id=android-1
```

Send the device key in `X-Device-Key` or `Authorization: Bearer <device-key>`.

The server sends the current waiting list:

```json
{
  "type": "task_available",
  "tasks": [
    {
      "task_id": "uuid",
      "model": "answerly",
      "messages": [{"role": "user", "content": "Hello"}],
      "created": 1730000000
    }
  ]
}
```

The Android client claims one selected task:

```json
{
  "type": "task_claim",
  "task_id": "uuid"
}
```

The server replies with `task_claimed` or `task_unavailable`. After `task_claimed`, the Android client answers:

```json
{
  "type": "task_result",
  "task_id": "uuid",
  "content": "Hello from a person",
  "finish_reason": "stop"
}
```

or rejects a task:

```json
{
  "type": "task_error",
  "task_id": "uuid",
  "message": "Unable to answer"
}
```

The server ignores unknown task IDs and rejects malformed messages. A device can claim one task at a time; other tasks remain visible and waiting.

### Image replies

The Android client can send a text-only reply as before, or a multimodal content array:

```json
{
  "type": "task_result",
  "task_id": "uuid",
  "content": [
    {"type": "text", "text": "图片回复说明"},
    {"type": "image", "mime_type": "image/png", "data": "<base64>"}
  ],
  "finish_reason": "stop"
}
```

The server accepts JPEG, PNG, and WebP images, up to 5 images per reply, 10 MB per image, and 20 MB total. The OpenAI-compatible response preserves the content array. The Android MVP selects images from the system photo picker and previews them before sending.

## Chat history

Every completed request (manual answer or offline auto-reply) is persisted in SQLite.

Entries are grouped by conversation: the `conversation_key` is the SHA-256 of the first `user` message text, so follow-up requests in the same conversation update the existing row (its `messages` and `response` are replaced with the newest values) instead of creating a new one. The list is ordered by `updated_at` descending.

### `GET /v1/history?limit=100&offset=0`

Requires an API key (`Authorization: Bearer <api-key>` or `X-API-Key`) or a device key (`X-Device-Key`).

```json
{
  "object": "list",
  "data": [
    {
      "id": "uuid",
      "created_at": 1730000000,
      "model": "answerly",
      "messages": [{"role": "user", "content": "Hello"}],
      "response": "Hello from a person",
      "finish_reason": "stop",
      "source": "manual",
      "device_id": "android-1"
    }
  ]
}
```

`source` is `manual` or `auto_reply`. `response` is a string, or a content array when the reply contains images.

### `GET /v1/history/{id}`

Returns a single entry, or HTTP 404 when it does not exist.

### `DELETE /v1/history/{id}`

Deletes one entry on the server and broadcasts `{"type": "history_deleted", "id": "<id>"}` to all connected devices.

### `DELETE /v1/history`

Deletes all entries and broadcasts `{"type": "history_cleared"}`.

### WebSocket history messages

Devices may also manage history over the WebSocket:

```json
{"type": "history_list", "limit": 100, "offset": 0}
```

The server replies with `{"type": "history_list", "data": [...]}`.

```json
{"type": "history_delete", "id": "uuid"}
{"type": "history_clear"}
```

Both trigger the same broadcasts as the HTTP endpoints, so every connected client stays in sync.

### Offline auto-reply

The Android client may send the following message after connecting:

```json
{
  "type": "auto_reply_config",
  "enabled": true,
  "content": "服务暂时离线，请稍后再试"
}
```

The server stores the configuration by device ID. When no Android device is connected and an enabled non-empty auto-reply exists, a chat completion request immediately returns that content in the normal OpenAI-compatible response. When a device is connected, requests continue to use the manual task queue.
