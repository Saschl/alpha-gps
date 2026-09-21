package com.sasch.cameragps.sharednew.bluetooth

import platform.AccessorySetupKit.ASAccessoryEvent
import platform.AccessorySetupKit.ASAccessorySession

/**
 * Native discovery customization supplied by Swift on iOS 26.1+. Keep newer
 * AccessorySetupKit classes out of Kotlin: Kotlin/Native strongly links class
 * references even behind version checks, which would break launch on iOS 18.
 * This adapter only customizes the picker; the shell still owns its lifecycle.
 */
interface IosAccessoryDiscoveryCustomizer {
    fun start()
    fun onEvent(event: ASAccessoryEvent)
    fun stop()
}

object IosAccessoryDiscoverySupport {
    private var factory: ((ASAccessorySession) -> IosAccessoryDiscoveryCustomizer?)? = null

    /** Inert registration, before the controller's normal synchronous startup. */
    fun install(factory: (ASAccessorySession) -> IosAccessoryDiscoveryCustomizer?) {
        this.factory = factory
    }

    internal fun create(session: ASAccessorySession): IosAccessoryDiscoveryCustomizer? = factory?.invoke(session)
}
