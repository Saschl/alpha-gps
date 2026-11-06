package com.saschl.cameragps

import android.app.Application
import com.diamondedge.logging.FixedLogLevel
import com.diamondedge.logging.KmLogging
import com.diamondedge.logging.PlatformLogger
import com.saschl.cameragps.service.FileTree
import com.saschl.cameragps.service.GlobalExceptionHandler
import com.saschl.cameragps.service.TimberLogger
import com.saschl.cameragps.utils.CrashReporting
import com.saschl.cameragps.utils.PreferencesManager
import timber.log.Timber

/**
 * Process entry point: one-time logging/crash-reporting setup and the
 * app-scoped service graph ([AppServices]). Application.onCreate precedes every
 * other component, so the init blocks previously duplicated across the
 * activity, services and receivers live only here.
 */
class CameraGpsApplication : Application() {

    val services: AppServices by lazy { AppServices(this) }

    override fun onCreate() {
        super.onCreate()

        FileTree.initialize(this)
        Timber.plant(FileTree(this, PreferencesManager.logLevel(this)))
        // Keep Logcat/tag generation and also route shared logs into the in-app
        // database and consent-controlled Timber trees. Trees own their thresholds.
        KmLogging.setLoggers(PlatformLogger(FixedLogLevel(true)), TimberLogger())

        // Sentry must wrap this handler so closing its SDK can restore it.
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(GlobalExceptionHandler(defaultHandler))

        CrashReporting.updateFromPreferences(this)
    }
}
