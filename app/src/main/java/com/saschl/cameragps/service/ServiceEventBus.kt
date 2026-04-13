package com.saschl.cameragps.service

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Unidirectional event bus. Coordinators call [emit] from any thread (including BLE callback threads).
 * The service collects [events] on its lifecycle scope.
 *
 * Uses a Channel (not SharedFlow) so events are never dropped and back-pressure is handled via buffering.
 */
class ServiceEventBus {
    private val channel = Channel<ServiceEvent>(Channel.UNLIMITED)

    val events: Flow<ServiceEvent> = channel.receiveAsFlow()

    fun emit(event: ServiceEvent) {
        channel.trySend(event)
    }
}

