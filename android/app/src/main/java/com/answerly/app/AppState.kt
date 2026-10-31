package com.answerly.app

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Tracks whether any activity of the app is currently visible.
 *
 * Notifications are only posted while the app is in the background, so the
 * user is not interrupted by a banner for a question they can already see.
 */
object AppState : Application.ActivityLifecycleCallbacks {
    @Volatile
    var isForeground: Boolean = false
        private set

    private var startedActivities = 0

    fun register(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        startedActivities++
        isForeground = true
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
        isForeground = startedActivities > 0
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
