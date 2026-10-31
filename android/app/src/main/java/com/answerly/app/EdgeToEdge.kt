package com.answerly.app

import android.app.Activity
import android.content.res.Configuration
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding

/**
 * Draws content behind the system bars and pads [root] by the system bar insets,
 * so the status bar area shows the app background instead of a solid scrim.
 */
fun Activity.applyEdgeToEdge(root: View) {
    WindowCompat.setDecorFitsSystemWindows(window, false)

    val nightMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    WindowInsetsControllerCompat(window, root).apply {
        isAppearanceLightStatusBars = !nightMode
        isAppearanceLightNavigationBars = !nightMode
    }

    ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        view.updatePadding(
            left = bars.left,
            top = bars.top,
            right = bars.right,
            bottom = bars.bottom
        )
        insets
    }
    ViewCompat.requestApplyInsets(root)
}