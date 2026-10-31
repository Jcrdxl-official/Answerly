# Answerly

Answerly 是一个不接入外部模型的 MVP：OpenAI 兼容 API 接收请求，服务端通过 WebSocket 把请求转给已认证的 Android 客户端，用户手动输入回答后再返回兼容 OpenAI 的响应。

## 目录

- `server/`：FastAPI 服务端
- `android/`：Kotlin Android 客户端
- `docs/PROTOCOL.md`：HTTP/WebSocket 协议
- `docker-compose.yml`：Docker 启动配置

## 聊天记录

服务端会把每次完成的请求（人工回答或离线自动回复）写入 SQLite（默认 `data/history.db`，可用 `RELAY_HISTORY_DB` 修改，最多保留 `RELAY_HISTORY_LIMIT` 条，默认 500）。

同一个对话（以首条 user 消息内容作为 `conversation_key`）的多轮请求会合并成一条记录：后续请求会更新该记录的消息列表与最新回答，而不是新增一行。因此历史列表里一个对话只出现一次。

- `GET /v1/history?limit=100&offset=0`：列出记录（API Key 或设备密钥均可）
- `GET /v1/history/{id}`：查看单条
- `DELETE /v1/history/{id}`：删除单条，并广播 `history_deleted` 给所有在线设备
- `DELETE /v1/history`：清空全部，并广播 `history_cleared`

Android 客户端首页的“聊天”页会平铺展示全部历史记录，点击卡片进入只读聊天页；卡片上的删除按钮会同步删除服务端记录，顶部“清空”按钮会清空全部。

## Android 界面

- 首页底栏两个页面：**聊天**（待回答问题 + 平铺的聊天记录）与**设置**。
- 首页左上角显示应用名 `Answerly`（KernelSU 风格纯文字标题，无 logo）。
- 设置页包含：连接设置、外观（莫奈取色开关）、离线自动回复。
- 连接成功与连接失败都会弹出提示弹窗。

## 快速启动服务端

需要 Python 3.11+。

### 一键脚本（推荐）

Linux / macOS：

```bash
cd server
chmod +x start.sh
./start.sh              # 前台启动
./start.sh --daemon     # 后台启动，日志写入 server.log
./start.sh --reload     # 开发模式，代码改动自动重载
./start.sh --stop       # 停止后台进程
```

脚本会自动创建 `.venv`、安装依赖、创建 `data/` 目录，并在未设置环境变量时使用默认凭据。

Windows（PowerShell）：

```powershell
cd server
.\start.ps1             # 前台启动
.\start.ps1 -Daemon     # 后台启动
.\start.ps1 -Reload     # 开发模式
.\start.ps1 -Stop       # 停止后台进程
```

如果提示「禁止运行脚本」，改用：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\start.ps1 -Daemon
```

### 后台运行与日志

```bash
./start.sh --daemon          # 后台启动，pid 写入 .server.pid
tail -f server.log           # 实时查看日志
./start.sh --stop            # 停止后台进程
```

Windows 下对应 `.\start.ps1 -Daemon`、`Get-Content server.log -Wait`、`.\start.ps1 -Stop`。

### systemd（Linux 常驻）

```ini
# /etc/systemd/system/answerly.service
[Unit]
Description=Answerly relay server
After=network.target

[Service]
Type=simple
WorkingDirectory=/root/Answerly/server
Environment=RELAY_API_KEYS=dev-api-key
Environment=RELAY_DEVICE_KEYS=android-1:dev-device-key
Environment=RELAY_DEVICE_IDS=android-1
ExecStart=/root/Answerly/server/.venv/bin/uvicorn app.main:app --host 0.0.0.0 --port 8000
Restart=always

[Install]
WantedBy=multi-user.target
```

```bash
systemctl daemon-reload
systemctl enable --now answerly
journalctl -u answerly -f
```

### 手动启动

```bash
cd server
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
export RELAY_API_KEYS="dev-api-key"
export RELAY_DEVICE_KEYS="android-1:dev-device-key"
export RELAY_DEVICE_IDS="android-1"
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

### 环境变量

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `RELAY_API_KEYS` | `dev-api-key` | 允许调用 `/v1/*` 的 API Key，逗号分隔 |
| `RELAY_DEVICE_KEYS` | `android-1:dev-device-key` | 设备 ID 与设备密钥，格式 `id:key`，逗号分隔 |
| `RELAY_DEVICE_IDS` | 由 `RELAY_DEVICE_KEYS` 推导 | 允许连接的设备 ID |
| `RELAY_TASK_TIMEOUT_SECONDS` | `120` | 单个任务等待人工回答的超时秒数 |
| `RELAY_HISTORY_DB` | `data/history.db` | SQLite 历史库路径 |
| `RELAY_HISTORY_LIMIT` | `500` | 历史记录最大保留条数 |
| `RELAY_HOST` / `RELAY_PORT` | `0.0.0.0` / `8000` | 仅启动脚本使用 |
| `RELAY_PIP_INDEX` | 空 | 仅启动脚本使用，指定 pip 镜像源，例如 `https://pypi.tuna.tsinghua.edu.cn/simple` |

使用 Docker（服务名 answerly-server）：

```bash
docker compose up --build
```

## 运行 Android 客户端

用 Android Studio 打开 `android/`，连接设备或模拟器后运行 `app`。在设置区域填写：

- 服务端 WebSocket 地址，例如 `ws://10.0.2.2:8000/ws/device`
- 设备 ID：`android-1`
- 设备密钥：`dev-device-key`

服务端 API Key 通过 `Authorization: Bearer dev-api-key` 或 `X-API-Key: dev-api-key` 发送。

## 最小验证

```powershell
cd server
pip install -r requirements-dev.txt
python -m pytest
```

测试覆盖模型列表、API Key 鉴权、设备 WebSocket 认证、端到端非流式回答和任务超时。

