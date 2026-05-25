package com.saschl.cameragps.service

import android.location.Location
import com.sasch.cameragps.sharednew.bluetooth.coordinator.LocationDataConfig
import com.sasch.cameragps.sharednew.bluetooth.coordinator.LocationPacketBuilder
import com.sasch.cameragps.sharednew.bluetooth.coordinator.PlatformTimeZoneInfo
import java.time.ZonedDateTime

/**
 * Thin Android wrapper around the shared [LocationPacketBuilder].
 * Extracts lat/lng from Android [Location] and delegates to the shared builder.
 */
object LocationDataConverter {

    /**
     * Builds the complete location data packet to send to the camera.
     */
    fun buildLocationDataPacket(
        locationDataConfig: LocationDataConfig,
        locationResult: Location,
    ): ByteArray {
        return LocationPacketBuilder.buildLocationDataPacket(
            locationDataConfig,
            locationResult.latitude,
            locationResult.longitude,
            PlatformTimeZoneInfo(),
        )
    }

    /**
     * For cameras that have explicit time sync.
     */
    @Suppress("UNUSED_PARAMETER")
    fun serializeTimeAreaData(zonedDateTime: ZonedDateTime): ByteArray {
        return LocationPacketBuilder.buildTimeSyncPacket(PlatformTimeZoneInfo())
    }
}

/**
 * Extension function to convert ByteArray to hex string for debugging
 */
fun ByteArray.toHex(): String = joinToString(separator = "") { eachByte -> "%02x".format(eachByte) }
