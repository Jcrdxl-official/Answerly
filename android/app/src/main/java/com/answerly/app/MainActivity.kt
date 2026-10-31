package com.answerly.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_OPEN_TASK_ID = "open_task_id"
        private const val KEY_NOTIFY_NEW_TASK = "notify_new_task"
        private const val KEY_NOTIFY_DISCONNECT = "notify_disconnect"
        private const val KEY_RELAY_ENABLED = "relay_enabled"
    }

    private val httpClient = OkHttpClient()

    private var selectedTaskId: String? = null
    private val tasks = linkedMapOf<String, JSONObject>()
    private lateinit var status: TextView
    private lateinit var taskList: LinearLayout
    private lateinit var historyContainer: LinearLayout
    private lateinit var historyStatus: TextView
    private lateinit var deviceKey: EditText
    private lateinit var autoReplyEnabled: CheckBox
    private lateinit var autoReplyInput: EditText
    private lateinit var chatPage: View
    private lateinit var settingsPage: View
    private lateinit var notifyNewTaskSwitch: com.google.android.material.materialswitch.MaterialSwitch
    private lateinit var notifyDisconnectSwitch: com.google.android.material.materialswitch.MaterialSwitch
    private var pendingOpenTaskId: String? = null
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    private val preferences by lazy { Prefs.get(this) }
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    private val chatLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) {
            selectedTaskId = null
            renderTasks()
            return@registerForActivityResult
        }
        val data = result.data ?: return@registerForActivityResult
        val taskId = data.getStringExtra(ChatActivity.EXTRA_TASK_ID) ?: return@registerForActivityResult
        val content = data.getStringExtra(ChatActivity.EXTRA_CONTENT) ?: return@registerForActivityResult
        sendTaskResult(taskId, content)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.migrate(this)
        Monet.apply(this)
        setContentView(R.layout.activity_main)
        applyEdgeToEdge(findViewById(R.id.rootLayout))
        status = findViewById(R.id.statusText)
        taskList = findViewById(R.id.taskList)
        historyContainer = findViewById(R.id.historyContainer)
        historyStatus = findViewById(R.id.historyStatus)
        deviceKey = findViewById(R.id.deviceKeyInput)
        autoReplyEnabled = findViewById(R.id.autoReplyEnabled)
        autoReplyInput = findViewById(R.id.autoReplyInput)
        chatPage = findViewById(R.id.chatPage)
        settingsPage = findViewById(R.id.settingsPage)
        notifyNewTaskSwitch = findViewById(R.id.notifyNewTaskSwitch)
        notifyDisconnectSwitch = findViewById(R.id.notifyDisconnectSwitch)
        loadPreferences()
        setupNotifications()
        observeRelay()
        handleOpenTaskIntent(intent)
        startRelayIfConfigured()

        findViewById<Button>(R.id.connectButton).setOnClickListener {
            connect(
                findViewById<EditText>(R.id.urlInput).text.toString(),
                findViewById<EditText>(R.id.deviceIdInput).text.toString(),
                deviceKey.text.toString()
            )
        }
        findViewById<Button>(R.id.saveAutoReplyButton).setOnClickListener {
            savePreferences()
            ServiceRelay.updateAutoReply(
                this,
                autoReplyEnabled.isChecked,
                autoReplyInput.text.toString()
            )
            status.text = getString(R.string.auto_reply_saved)
        }
        findViewById<Button>(R.id.refreshHistoryButton).setOnClickListener { loadHistory() }
        findViewById<Button>(R.id.clearHistoryButton).setOnClickListener { confirmClear() }

        val monetSwitch = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.monetSwitch)
        monetSwitch.isChecked = Monet.isEnabled(this)
        monetSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked == Monet.isEnabled(this)) return@setOnCheckedChangeListener
            Monet.setEnabled(this, checked)
            recreate()
        }

        findViewById<BottomNavigationView>(R.id.bottomNav).setOnItemSelectedListener { item ->
            val showChat = item.itemId == R.id.nav_chat
            chatPage.visibility = if (showChat) View.VISIBLE else View.GONE
            settingsPage.visibility = if (showChat) View.GONE else View.VISIBLE
            if (showChat) loadHistory()
            true
        }

        findViewById<Button>(R.id.batteryOptimizationButton).setOnClickListener {
            requestIgnoreBatteryOptimizations()
        }
        findViewById<Button>(R.id.autostartButton).setOnClickListener {
            openAutostartSettings()
        }

        loadHistory()
    }

    private fun requestIgnoreBatteryOptimizations() {
        val manager = getSystemService(android.os.PowerManager::class.java)
        if (manager?.isIgnoringBatteryOptimizations(packageName) == true) {
            status.text = getString(R.string.background_battery_done)
            return
        }
        val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(android.net.Uri.parse("package:$packageName"))
        try {
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun openAutostartSettings() {
        val candidates = listOf(
            "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.huawei.systemmanager/.startupmgr.ui.StartupNormalAppListActivity",
            "com.coloros.safecenter/.permission.startup.StartupAppListActivity",
            "com.vivo.permissionmanager/.activity.BgStartUpManagerActivity",
            "com.samsung.android.lool/.activity.BatteryActivity"
        )
        for (name in candidates) {
            val parts = name.split("/")
            val intent = Intent().setClassName(parts[0], parts[1])
            if (packageManager.resolveActivity(intent, 0) != null) {
                try {
                    startActivity(intent)
                    return
                } catch (_: Exception) {
                    // try the next vendor entry point
                }
            }
        }
        try {
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            status.text = getString(R.string.background_autostart_missing)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOpenTaskIntent(intent)
    }

    private fun connect(url: String, deviceId: String, deviceKey: String) {
        savePreferences()
        preferences.edit().putBoolean(KEY_RELAY_ENABLED, true).apply()
        status.text = getString(R.string.status_connecting)
        ServiceRelay.connect(this, url, deviceId, deviceKey)
    }

    private fun startRelayIfConfigured() {
        if (!preferences.getBoolean(KEY_RELAY_ENABLED, false)) return
        val url = preferences.getString("url", "").orEmpty()
        val deviceId = preferences.getString("device_id", "").orEmpty()
        val key = preferences.getString("device_key", "").orEmpty()
        if (url.isEmpty() || key.isEmpty()) return
        ServiceRelay.connect(this, url, deviceId, key)
    }

    private fun observeRelay() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    RelayState.status.collect { value ->
                        status.text = when (value) {
                            RelayState.Status.DISCONNECTED -> getString(R.string.status_disconnected)
                            RelayState.Status.CONNECTING -> getString(R.string.status_connecting)
                            RelayState.Status.CONNECTED -> getString(R.string.status_waiting)
                        }
                    }
                }
                launch {
                    RelayState.tasks.collect { list ->
                        tasks.clear()
                        list.forEach { tasks[it.optString("task_id")] = it }
                        renderTasks()
                        openPendingTaskIfReady()
                    }
                }
                launch {
                    RelayState.events.collect { event ->
                        when (event) {
                            is RelayState.Event.TaskClaimed -> {
                                val task = tasks[event.taskId] ?: return@collect
                                status.text = getString(R.string.status_claiming)
                                chatLauncher.launch(
                                    Intent(this@MainActivity, ChatActivity::class.java)
                                        .putExtra(ChatActivity.EXTRA_TASK_ID, event.taskId)
                                        .putExtra(ChatActivity.EXTRA_TASK, task.toString())
                                )
                            }
                            RelayState.Event.TaskUnavailable -> {
                                selectedTaskId = null
                                status.text = getString(R.string.status_task_taken)
                                renderTasks()
                            }
                            RelayState.Event.HistoryChanged -> loadHistory()
                        }
                    }
                }
            }
        }
    }

    private fun loadPreferences() {
        findViewById<EditText>(R.id.urlInput).setText(
            preferences.getString("url", "ws://114.55.117.16:8000/ws/device")
        )
        findViewById<EditText>(R.id.deviceIdInput).setText(
            preferences.getString("device_id", "android-1")
        )
        deviceKey.setText(preferences.getString("device_key", ""))
        autoReplyEnabled.isChecked = preferences.getBoolean("auto_reply_enabled", false)
        autoReplyInput.setText(preferences.getString("auto_reply_content", ""))
    }

    private fun savePreferences() {
        preferences.edit()
            .putString("url", findViewById<EditText>(R.id.urlInput).text.toString())
            .putString("device_id", findViewById<EditText>(R.id.deviceIdInput).text.toString())
            .putString("device_key", deviceKey.text.toString())
            .putBoolean("auto_reply_enabled", autoReplyEnabled.isChecked)
            .putString("auto_reply_content", autoReplyInput.text.toString())
            .apply()
    }

    private fun renderTasks() {
        taskList.removeAllViews()
        for ((taskId, task) in tasks) {
            val button = MaterialButton(this)
            button.text = taskSummary(task)
            button.setAllCaps(false)
            button.cornerRadius = 20
            button.strokeWidth = 0
            button.setPadding(20, 12, 20, 12)
            button.setOnClickListener { selectTask(taskId) }
            button.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 8, 0, 0) }
            taskList.addView(button)
        }
        if (tasks.isEmpty() && selectedTaskId == null) {
            status.text = getString(R.string.status_waiting)
        }
    }

    private fun taskSummary(task: JSONObject): String {
        val messages = task.optJSONArray("messages") ?: return getString(R.string.task_new)
        if (messages.length() == 0) return getString(R.string.task_new)
        val last = messages.getJSONObject(messages.length() - 1)
        val content = last.optString("content", "").replace("\n", " ")
        return "${last.optString("role", "user")}: ${content.take(100)}"
    }

    private fun selectTask(taskId: String) {
        if (selectedTaskId != null) return
        selectedTaskId = taskId
        status.text = getString(R.string.status_claiming)
        ServiceRelay.claim(this, taskId)
    }

    private fun sendTaskResult(taskId: String, content: String) {
        ServiceRelay.sendResult(this, taskId, content)
        Notifications.cancelTask(this, taskId)
        selectedTaskId = null
        tasks.remove(taskId)
        status.text = getString(R.string.status_answer_sent)
        renderTasks()
        loadHistory()
    }

    private fun httpBase(): String {
        var base = findViewById<EditText>(R.id.urlInput).text.toString().trim()
        base = base.replaceFirst("^ws://", "http://").replaceFirst("^wss://", "https://")
        val marker = base.indexOf("/ws/")
        if (marker >= 0) base = base.substring(0, marker)
        return base.trimEnd('/')
    }

    private fun loadHistory() {
        val key = deviceKey.text.toString()
        if (key.isEmpty()) {
            historyStatus.text = getString(R.string.history_need_key)
            return
        }
        historyStatus.text = getString(R.string.loading)
        val request = Request.Builder()
            .url("${httpBase()}/v1/history?limit=200")
            .header("X-Device-Key", key)
            .build()
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { historyStatus.text = getString(R.string.history_load_failed, e.message ?: "") }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                runOnUiThread {
                    if (!response.isSuccessful) {
                        historyStatus.text = getString(R.string.history_http_failed, response.code)
                        return@runOnUiThread
                    }
                    try {
                        renderHistory(JSONObject(body).optJSONArray("data") ?: JSONArray())
                    } catch (_: Exception) {
                        historyStatus.text = getString(R.string.history_parse_failed)
                    }
                }
            }
        })
    }

    private fun renderHistory(items: JSONArray) {
        historyContainer.removeAllViews()
        historyStatus.text = if (items.length() == 0) {
            getString(R.string.history_empty)
        } else {
            getString(R.string.history_count, items.length())
        }
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            historyContainer.addView(buildHistoryCard(item))
        }
    }

    private fun buildHistoryCard(item: JSONObject): MaterialCardView {
        val card = MaterialCardView(this).apply {
            radius = ChatBubbles.dp(this@MainActivity, 18).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(
                ChatBubbles.themeColor(this@MainActivity, com.google.android.material.R.attr.colorSurfaceContainerHigh)
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = ChatBubbles.dp(this@MainActivity, 10) }
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                ChatBubbles.dp(this@MainActivity, 14),
                ChatBubbles.dp(this@MainActivity, 12),
                ChatBubbles.dp(this@MainActivity, 14),
                ChatBubbles.dp(this@MainActivity, 12)
            )
        }
        val created = item.optLong("updated_at", item.optLong("created_at", 0L)) * 1000
        val turns = ((item.optJSONArray("messages")?.length() ?: 0) + 1) / 2
        val header = TextView(this).apply {
            text = "${timeLabel(created)} · ${sourceLabel(item.optString("source"))} · ${getString(R.string.turns, turns)}"
            textSize = 11f
            setTextColor(ChatBubbles.themeColor(this@MainActivity, com.google.android.material.R.attr.colorOnSurfaceVariant))
        }
        val preview = TextView(this).apply {
            text = previewText(item)
            textSize = 15f
            maxLines = 3
            setTextColor(ChatBubbles.themeColor(this@MainActivity, com.google.android.material.R.attr.colorOnSurface))
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        actions.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.view)
            setOnClickListener { openDetail(item) }
        })
        actions.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.delete)
            setOnClickListener { confirmDelete(item) }
        })
        inner.addView(header)
        inner.addView(preview)
        inner.addView(actions)
        card.addView(inner)
        return card
    }

    private fun previewText(item: JSONObject): String {
        val messages = item.optJSONArray("messages")
        val last = messages?.optJSONObject((messages.length() - 1).coerceAtLeast(0))
        val question = contentToText(last?.opt("content"))
        val answer = contentToText(item.opt("response"))
        return getString(R.string.history_preview, question.take(80), answer.take(80))
    }

    private fun contentToText(content: Any?): String = when (content) {
        null, JSONObject.NULL -> ""
        is JSONArray -> (0 until content.length()).joinToString(" ") { index ->
            val part = content.optJSONObject(index) ?: return@joinToString ""
            when (part.optString("type")) {
                "text" -> part.optString("text")
                "image", "image_url" -> getString(R.string.image)
                else -> ""
            }
        }
        else -> content.toString()
    }

    private fun sourceLabel(source: String): String = when (source) {
        "auto_reply" -> getString(R.string.source_auto)
        "manual" -> getString(R.string.source_manual)
        else -> source
    }

    private fun timeLabel(millis: Long): String =
        if (millis <= 0) getString(R.string.unknown_time) else timeFormat.format(Date(millis))

    private fun openDetail(item: JSONObject) {
        startActivity(
            Intent(this, ChatActivity::class.java)
                .putExtra(ChatActivity.EXTRA_TASK, item.toString())
                .putExtra(ChatActivity.EXTRA_READ_ONLY, true)
        )
    }

    private fun confirmDelete(item: JSONObject) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_title)
            .setMessage(R.string.delete_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> deleteItem(item.optString("id")) }
            .show()
    }

    private fun deleteItem(id: String) {
        if (id.isEmpty()) return
        val request = Request.Builder()
            .url("${httpBase()}/v1/history/$id")
            .header("X-Device-Key", deviceKey.text.toString())
            .delete()
            .build()
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { historyStatus.text = getString(R.string.history_delete_failed, e.message ?: "") }
            }

            override fun onResponse(call: Call, response: Response) {
                runOnUiThread {
                    if (response.isSuccessful) loadHistory() else historyStatus.text = getString(R.string.history_delete_failed, "HTTP ${response.code}")
                }
            }
        })
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle(R.string.clear_title)
            .setMessage(R.string.clear_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.clear_all) { _, _ -> clearAll() }
            .show()
    }

    private fun clearAll() {
        val request = Request.Builder()
            .url("${httpBase()}/v1/history")
            .header("X-Device-Key", deviceKey.text.toString())
            .delete()
            .build()
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { historyStatus.text = getString(R.string.history_clear_failed, e.message ?: "") }
            }

            override fun onResponse(call: Call, response: Response) {
                runOnUiThread {
                    if (response.isSuccessful) loadHistory() else historyStatus.text = getString(R.string.history_clear_failed, "HTTP ${response.code}")
                }
            }
        })
    }

    private fun handleOpenTaskIntent(intent: Intent?) {
        val taskId = intent?.getStringExtra(EXTRA_OPEN_TASK_ID) ?: return
        intent.removeExtra(EXTRA_OPEN_TASK_ID)
        pendingOpenTaskId = taskId
        openPendingTaskIfReady()
    }

    private fun openPendingTaskIfReady() {
        val taskId = pendingOpenTaskId ?: return
        val task = tasks[taskId] ?: return
        pendingOpenTaskId = null
        startActivity(
            Intent(this, ChatActivity::class.java)
                .putExtra(ChatActivity.EXTRA_TASK_ID, taskId)
                .putExtra(ChatActivity.EXTRA_TASK, task.toString())
        )
    }

    private fun setupNotifications() {
        Notifications.ensureChannel(this)
        notifyNewTaskSwitch.isChecked = preferences.getBoolean(KEY_NOTIFY_NEW_TASK, true)
        notifyDisconnectSwitch.isChecked = preferences.getBoolean(KEY_NOTIFY_DISCONNECT, true)
        notifyNewTaskSwitch.setOnCheckedChangeListener { _, checked ->
            preferences.edit().putBoolean(KEY_NOTIFY_NEW_TASK, checked).apply()
        }
        notifyDisconnectSwitch.setOnCheckedChangeListener { _, checked ->
            preferences.edit().putBoolean(KEY_NOTIFY_DISCONNECT, checked).apply()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun notifyNewTask(task: JSONObject) {
        if (!preferences.getBoolean(KEY_NOTIFY_NEW_TASK, true)) return
        val taskId = task.optString("task_id")
        if (taskId.isEmpty()) return
        Notifications.notifyNewTask(this, taskId, taskSummary(task))
    }

    override fun onDestroy() {
        httpClient.dispatcher.executorService.shutdown()
        super.onDestroy()
    }
}