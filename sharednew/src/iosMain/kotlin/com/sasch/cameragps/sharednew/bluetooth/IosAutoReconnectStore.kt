package com.sasch.cameragps.sharednew.bluetooth

import platform.Foundation.NSUserDefaults

/**
 * Persists auto-reconnect peripheral identifiers in NSUserDefaults.
 *
 * Must be called from the main thread only (matches the CoreBluetooth threading contract).
 */
internal class IosAutoReconnectStore(
    private val userDefaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) {
    private val ids = mutableSetOf<String>()

    private companion object {
        const val PERSISTED_PERIPHERALS_KEY = "com.saschl.cameragps.persistedPeripherals"
    }

    /** Add a peripheral to the auto-reconnect set and persist. */
    fun add(id: String) {
        ids.add(id)
        flush()
    }

    /** Remove a peripheral from the auto-reconnect set and persist. */
    fun remove(id: String) {
        ids.remove(id)
        flush()
    }

    /** Returns `true` if [id] is in the auto-reconnect set. */
    fun contains(id: String): Boolean = id in ids

    /** Returns a snapshot of all persisted IDs. */
    fun getAll(): Set<String> = ids.toSet()

    /** Load persisted IDs from disk into the in-memory set. */
    fun loadFromDisk() {
        val raw = userDefaults.stringForKey(PERSISTED_PERIPHERALS_KEY) ?: return
        ids.addAll(raw.split(",").filter { it.isNotBlank() })
    }

    private fun flush() {
        userDefaults.setObject(ids.joinToString(","), forKey = PERSISTED_PERIPHERALS_KEY)
        userDefaults.synchronize()
    }
}

