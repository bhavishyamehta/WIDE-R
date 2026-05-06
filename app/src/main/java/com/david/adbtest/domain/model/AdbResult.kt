package com.david.adbtest.domain.model

sealed class AdbState {
    data object Idle : AdbState()
    data class Loading(val message: String) : AdbState()
    data class Success(val output: String) : AdbState()
    data class Error(val message: String) : AdbState()
    data class NeedsPairing(val message: String = "First time setup required") : AdbState()
}

data class PairingInfo(
    val port: String = "",
    val pairingCode: String = ""
)