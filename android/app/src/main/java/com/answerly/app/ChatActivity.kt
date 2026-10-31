package com.answerly.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import org.json.JSONArray
import org.json.JSONObject

class ChatActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_TASK = "task"
        const val EXTRA_CONTENT = "content"
        const val EXTRA_READ_ONLY = "read_only"
    }

    private lateinit var task: JSONObject
    private lateinit var answer: EditText
    private lateinit var chatContainer: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var imagePreviewContainer: LinearLayout
    private val selectedImages = mutableListOf<JSONObject>()
    private val selectedImageUris = mutableListOf<Uri>()
    private val imagePicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> addSelectedImages(uris) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Monet.apply(this)
        setContentView(R.layout.activity_chat)
        task = JSONObject(intent.getStringExtra(EXTRA_TASK) ?: "{}")
        answer = findViewById(R.id.answerInput)
        chatContainer = findViewById(R.id.chatContainer)
        chatScroll = findViewById(R.id.chatScroll)
        imagePreviewContainer = findViewById(R.id.imagePreviewContainer)

        findViewById<MaterialToolbar>(R.id.chatToolbar).setNavigationOnClickListener {
            finish()
        }
        findViewById<MaterialButton>(R.id.rawButton).setOnClickListener { showRawTask() }
        findViewById<MaterialButton>(R.id.addImageButton).setOnClickListener {
            imagePicker.launch(arrayOf("image/jpeg", "image/png", "image/webp"))
        }
        findViewById<MaterialButton>(R.id.clearImagesButton).setOnClickListener {
            selectedImages.clear()
            selectedImageUris.clear()
            renderImagePreviews()
        }
        findViewById<MaterialButton>(R.id.sendButton).setOnClickListener { submitAnswer() }

        if (intent.getBooleanExtra(EXTRA_READ_ONLY, false)) {
            findViewById<ViewGroup>(R.id.answerSection).visibility = ViewGroup.GONE
        }
        renderChat()
    }

    private fun renderChat() {
        chatContainer.removeAllViews()
        val messages = task.optJSONArray("messages")
        if (messages != null) {
            ChatBubbles.renderMessages(this, chatContainer, messages)
        }
        val response = task.opt("response")
        if (response != null && response != JSONObject.NULL) {
            ChatBubbles.addResponse(this, chatContainer, response)
        }
        chatScroll.post { chatScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun showRawTask() {
        val raw = android.widget.TextView(this).apply {
            text = task.toString(2)
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(24, 16, 24, 16)
        }
        AlertDialog.Builder(this)
            .setTitle("原始数据")
            .setView(ScrollView(this).apply { addView(raw) })
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun submitAnswer() {
        val text = answer.text.toString().trim()
        if (text.isEmpty() && selectedImages.isEmpty()) return
        val content = if (selectedImages.isEmpty()) {
            text
        } else {
            JSONArray().apply {
                if (text.isNotEmpty()) put(JSONObject().put("type", "text").put("text", text))
                selectedImages.forEach { put(it) }
            }.toString()
        }
        setResult(
            RESULT_OK,
            Intent()
                .putExtra(EXTRA_TASK_ID, intent.getStringExtra(EXTRA_TASK_ID))
                .putExtra(EXTRA_CONTENT, content)
        )
        finish()
    }

    private fun addSelectedImages(uris: List<Uri>) {
        val available = (5 - selectedImages.size).coerceAtLeast(0)
        uris.take(available).forEach { uri ->
            Thread {
                try {
                    val mimeType = contentResolver.getType(uri) ?: return@Thread
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@Thread
                    if (bytes.size > 10 * 1024 * 1024) return@Thread
                    val image = JSONObject()
                        .put("type", "image")
                        .put("mime_type", mimeType)
                        .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    runOnUiThread {
                        selectedImages.add(image)
                        selectedImageUris.add(uri)
                        renderImagePreviews()
                    }
                } catch (_: Exception) {
                }
            }.start()
        }
    }

    private fun renderImagePreviews() {
        imagePreviewContainer.removeAllViews()
        selectedImageUris.forEach { uri ->
            val imageView = ImageView(this).apply {
                setImageURI(uri)
                adjustViewBounds = true
                layoutParams = LinearLayout.LayoutParams(140, 140).apply {
                    setMargins(4, 4, 4, 4)
                }
            }
            imagePreviewContainer.addView(imageView)
        }
    }
}


