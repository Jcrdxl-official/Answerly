package com.answerly.app

import android.app.Application

class AnswerlyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppState.register(this)
        Notifications.ensureChannel(this)
    }
}