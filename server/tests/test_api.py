import asyncio
import base64
import os

os.environ.setdefault("RELAY_API_KEYS", "test-api-key")
os.environ.setdefault("RELAY_DEVICE_KEYS", "test-device:test-device-key")
os.environ.setdefault("RELAY_DEVICE_IDS", "test-device")
os.environ.setdefault("RELAY_TASK_TIMEOUT_SECONDS", "0.05")

import pytest
from fastapi.testclient import TestClient

from app.main import app
from app.tasks import TaskManager


@pytest.fixture
def client() -> TestClient:
    with TestClient(app) as test_client:
        yield test_client


def headers() -> dict[str, str]:
    return {"Authorization": "Bearer test-api-key"}


def test_models_requires_auth(client: TestClient) -> None:
    assert client.get("/v1/models").status_code == 401
    model = client.get("/v1/models", headers=headers()).json()["data"][0]
    assert model["id"] == "answerly"
    assert model["capabilities"]["output_modalities"] == ["text", "image"]


def test_chat_without_device_waits_until_timeout(client: TestClient) -> None:
    response = client.post("/v1/chat/completions", headers=headers(), json={"messages": [{"role": "user", "content": "Hi"}]})
    assert response.status_code == 504


def test_streaming_without_device_waits_until_timeout(client: TestClient) -> None:
    response = client.post("/v1/chat/completions", headers=headers(), json={"stream": True, "messages": [{"role": "user", "content": "Hi"}]})
    assert response.status_code == 504


def test_end_to_end_manual_answer(client: TestClient) -> None:
    with client.websocket_connect("/ws/device?device_id=test-device", headers={"X-Device-Key": "test-device-key"}) as device:
        async def request() -> dict:
            return await asyncio.to_thread(
                lambda: client.post(
                    "/v1/chat/completions",
                    headers=headers(),
                    json={"messages": [{"role": "user", "content": "Hi"}]},
                ).json()
            )

        async def answer() -> None:
            available = await asyncio.to_thread(device.receive_json)
            while not available["tasks"]:
                available = await asyncio.to_thread(device.receive_json)
            task = available["tasks"][0]
            await asyncio.to_thread(device.send_json, {"type": "task_claim", "task_id": task["task_id"]})
            claimed = await asyncio.to_thread(device.receive_json)
            assert claimed["type"] == "task_claimed"
            await asyncio.to_thread(device.send_json, {"type": "task_result", "task_id": task["task_id"], "content": "Hello"})

        async def run_both() -> tuple[dict, None]:
            return await asyncio.gather(request(), answer())

        result, _ = asyncio.run(run_both())
        assert result["choices"][0]["message"]["content"] == "Hello"


def test_end_to_end_image_answer(client: TestClient) -> None:
    with client.websocket_connect("/ws/device?device_id=test-device", headers={"X-Device-Key": "test-device-key"}) as device:
        async def request() -> dict:
            return await asyncio.to_thread(
                lambda: client.post(
                    "/v1/chat/completions",
                    headers=headers(),
                    json={"messages": [{"role": "user", "content": "send an image"}]},
                ).json()
            )

        async def answer() -> None:
            available = await asyncio.to_thread(device.receive_json)
            while not available["tasks"]:
                available = await asyncio.to_thread(device.receive_json)
            task = available["tasks"][0]
            await asyncio.to_thread(device.send_json, {"type": "task_claim", "task_id": task["task_id"]})
            await asyncio.to_thread(device.receive_json)
            image = base64.b64encode(b"fake-png").decode()
            await asyncio.to_thread(device.send_json, {
                "type": "task_result",
                "task_id": task["task_id"],
                "content": [
                    {"type": "text", "text": "see image"},
                    {"type": "image", "mime_type": "image/png", "data": image},
                ],
            })

        async def run_both() -> tuple[dict, None]:
            return await asyncio.gather(request(), answer())

        result, _ = asyncio.run(run_both())
        image = result["choices"][0]["message"]["content"][1]
        assert image["type"] == "image_url"
        assert image["image_url"]["url"].startswith("data:image/png;base64,")


def test_task_timeout(client: TestClient) -> None:
    with client.websocket_connect("/ws/device?device_id=test-device", headers={"X-Device-Key": "test-device-key"}):
        response = client.post("/v1/chat/completions", headers=headers(), json={"messages": [{"role": "user", "content": "Hi"}]})
        assert response.status_code == 504


@pytest.mark.asyncio
async def test_tasks_can_be_claimed_in_any_order() -> None:
    class FakeWebSocket:
        def __init__(self) -> None:
            self.messages: list[dict] = []

        async def send_json(self, message: dict) -> None:
            self.messages.append(message)

    manager = TaskManager()
    device = FakeWebSocket()
    await manager.connect("device", device)
    first = asyncio.create_task(manager.dispatch({"model": "answerly", "messages": [{"content": "first"}]}, 1))
    second = asyncio.create_task(manager.dispatch({"model": "answerly", "messages": [{"content": "second"}]}, 1))
    await asyncio.sleep(0)

    available = device.messages[-1]["tasks"]
    assert len(available) == 2
    second_id = available[1]["task_id"]
    first_id = available[0]["task_id"]
    assert await manager.claim("device", device, second_id) == "claimed"
    await manager.resolve("device", device, second_id, {"task_id": second_id, "content": "second answer"})
    await asyncio.sleep(0)
    assert await manager.claim("device", device, first_id) == "claimed"
    await manager.resolve("device", device, first_id, {"task_id": first_id, "content": "first answer"})

    results = await asyncio.gather(first, second)
    assert {result["content"] for result in results} == {"first answer", "second answer"}


@pytest.mark.asyncio
async def test_task_waits_for_device_that_connects_later() -> None:
    class FakeWebSocket:
        def __init__(self) -> None:
            self.messages: list[dict] = []

        async def send_json(self, message: dict) -> None:
            self.messages.append(message)

    manager = TaskManager()
    request = asyncio.create_task(
        manager.dispatch(
            {"model": "answerly", "messages": [{"role": "user", "content": "later"}]},
            1,
        )
    )
    await asyncio.sleep(0)
    device = FakeWebSocket()
    await manager.connect("device", device)
    task = device.messages[-1]["tasks"][0]
    assert await manager.claim("device", device, task["task_id"]) == "claimed"
    await manager.resolve("device", device, task["task_id"], {"task_id": task["task_id"], "content": "answer"})
    assert (await request)["content"] == "answer"


@pytest.mark.asyncio
async def test_offline_auto_reply_returns_without_device() -> None:
    manager = TaskManager()
    await manager.configure_auto_reply("device", True, "服务暂时离线，请稍后再试")
    result = await manager.dispatch(
        {"model": "answerly", "messages": [{"role": "user", "content": "Hi"}]},
        1,
    )
    assert result["content"] == "服务暂时离线，请稍后再试"
