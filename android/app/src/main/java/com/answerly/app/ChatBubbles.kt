package com.answerly.app

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import org.json.JSONArray
import org.json.JSONObject

object ChatBubbles {
    private const val MAX_IMAGE_HEIGHT_DP = 240
    private const val SIDE_MARGIN_DP = 48

    fun renderMessages(context: Context, container: LinearLayout, messages: JSONArray?) {
        if (messages == null) return
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            addMessage(context, container, message.optString("role", "user"), message.opt("content"))
        }
    }

    fun addMessage(context: Context, container: LinearLayout, role: String, content: Any?) {
        val normalizedRole = role.lowercase()
        // Assistant replies sit on the right, user prompts on the left.
        val alignEnd = normalizedRole != "user"
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (alignEnd) Gravity.END else Gravity.START
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            if (alignEnd) {
                setPadding(dp(context, SIDE_MARGIN_DP), 0, 0, 0)
            } else {
                setPadding(0, 0, dp(context, SIDE_MARGIN_DP), 0)
            }
        }
        val bubble = MaterialCardView(context).apply {
            radius = dp(context, 20).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(
                when (normalizedRole) {
                    "user" -> themeColor(context, com.google.android.material.R.attr.colorPrimaryContainer)
                    "assistant" -> themeColor(context, com.google.android.material.R.attr.colorSurfaceContainerHigh)
                    else -> themeColor(context, com.google.android.material.R.attr.colorSecondaryContainer)
                }
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val horizontal = dp(context, 14)
            val vertical = dp(context, 10)
            setPadding(horizontal, vertical, horizontal, vertical + dp(context, 2))
        }
        val label = TextView(context).apply {
            text = when (normalizedRole) {
                "user" -> "用户"
                "assistant" -> "助手"
                "system" -> "系统"
                else -> normalizedRole
            }
            textSize = 11f
            setTextColor(themeColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant))
        }
        inner.addView(label)
        addContent(context, inner, content)
        bubble.addView(inner)
        row.addView(bubble)
        container.addView(row)
    }

    fun addResponse(context: Context, container: LinearLayout, content: Any?) {
        addMessage(context, container, "assistant", content)
    }

    private fun addContent(context: Context, parent: LinearLayout, content: Any?) {
        when (content) {
            is JSONArray -> {
                for (index in 0 until content.length()) {
                    val item = content.optJSONObject(index) ?: continue
                    when (item.optString("type")) {
                        "text" -> addText(context, parent, item.optString("text"))
                        "image" -> addImage(context, parent, item.optString("data"))
                        "image_url" -> addImageUrl(
                            context,
                            parent,
                            item.optJSONObject("image_url")?.optString("url", "") ?: ""
                        )
                    }
                }
            }
            is JSONObject -> addContent(context, parent, content.opt("content"))
            null, JSONObject.NULL -> addText(context, parent, "")
            else -> addText(context, parent, content.toString())
        }
    }

    private fun addText(context: Context, parent: LinearLayout, text: String) {
        if (text.isEmpty()) return
        val view = TextView(context).apply {
            this.text = text
            textSize = 15f
            setTextColor(themeColor(context, com.google.android.material.R.attr.colorOnSurface))
            setTextIsSelectable(true)
            setLineSpacing(0f, 1.15f)
        }
        parent.addView(view)
    }

    private fun addImageUrl(context: Context, parent: LinearLayout, url: String) {
        val marker = ";base64,"
        val position = url.indexOf(marker)
        if (!url.startsWith("data:") || position <= 5) {
            addText(context, parent, url)
            return
        }
        addImage(context, parent, url.substring(position + marker.length))
    }

    private fun addImage(context: Context, parent: LinearLayout, data: String) {
        try {
            val bytes = Base64.decode(data, Base64.DEFAULT)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bitmap == null) {
                addText(context, parent, "图片无法解码")
                return
            }
            val view = ImageView(context).apply {
                setImageBitmap(bitmap)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = "图片"
                maxHeight = dp(context, MAX_IMAGE_HEIGHT_DP)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(context, 6) }
            }
            parent.addView(view)
        } catch (_: IllegalArgumentException) {
            addText(context, parent, "图片编码无效")
        }
    }

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun themeColor(context: Context, attr: Int): Int {
        val value = android.util.TypedValue()
        val resolved = context.theme.resolveAttribute(attr, value, true)
        if (!resolved) return android.graphics.Color.GRAY
        return if (value.resourceId != 0) {
            androidx.core.content.ContextCompat.getColor(context, value.resourceId)
        } else {
            value.data
        }
    }
}