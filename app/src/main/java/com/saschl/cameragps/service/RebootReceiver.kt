package com.saschl.cameragps.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.saschl.cameragps.utils.PreferencesManager
import com.saschl.cameragps.utils.SentryInit
import timber.log.Timber

class RebootReceiver : BroadcastReceiver() {


    override fun onReceive(context: Context, intent: Intent) {
        val serviceIntent = Intent(context, LocationSenderService::class.java)

        if (PreferencesManager.sentryEnabled(context)) {
            SentryInit.initSentry(context)
        }

        if (Timber.forest().find { it is FileTree } == null) {
            val logLevel = PreferencesManager.logLevel(context)
            FileTree.initialize(context)
            Timber.plant(FileTree(context, logLevel))

            // Set up global exception handler to log crashes
            val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler(GlobalExceptionHandler(defaultHandler))
        }

        Timber.i(
            "RebootReceiver received intent: ${intent.action} with preference ${
                PreferencesManager.getAutoStartAfterBootEnabled(context)
            }"
        )
        if(Intent.ACTION_MY_PACKAGE_REPLACED == intent.action) {
            ContextCompat.startForegroundService(context, serviceIntent)
        }

        if (Intent.ACTION_BOOT_COMPLETED == intent.action && PreferencesManager.getAutoStartAfterBootEnabled(
                context
            )
        ) {
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }
}