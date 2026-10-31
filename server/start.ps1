# Answerly 服务端启动脚本 (Windows PowerShell)
#
# 用法:
#   .\start.ps1              前台启动
#   .\start.ps1 -Reload      开发模式，代码改动自动重载
#   .\start.ps1 -Daemon      后台启动，日志写入 server.log
#   .\start.ps1 -Stop        停止后台进程
#
# 所有配置都可以用环境变量覆盖，例如:
#   $env:RELAY_API_KEYS="sk-xxx"; .\start.ps1

[CmdletBinding()]
param(
    [switch]$Reload,
    [switch]$Daemon,
    [switch]$Stop
)

$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

$BindHost = if ($env:RELAY_HOST) { $env:RELAY_HOST } else { '0.0.0.0' }
$Port = if ($env:RELAY_PORT) { $env:RELAY_PORT } else { '8000' }
$VenvDir = if ($env:RELAY_VENV) { $env:RELAY_VENV } else { '.venv' }
$PidFile = '.server.pid'
$LogFile = 'server.log'

# 默认凭据，生产环境请务必用环境变量覆盖
if (-not $env:RELAY_API_KEYS) { $env:RELAY_API_KEYS = 'dev-api-key' }
if (-not $env:RELAY_DEVICE_KEYS) { $env:RELAY_DEVICE_KEYS = 'android-1:dev-device-key' }
if (-not $env:RELAY_DEVICE_IDS) { $env:RELAY_DEVICE_IDS = 'android-1' }
if (-not $env:RELAY_TASK_TIMEOUT_SECONDS) { $env:RELAY_TASK_TIMEOUT_SECONDS = '120' }
if (-not $env:RELAY_HISTORY_DB) { $env:RELAY_HISTORY_DB = 'data/history.db' }
if (-not $env:RELAY_HISTORY_LIMIT) { $env:RELAY_HISTORY_LIMIT = '500' }

function Stop-Server {
    if (-not (Test-Path $PidFile)) {
        Write-Host '没有找到 .server.pid，服务端可能未在后台运行。'
        return
    }
    $serverPid = (Get-Content $PidFile -Raw).Trim()
    if ($serverPid -and (Get-Process -Id $serverPid -ErrorAction SilentlyContinue)) {
        Write-Host "停止 Answerly 服务端 (pid $serverPid)..."
        Stop-Process -Id $serverPid -Force
    }
    Remove-Item $PidFile -Force -ErrorAction SilentlyContinue
    Write-Host '已停止。'
}

function Test-Deps {
    param([string]$Python)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $Python -c "import fastapi, uvicorn" *> $null
        return ($LASTEXITCODE -eq 0)
    } finally {
        $ErrorActionPreference = $previous
    }
}

function Initialize-Env {
    if (-not (Test-Path $VenvDir)) {
        Write-Host "创建虚拟环境 $VenvDir ..."
        python -m venv $VenvDir
        if ($LASTEXITCODE -ne 0) { throw '创建虚拟环境失败，请确认 Python 3.11+ 已安装。' }
    }
    $python = Join-Path $VenvDir 'Scripts\python.exe'
    if (-not (Test-Path $python)) {
        throw "找不到 $python，请确认 Python 3.11+ 已安装并在 PATH 中。"
    }
    if (-not (Test-Deps -Python $python)) {
        Write-Host '安装依赖...'
        $pipArgs = @()
        if ($env:RELAY_PIP_INDEX) { $pipArgs = @('-i', $env:RELAY_PIP_INDEX) }
        & $python -m pip install --upgrade pip @pipArgs | Out-Null
        & $python -m pip install @pipArgs -r requirements.txt
        if ($LASTEXITCODE -ne 0) { throw '依赖安装失败。' }
    }
    New-Item -ItemType Directory -Force -Path 'data' | Out-Null
    return $python
}

if ($Stop) {
    Stop-Server
    return
}

$python = Initialize-Env
$uvicornArgs = @('-m', 'uvicorn', 'app.main:app', '--host', $BindHost, '--port', $Port)
if ($Reload) { $uvicornArgs += '--reload' }

if ($Daemon) {
    if (Test-Path $PidFile) {
        $existing = (Get-Content $PidFile -Raw).Trim()
        if ($existing -and (Get-Process -Id $existing -ErrorAction SilentlyContinue)) {
            Write-Host "服务端已在运行 (pid $existing)。"
            return
        }
    }
    Write-Host "后台启动，日志: $LogFile"
    $proc = Start-Process -FilePath $python -ArgumentList $uvicornArgs `
        -RedirectStandardOutput $LogFile -RedirectStandardError "$LogFile.err" `
        -WindowStyle Hidden -PassThru
    $proc.Id | Set-Content $PidFile
    Start-Sleep -Seconds 2
    if (Get-Process -Id $proc.Id -ErrorAction SilentlyContinue) {
        Write-Host "已启动 (pid $($proc.Id)): http://${BindHost}:$Port"
        Write-Host "查看日志: Get-Content $LogFile -Wait"
    } else {
        Write-Output "启动失败，请检查 $LogFile 与 $LogFile.err"
        exit 1
    }
} else {
    Write-Host "启动 Answerly 服务端: http://${BindHost}:$Port"
    & $python @uvicornArgs
}
