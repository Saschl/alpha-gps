package com.saschl.cameragps.service

import com.diamondedge.logging.FixedLogLevel
import com.diamondedge.logging.LogLevelController
import com.diamondedge.logging.Logger
import timber.log.Timber

/** Shared logs use the same database filtering and crash-reporting trees as Android logs. */
internal class TimberLogger : Logger, LogLevelController by FixedLogLevel(true) {
    override fun verbose(tag: String, msg: String) = Timber.tag(tag).v(msg)
    override fun debug(tag: String, msg: String) = Timber.tag(tag).d(msg)
    override fun info(tag: String, msg: String) = Timber.tag(tag).i(msg)
    override fun warn(tag: String, msg: String, t: Throwable?) = Timber.tag(tag).w(t, msg)
    override fun error(tag: String, msg: String, t: Throwable?) = Timber.tag(tag).e(t, msg)
}
