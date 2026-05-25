package com.saschl.cameragps.service

sealed interface ServiceCommand {
    data class Connect(val address: String) : ServiceCommand
    data class Shutdown(val address: String) : ServiceCommand
    data class TriggerRemoteShutter(val address: String) : ServiceCommand
    data class SetRemoteControlMonitoring(val address: String, val enabled: Boolean) :
        ServiceCommand
    data object ReconnectAlwaysOn : ServiceCommand
    data class Ignore(val reason: String) : ServiceCommand
}

