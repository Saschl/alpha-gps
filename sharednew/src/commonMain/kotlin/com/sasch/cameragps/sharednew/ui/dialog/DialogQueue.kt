package com.sasch.cameragps.sharednew.ui.dialog

import androidx.compose.runtime.mutableStateListOf

/**
 * A FIFO queue observed by Compose. Use stable dialog keys (for example, an enum).
 * Enqueuing an existing key leaves its position unchanged. Call from the UI thread.
 */
class DialogQueue<T : Any> {
    private val dialogs = mutableStateListOf<T>()

    val activeDialog: T? get() = dialogs.firstOrNull()
    val isEmpty: Boolean get() = dialogs.isEmpty()

    operator fun contains(dialog: T): Boolean = dialog in dialogs

    fun enqueue(dialog: T) {
        if (dialog !in dialogs) dialogs.add(dialog)
    }

    /** Removes an active or waiting dialog; removing the head reveals the next one. */
    fun remove(dialog: T) {
        dialogs.remove(dialog)
    }

    /** Cancels requests whose underlying condition is no longer valid. */
    fun removeAll(predicate: (T) -> Boolean) {
        dialogs.removeAll(predicate)
    }

    /** A callback for an old dialog must not close the next dialog. */
    fun dismiss(dialog: T) {
        if (activeDialog == dialog) dialogs.removeAt(0)
    }
}
