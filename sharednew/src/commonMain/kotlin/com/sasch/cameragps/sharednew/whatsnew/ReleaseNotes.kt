package com.sasch.cameragps.sharednew.whatsnew

import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.whats_new_162_notifications
import cameragps.sharednew.generated.resources.whats_new_162_pairing
import cameragps.sharednew.generated.resources.whats_new_162_time
import cameragps.sharednew.generated.resources.whats_new_170_wifi_remote
import cameragps.sharednew.generated.resources.whats_new_171_rx0
import cameragps.sharednew.generated.resources.whats_new_171_zv1
import cameragps.sharednew.generated.resources.whats_new_172_layout
import cameragps.sharednew.generated.resources.whats_new_172_optimisations
import cameragps.sharednew.generated.resources.whats_new_172_troubleshooting
import cameragps.sharednew.generated.resources.whats_new_173_bulb_timer
import cameragps.sharednew.generated.resources.whats_new_173_download_confirmation
import cameragps.sharednew.generated.resources.whats_new_173_photo_browsing
import cameragps.sharednew.generated.resources.whats_new_173_photo_dates
import org.jetbrains.compose.resources.StringResource

enum class ReleasePlatform { Android, Ios }

data class ReleaseNotes(val version: String, val highlights: List<StringResource>)

/** Add an entry and localized strings when shipping a release with user-facing changes. */
object ReleaseNotesCatalog {
    fun forVersion(version: String, platform: ReleasePlatform): ReleaseNotes? =
        when (version.removePrefix("v")) {
            "1.7.4" -> ReleaseNotes(
                version = "1.7.4",
                highlights = listOf(
                    Res.string.whats_new_173_photo_dates,
                    Res.string.whats_new_173_photo_browsing,
                    Res.string.whats_new_173_bulb_timer,
                    Res.string.whats_new_173_download_confirmation,
                ),
            )

            "1.7.2" -> ReleaseNotes(
                version = "1.7.2",
                highlights = listOf(
                    Res.string.whats_new_172_troubleshooting,
                    Res.string.whats_new_172_layout,
                    Res.string.whats_new_172_optimisations,
                ),
            )

            "1.7.1" -> ReleaseNotes(
                version = "1.7.1",
                highlights = buildList {
                    add(Res.string.whats_new_171_zv1)
                    if (platform == ReleasePlatform.Android) add(Res.string.whats_new_171_rx0)
                },
            )

            "1.7.0" -> ReleaseNotes(
                version = "1.7.0",
                highlights = listOf(Res.string.whats_new_170_wifi_remote),
            )

            "1.6.2" -> ReleaseNotes(
                version = "1.6.2",
                highlights = buildList {
                    add(Res.string.whats_new_162_time)
                    if (platform == ReleasePlatform.Android) add(Res.string.whats_new_162_notifications)
                    if (platform == ReleasePlatform.Ios) add(Res.string.whats_new_162_pairing)
                },
            )

            else -> null
        }
}

object WhatsNewPolicy {
    // Numeric comparison prevents a downgrade (or a build of the same release)
    // from showing notes again. Both apps use numeric marketing versions.
    fun isNewer(current: String, previous: String?): Boolean {
        fun parts(value: String): List<Int>? = value.removePrefix("v").split('.').map {
            it.toIntOrNull()?.takeIf { part -> part >= 0 } ?: return null
        }

        val currentParts = parts(current) ?: return false
        val previousParts = previous?.let(::parts) ?: return true
        for (index in 0 until maxOf(currentParts.size, previousParts.size)) {
            val comparison = (currentParts.getOrNull(index) ?: 0)
                .compareTo(previousParts.getOrNull(index) ?: 0)
            if (comparison != 0) return comparison > 0
        }
        return false
    }

    fun shouldShow(
        current: String,
        previous: String?,
        firstLaunch: Boolean,
        hasNotes: Boolean
    ): Boolean =
        !firstLaunch && hasNotes && isNewer(current, previous)
}
