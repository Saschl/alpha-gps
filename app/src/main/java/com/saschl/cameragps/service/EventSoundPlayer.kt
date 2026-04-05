package com.saschl.cameragps.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.SystemClock
import androidx.core.net.toUri
import com.saschl.cameragps.utils.PreferencesManager
import timber.log.Timber

enum class TransmissionSoundEvent {
    LOCATION_ACQUIRED,
    LOCATION_INVALID,
    CAMERA_CONNECTED,
    CAMERA_DISCONNECTED,
}

enum class TransmissionSoundMode {
    DEFAULT,
    SILENT,
    CUSTOM,
}

internal class EventSoundPlayer(
    private val context: Context,
) {
    private val toneGenerator = runCatching {
        ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
    }.onFailure { error ->
        Timber.e(error, "Failed to initialize tone generator")
    }.getOrNull()

    private val lastPlayedAtMs = mutableMapOf<TransmissionSoundEvent, Long>()
    private var activeRingtone: Ringtone? = null

    fun play(event: TransmissionSoundEvent) {
        if (!PreferencesManager.isTransmissionEventSoundsEnabled(context)) return
        if (!PreferencesManager.isTransmissionEventEnabled(context, event)) return

        val now = SystemClock.elapsedRealtime()
        val lastPlayed = lastPlayedAtMs[event] ?: 0L
        if (now - lastPlayed < cooldownMs(event)) return

        val started = when (PreferencesManager.getTransmissionEventSoundMode(context, event)) {
            TransmissionSoundMode.SILENT -> false
            TransmissionSoundMode.CUSTOM -> playCustomSound(event) || playFallbackTone(event)
            TransmissionSoundMode.DEFAULT -> playFallbackTone(event)
        }

        if (started) {
            lastPlayedAtMs[event] = now
        }
    }

    fun release() {
        activeRingtone?.stop()
        activeRingtone = null
        toneGenerator?.release()
    }

    private fun playCustomSound(event: TransmissionSoundEvent): Boolean {
        val uriString =
            PreferencesManager.getTransmissionEventSoundUri(context, event) ?: return false
        val uri = runCatching { uriString.toUri() }.getOrNull() ?: return false

        return runCatching {
            activeRingtone?.stop()
            val ringtone = RingtoneManager.getRingtone(context, uri) ?: return false
            ringtone.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            ringtone.play()
            activeRingtone = ringtone
            true
        }.onFailure { error ->
            Timber.w(error, "Failed to play custom ringtone for event $event")
        }.getOrDefault(false)
    }

    private fun playFallbackTone(event: TransmissionSoundEvent): Boolean {
        return toneGenerator?.startTone(toneFor(event), durationMs(event)) ?: false
    }

    private fun toneFor(event: TransmissionSoundEvent): Int = when (event) {
        TransmissionSoundEvent.LOCATION_ACQUIRED -> ToneGenerator.TONE_PROP_ACK
        TransmissionSoundEvent.LOCATION_INVALID -> ToneGenerator.TONE_PROP_NACK
        TransmissionSoundEvent.CAMERA_CONNECTED -> ToneGenerator.TONE_PROP_BEEP
        TransmissionSoundEvent.CAMERA_DISCONNECTED -> ToneGenerator.TONE_PROP_BEEP2
    }

    private fun durationMs(event: TransmissionSoundEvent): Int = when (event) {
        TransmissionSoundEvent.LOCATION_ACQUIRED -> 120
        TransmissionSoundEvent.LOCATION_INVALID -> 160
        TransmissionSoundEvent.CAMERA_CONNECTED -> 100
        TransmissionSoundEvent.CAMERA_DISCONNECTED -> 180
    }

    private fun cooldownMs(event: TransmissionSoundEvent): Long = when (event) {
        TransmissionSoundEvent.LOCATION_ACQUIRED -> 1_500L
        TransmissionSoundEvent.LOCATION_INVALID -> 30_000L
        TransmissionSoundEvent.CAMERA_CONNECTED -> 1_500L
        TransmissionSoundEvent.CAMERA_DISCONNECTED -> 3_000L
    }
}




