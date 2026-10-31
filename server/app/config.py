import os
from dataclasses import dataclass


def _csv(value: str) -> set[str]:
    return {item.strip() for item in value.split(",") if item.strip()}


def _device_keys(value: str) -> dict[str, str]:
    result: dict[str, str] = {}
    for item in value.split(","):
        item = item.strip()
        if not item:
            continue
        device_id, separator, key = item.partition(":")
        if not separator or not device_id or not key:
            raise ValueError("RELAY_DEVICE_KEYS must contain device_id:key pairs")
        result[device_id] = key
    return result


@dataclass(frozen=True)
class Settings:
    api_keys: set[str]
    device_keys: dict[str, str]
    device_ids: set[str]
    task_timeout_seconds: float
    model_id: str = "answerly"
    history_db_path: str = "data/history.db"
    history_limit: int = 500

    @classmethod
    def from_env(cls) -> "Settings":
        api_keys = _csv(os.getenv("RELAY_API_KEYS", ""))
        device_keys = _device_keys(os.getenv("RELAY_DEVICE_KEYS", ""))
        device_ids = _csv(os.getenv("RELAY_DEVICE_IDS", ",".join(device_keys)))
        if not api_keys:
            raise ValueError("RELAY_API_KEYS must not be empty")
        if not device_keys:
            raise ValueError("RELAY_DEVICE_KEYS must not be empty")
        if not device_ids:
            raise ValueError("RELAY_DEVICE_IDS must not be empty")
        return cls(
            api_keys=api_keys,
            device_keys=device_keys,
            device_ids=device_ids,
            task_timeout_seconds=float(os.getenv("RELAY_TASK_TIMEOUT_SECONDS", "120")),
            history_db_path=os.getenv("RELAY_HISTORY_DB", "data/history.db"),
            history_limit=int(os.getenv("RELAY_HISTORY_LIMIT", "500")),
        )

