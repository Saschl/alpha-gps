package com.sasch.cameragps.sharednew.whatsnew

import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.whats_new_162_notifications
import cameragps.sharednew.generated.resources.whats_new_162_pairing
import cameragps.sharednew.generated.resources.whats_new_162_time
import org.jetbrains.compose.resources.StringResource

enum class ReleasePlatform { Android, Ios }

data class ReleaseNotes(val version: String, val highlights: List<StringResource>)

/** Add an entry and localized strings when shipping a release with user-facing changes. */
object ReleaseNotesCatalog {
    fun forVersion(version: String, platform: ReleasePlatform): ReleaseNotes? =
        when (version.removePrefix("v")) {
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
