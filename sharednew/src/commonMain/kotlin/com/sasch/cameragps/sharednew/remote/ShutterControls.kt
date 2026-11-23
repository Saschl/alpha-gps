package com.sasch.cameragps.sharednew.remote

/** Shutter actions on one transport. A successful start means the request was accepted locally. */
interface ShutterControls : BulbTimerControls {
    fun capture(identifier: String): Boolean

    /** Release with [stopBulbTimer]. A held exposure uses the same time limit as the timer. */
    fun startBulbHold(identifier: String): Boolean =
        startBulbTimer(identifier, bulbDurationSeconds.last)
}
