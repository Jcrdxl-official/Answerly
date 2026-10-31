package com.answerly.app

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

/**
 * Process-wide connection state shared between [RelayService] and the UI.
 *
 * The socket lives in the service so it survives the activity being destroyed
 * (for example when the user swipes the app out of the recents list).
 */
object RelayState {
    enum class Status { DISCONNECTED, CONNECTING, CONNECTED }

    sealed interface Event {
        data class TaskClaimed(val taskId: String) : Event
        data object TaskUnavailable : Event
        data object HistoryChanged : Event
    }

    val status = MutableStateFlow(Status.DISCONNECTED)
    val tasks = MutableStateFlow<List<JSONObject>>(emptyList())
    val events = MutableSharedFlow<Event>(extraBufferCapacity = 16)

    fun setStatus(value: Status) {
        status.value = value
    }

    fun setTasks(value: List<JSONObject>) {
        tasks.value = value
    }

    fun emit(event: Event) {
        events.tryEmit(event)
    }
}
