package com.answerly.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Keeps the relay WebSocket alive independently of any activity.
 *
 * Running as a foreground service means the connection (and therefore the
 * "new question" notifications) keeps working after the user swipes the app
 * out of the recents list.
 */
class ServiceRelay : Service() {
    companion object {
        const val ACTION_CONNECT = "com.answerly.app.action.CONNECT"
        const val ACTION_DISCONNECT = "com.answerly.app.action.DISCONNECT"
        const val ACTION_CLAIM = "com.answerly.app.action.CLAIM"
        const val ACTION_RESULT = "com.answerly.app.action.RESULT"
        const val ACTION_UPDATE_AUTO_REPLY = "com.answerly.app.action.UPDATE_AUTO_REPLY"

        const val EXTRA_URL = "url"
        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_DEVICE_KEY = "device_key"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_CONTENT = "content"
        const val EXTRA_ENABLED = "enabled"

        private const val RECONNECT_DELAY_MS = 5_000L

        fun connect(context: Context, url: String, deviceId: String, deviceKey: String) {
            val intent = Intent(context, ServiceRelay::class.java)
                .setAction(ACTION_CONNECT)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_DEVICE_ID, deviceId)
                .putExtra(EXTRA_DEVICE_KEY, deviceKey)
            start(context, intent)
        }

        fun disconnect(context: Context) {
            start(context, Intent(context, ServiceRelay::class.java).setAction(ACTION_DISCONNECT))
        }

        fun claim(context: Context, taskId: String) {
            start(
                context,
                Intent(context, ServiceRelay::class.java)
                    .setAction(ACTION_CLAIM)
                    .putExtra(EXTRA_TASK_ID, taskId)
            )
        }

        fun sendResult(context: Context, taskId: String, content: String) {
            start(
                context,
                Intent(context, ServiceRelay::class.java)
                    .setAction(ACTION_RESULT)
                    .putExtra(EXTRA_TASK_ID, taskId)
                    .putExtra(EXTRA_CONTENT, content)
            )
        }

        fun updateAutoReply(context: Context, enabled: Boolean, content: String) {
            start(
                context,
                Intent(context, ServiceRelay::class.java)
                    .setAction(ACTION_UPDATE_AUTO_REPLY)
                    .putExtra(EXTRA_ENABLED, enabled)
                    .putExtra(EXTRA_CONTENT, content)
            )
        }

        private fun start(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val httpClient = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null
    private var url: String = ""
    private var deviceId: String = ""
    private var deviceKey: String = ""
    private var autoReplyEnabled: Boolean = false
    private var autoReplyContent: String = ""
    private var reconnectScheduled = false
    private var stopped = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannel(this)
        val prefs = Prefs.get(this)
        url = prefs.getString("url", "").orEmpty()
        deviceId = prefs.getString("device_id", "").orEmpty()
        deviceKey = prefs.getString("device_key", "").orEmpty()
        autoReplyEnabled = prefs.getBoolean("auto_reply_enabled", false)
        autoReplyContent = prefs.getString("auto_reply_content", "").orEmpty()
        startInForeground(getString(R.string.status_connecting))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                url = intent.getStringExtra(EXTRA_URL).orEmpty()
                deviceId = intent.getStringExtra(EXTRA_DEVICE_ID).orEmpty()
                deviceKey = intent.getStringExtra(EXTRA_DEVICE_KEY).orEmpty()
                stopped = false
                openSocket()
            }
            ACTION_DISCONNECT -> {
                stopped = true
                socket?.close(1000, "user disconnected")
                socket = null
                Prefs.get(this).edit().putBoolean("relay_enabled", false).apply()
                RelayState.setStatus(RelayState.Status.DISCONNECTED)
                stopSelf()
            }
            ACTION_CLAIM -> intent.getStringExtra(EXTRA_TASK_ID)?.let { taskId ->
                socket?.send(JSONObject().put("type", "task_claim").put("task_id", taskId).toString())
            }
            ACTION_RESULT -> {
                val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return START_STICKY
                val content = intent.getStringExtra(EXTRA_CONTENT).orEmpty()
                val payload = JSONObject()
                    .put("type", "task_result")
                    .put("task_id", taskId)
                    .put("content", parseContent(content))
                    .put("finish_reason", "stop")
                socket?.send(payload.toString())
                Notifications.cancelTask(this, taskId)
            }
            ACTION_UPDATE_AUTO_REPLY -> {
                autoReplyEnabled = intent.getBooleanExtra(EXTRA_ENABLED, false)
                autoReplyContent = intent.getStringExtra(EXTRA_CONTENT).orEmpty()
                sendAutoReplyConfig()
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away must not kill the connection.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopped = true
        socket?.close(1000, "service destroyed")
        socket = null
        httpClient.dispatcher.executorService.shutdown()
        RelayState.setStatus(RelayState.Status.DISCONNECTED)
        super.onDestroy()
    }

    private fun startInForeground(text: String) {
        val notification = Notifications.buildServiceNotification(this, text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                Notifications.ID_SERVICE,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(Notifications.ID_SERVICE, notification)
        }
    }

    private fun updateServiceNotification(text: String) {
        val manager = getSystemService(android.app.NotificationManager::class.java) ?: return
        manager.notify(Notifications.ID_SERVICE, Notifications.buildServiceNotification(this, text))
    }

    private fun openSocket() {
        if (url.isEmpty()) return
        socket?.close(1000, "reconnecting")
        RelayState.setStatus(RelayState.Status.CONNECTING)
        updateServiceNotification(getString(R.string.status_connecting))
        val request = Request.Builder()
            .url("$url?device_id=${java.net.URLEncoder.encode(deviceId, "UTF-8")}")
            .header("X-Device-Key", deviceKey)
            .build()
        socket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                RelayState.setStatus(RelayState.Status.CONNECTED)
                updateServiceNotification(getString(R.string.status_connected))
                Notifications.cancelDisconnected(this@ServiceRelay)
                sendAutoReplyConfig()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = try {
                    JSONObject(text)
                } catch (_: Exception) {
                    return
                }
                when (message.optString("type")) {
                    "task_available" -> {
                        val incoming = message.optJSONArray("tasks") ?: JSONArray()
                        val list = mutableListOf<JSONObject>()
                        for (index in 0 until incoming.length()) {
                            list.add(incoming.getJSONObject(index))
                        }
                        val known = RelayState.tasks.value.map { it.optString("task_id") }.toSet()
                        val fresh = list.filter { it.optString("task_id") !in known }
                        RelayState.setTasks(list)
                        val prefs = Prefs.get(this@ServiceRelay)
                        if (prefs.getBoolean("notify_new_task", true)) {
                            fresh.forEach {
                                Notifications.notifyNewTask(this@ServiceRelay, it.optString("task_id"), taskSummary(it))
                            }
                        }
                    }
                    "task_claimed" -> RelayState.emit(RelayState.Event.TaskClaimed(message.optString("task_id")))
                    "task_unavailable" -> RelayState.emit(RelayState.Event.TaskUnavailable)
                    "history_deleted", "history_cleared" -> RelayState.emit(RelayState.Event.HistoryChanged)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                RelayState.setStatus(RelayState.Status.DISCONNECTED)
                updateServiceNotification(getString(R.string.status_disconnected))
                Notifications.notifyDisconnected(this@ServiceRelay, t.message ?: response?.message ?: "")
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                RelayState.setStatus(RelayState.Status.DISCONNECTED)
                updateServiceNotification(getString(R.string.status_disconnected))
                if (code != 1000) {
                    Notifications.notifyDisconnected(this@ServiceRelay, reason)
                    scheduleReconnect()
                }
            }
        })
    }

    private fun scheduleReconnect() {
        if (stopped || reconnectScheduled) return
        reconnectScheduled = true
        android.os.Handler(mainLooper).postDelayed({
            reconnectScheduled = false
            if (!stopped) openSocket()
        }, RECONNECT_DELAY_MS)
    }

    private fun sendAutoReplyConfig() {
        socket?.send(
            JSONObject()
                .put("type", "auto_reply_config")
                .put("enabled", autoReplyEnabled)
                .put("content", autoReplyContent)
                .toString()
        )
    }

    private fun parseContent(content: String): Any = try {
        JSONArray(content)
    } catch (_: Exception) {
        content
    }

    private fun taskSummary(task: JSONObject): String {
        val messages = task.optJSONArray("messages") ?: return getString(R.string.task_new)
        if (messages.length() == 0) return getString(R.string.task_new)
        val last = messages.getJSONObject(messages.length() - 1)
        return last.optString("content", "").replace("\n", " ").take(100)
            .ifEmpty { getString(R.string.task_new) }
    }
}
