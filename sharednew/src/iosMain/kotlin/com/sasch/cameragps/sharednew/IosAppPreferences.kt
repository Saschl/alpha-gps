package com.sasch.cameragps.sharednew

import platform.Foundation.NSUserDefaults

internal object IosAppPreferences {
    private const val keyShowWelcome = "ios.showWelcome"
    private const val keyAppEnabled = "ios.appEnabled"
    private const val keyAutoScanEnabled = "ios.autoScanEnabled"

    private val defaults: NSUserDefaults
        get() = NSUserDefaults.standardUserDefaults

    fun showWelcomeOnLaunch(): Boolean = defaults.objectForKey(keyShowWelcome)?.let {
        defaults.boolForKey(keyShowWelcome)
    } ?: true

    fun setShowWelcomeOnLaunch(show: Boolean) {
        defaults.setBool(show, forKey = keyShowWelcome)
    }

    fun isAppEnabled(): Boolean = defaults.objectForKey(keyAppEnabled)?.let {
        defaults.boolForKey(keyAppEnabled)
    } ?: true

    fun setAppEnabled(enabled: Boolean) {
        defaults.setBool(enabled, forKey = keyAppEnabled)
    }

    fun isAutoScanEnabled(): Boolean = defaults.objectForKey(keyAutoScanEnabled)?.let {
        defaults.boolForKey(keyAutoScanEnabled)
    } ?: true

    fun setAutoScanEnabled(enabled: Boolean) {
        defaults.setBool(enabled, forKey = keyAutoScanEnabled)
    }
}

