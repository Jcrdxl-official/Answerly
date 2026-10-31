#!/usr/bin/env bash
# Answerly relay server launcher (Linux / macOS)
#
# Usage:
#   ./start.sh              start in foreground
#   ./start.sh --reload     dev mode, auto-reload on code change
#   ./start.sh --daemon     start in background, log to server.log
#   ./start.sh --stop       stop the background process
#   ./start.sh --help       show this help
#
# All settings can be overridden with environment variables, e.g.:
#   RELAY_API_KEYS=sk-xxx RELAY_DEVICE_KEYS=android-1:key ./start.sh

set -euo pipefail

cd "$(dirname "$0")"

HOST="${RELAY_HOST:-0.0.0.0}"
PORT="${RELAY_PORT:-8000}"
VENV_DIR="${RELAY_VENV:-.venv}"
PID_FILE=".server.pid"
LOG_FILE="server.log"

# Default credentials. Override these in production.
export RELAY_API_KEYS="${RELAY_API_KEYS:-dev-api-key}"
export RELAY_DEVICE_KEYS="${RELAY_DEVICE_KEYS:-android-1:dev-device-key}"
export RELAY_DEVICE_IDS="${RELAY_DEVICE_IDS:-android-1}"
export RELAY_TASK_TIMEOUT_SECONDS="${RELAY_TASK_TIMEOUT_SECONDS:-120}"
export RELAY_HISTORY_DB="${RELAY_HISTORY_DB:-data/history.db}"
export RELAY_HISTORY_LIMIT="${RELAY_HISTORY_LIMIT:-500}"

usage() {
  sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'
}

stop_server() {
  if [[ ! -f "$PID_FILE" ]]; then
    echo "No $PID_FILE found; server is probably not running in background."
    return 0
  fi
  local pid
  pid="$(cat "$PID_FILE")"
  if kill -0 "$pid" 2>/dev/null; then
    echo "Stopping Answerly server (pid $pid)..."
    kill "$pid" 2>/dev/null || true
    for _ in $(seq 1 20); do
      kill -0 "$pid" 2>/dev/null || break
      sleep 0.5
    done
    kill -0 "$pid" 2>/dev/null && kill -9 "$pid" 2>/dev/null || true
  fi
  rm -f "$PID_FILE"
  echo "Stopped."
}

find_python() {
  local candidate
  for candidate in python3 python; do
    if command -v "$candidate" >/dev/null 2>&1; then
      if "$candidate" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 11) else 1)' 2>/dev/null; then
        echo "$candidate"
        return 0
      fi
    fi
  done
  return 1
}

prepare_env() {
  local base_python
  if ! base_python="$(find_python)"; then
    echo "ERROR: Python 3.11+ not found." >&2
    echo "  Debian/Ubuntu: apt install -y python3 python3-venv python3-pip" >&2
    echo "  CentOS/RHEL:   yum install -y python3 python3-pip" >&2
    exit 1
  fi

  # Accept both POSIX (bin/python) and Windows (Scripts/python.exe) layouts.
  if [[ -x "$VENV_DIR/bin/python" ]]; then
    PY="$VENV_DIR/bin/python"
  elif [[ -x "$VENV_DIR/Scripts/python.exe" ]]; then
    PY="$VENV_DIR/Scripts/python.exe"
  else
    echo "Creating virtualenv in $VENV_DIR ..."
    rm -rf "$VENV_DIR"
    if ! "$base_python" -m venv "$VENV_DIR"; then
      echo "ERROR: failed to create virtualenv." >&2
      echo "  Debian/Ubuntu usually needs: apt install -y python3-venv" >&2
      exit 1
    fi
    if [[ -x "$VENV_DIR/bin/python" ]]; then
      PY="$VENV_DIR/bin/python"
    elif [[ -x "$VENV_DIR/Scripts/python.exe" ]]; then
      PY="$VENV_DIR/Scripts/python.exe"
    else
      echo "ERROR: virtualenv created but no python interpreter found in $VENV_DIR." >&2
      exit 1
    fi
  fi

  if ! "$PY" -c "import fastapi, uvicorn" >/dev/null 2>&1; then
    echo "Installing dependencies ..."
    local pip_args=()
    if [[ -n "${RELAY_PIP_INDEX:-}" ]]; then
      pip_args=(-i "$RELAY_PIP_INDEX")
    fi
    "$PY" -m pip install --upgrade pip "${pip_args[@]}" >/dev/null
    if ! "$PY" -m pip install "${pip_args[@]}" -r requirements.txt; then
      echo "ERROR: dependency installation failed." >&2
      exit 1
    fi
  fi
  mkdir -p data
}

case "${1:-}" in
  -h|--help)
    usage
    ;;
  --stop)
    stop_server
    ;;
  --reload)
    prepare_env
    echo "Dev mode: http://$HOST:$PORT"
    exec "$PY" -m uvicorn app.main:app --host "$HOST" --port "$PORT" --reload
    ;;
  --daemon)
    prepare_env
    if [[ -f "$PID_FILE" ]] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
      echo "Server already running (pid $(cat "$PID_FILE"))."
      exit 0
    fi
    echo "Starting in background, log: $LOG_FILE"
    nohup "$PY" -m uvicorn app.main:app --host "$HOST" --port "$PORT" >"$LOG_FILE" 2>&1 &
    echo $! >"$PID_FILE"
    sleep 1
    if kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
      echo "Started (pid $(cat "$PID_FILE")): http://$HOST:$PORT"
      echo "Follow log: tail -f $LOG_FILE"
    else
      echo "ERROR: failed to start, check $LOG_FILE" >&2
      exit 1
    fi
    ;;
  "")
    prepare_env
    echo "Starting Answerly server: http://$HOST:$PORT"
    exec "$PY" -m uvicorn app.main:app --host "$HOST" --port "$PORT"
    ;;
  *)
    echo "Unknown argument: $1" >&2
    usage
    exit 1
    ;;
esac