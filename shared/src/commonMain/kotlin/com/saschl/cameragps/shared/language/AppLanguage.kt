package com.saschl.cameragps.shared.language

data class AppLanguage(
    val tag: String,
    val displayName: String,
)

object SupportedLanguages {
    val entries: List<AppLanguage> = listOf(
        AppLanguage(tag = "en", displayName = "English"),
        AppLanguage(tag = "de", displayName = "Deutsch"),
        AppLanguage(tag = "zh-Hans", displayName = "中文"),
        AppLanguage(tag = "es", displayName = "Español"),
        AppLanguage(tag = "ja", displayName = "日本語"),
    )

    fun fromTag(tag: String): AppLanguage? {
        val normalized = tag.substringBefore('-').lowercase()
        return entries.firstOrNull { it.tag.substringBefore('-').lowercase() == normalized }
    }
}

