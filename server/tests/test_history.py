import os

os.environ.setdefault("RELAY_API_KEYS", "test-api-key")
os.environ.setdefault("RELAY_DEVICE_KEYS", "test-device:test-device-key")
os.environ.setdefault("RELAY_DEVICE_IDS", "test-device")
os.environ.setdefault("RELAY_TASK_TIMEOUT_SECONDS", "0.05")

import pytest
from fastapi.testclient import TestClient

from app.history import HistoryStore
from app.main import app, history


@pytest.fixture
def client() -> TestClient:
    with TestClient(app) as test_client:
        yield test_client


def headers() -> dict[str, str]:
    return {"Authorization": "Bearer test-api-key"}


def test_history_store_add_list_delete_clear(tmp_path) -> None:
    store = HistoryStore(str(tmp_path / "history.db"), limit=10)
    first = store.add(
        model="answerly",
        request_messages=[{"role": "user", "content": "hi"}],
        response_content="hello",
        source="manual",
        device_id="test-device",
    )
    second = store.add(
        model="answerly",
        request_messages=[{"role": "user", "content": "again"}],
        response_content=[{"type": "text", "text": "ok"}],
        source="auto_reply",
    )
    items = store.list()
    assert [item["id"] for item in items] == [second["id"], first["id"]]
    assert store.get(first["id"])["response"] == "hello"
    assert store.delete(first["id"]) is True
    assert store.get(first["id"]) is None
    assert store.clear() == 1
    assert store.list() == []


def test_history_store_merges_same_conversation(tmp_path) -> None:
    store = HistoryStore(str(tmp_path / "history.db"), limit=10)
    first = store.add(
        model="answerly",
        request_messages=[{"role": "user", "content": "你好哦"}],
        response_content="不哥们",
    )
    second = store.add(
        model="answerly",
        request_messages=[
            {"role": "user", "content": "你好哦"},
            {"role": "assistant", "content": "不哥们"},
            {"role": "user", "content": "商丘"},
        ],
        response_content="ee",
    )
    items = store.list()
    assert len(items) == 1
    assert second["id"] == first["id"]
    assert items[0]["response"] == "ee"
    assert len(items[0]["messages"]) == 3
    assert items[0]["updated_at"] >= items[0]["created_at"]


def test_history_store_keeps_distinct_conversations(tmp_path) -> None:
    store = HistoryStore(str(tmp_path / "db.sqlite"), limit=10)
    store.add(
        model="answerly",
        request_messages=[{"role": "user", "content": "话题 A"}],
        response_content="a",
    )
    store.add(
        model="answerly",
        request_messages=[{"role": "user", "content": "话题 B"}],
        response_content="b",
    )
    assert len(store.list()) == 2


def test_history_store_trims_to_limit(tmp_path) -> None:
    store = HistoryStore(str(tmp_path / "history.db"), limit=2)
    for index in range(4):
        store.add(
            model="answerly",
            request_messages=[{"role": "user", "content": f"m{index}"}],
            response_content=f"r{index}",
        )
    items = store.list()
    assert len(items) == 2
    assert [item["response"] for item in items] == ["r3", "r2"]


def test_history_endpoints_require_api_key(client: TestClient) -> None:
    assert client.get("/v1/history").status_code == 401
    assert client.delete("/v1/history").status_code == 401


def test_history_endpoints_roundtrip(client: TestClient) -> None:
    history.clear()
    entry = history.add(
        model="answerly",
        request_messages=[{"role": "user", "content": "history please"}],
        response_content="recorded",
        source="manual",
        device_id="test-device",
    )

    listing = client.get("/v1/history", headers=headers()).json()
    assert listing["object"] == "list"
    assert [item["id"] for item in listing["data"]] == [entry["id"]]
    assert listing["data"][0]["messages"][0]["content"] == "history please"

    single = client.get(f"/v1/history/{entry['id']}", headers=headers())
    assert single.status_code == 200
    assert single.json()["response"] == "recorded"

    deleted = client.delete(f"/v1/history/{entry['id']}", headers=headers())
    assert deleted.status_code == 200
    assert client.get("/v1/history", headers=headers()).json()["data"] == []
    assert client.get(f"/v1/history/{entry['id']}", headers=headers()).status_code == 404


def test_history_clear_endpoint(client: TestClient) -> None:
    history.clear()
    history.add(
        model="answerly",
        request_messages=[{"role": "user", "content": "one"}],
        response_content="a",
    )
    history.add(
        model="answerly",
        request_messages=[{"role": "user", "content": "two"}],
        response_content="b",
    )
    response = client.delete("/v1/history", headers=headers())
    assert response.status_code == 200
    assert response.json()["removed"] == 2
    assert client.get("/v1/history", headers=headers()).json()["data"] == []
