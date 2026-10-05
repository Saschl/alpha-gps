package com.sasch.cameragps.sharednew.remote.wifi

import com.diamondedge.logging.FixedLogLevel
import com.diamondedge.logging.KmLogging
import com.diamondedge.logging.PlatformLogger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest

/** Protocol tests also run on a JVM without Android's Log implementation. */
abstract class WifiLoggingTest {
    @BeforeTest fun silencePlatformLogs() { KmLogging.setLoggers() }
    @AfterTest fun restorePlatformLogs() { KmLogging.setLoggers(PlatformLogger(FixedLogLevel(true))) }
}
