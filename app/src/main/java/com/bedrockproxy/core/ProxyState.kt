package com.bedrockproxy.core

sealed interface ProxyState {
    data class Running(val localPort: Int, val clientCount: Int) : ProxyState
    data object Stopped : ProxyState
    data class Error(val message: String) : ProxyState

    companion object {
        // Serialización simple para broadcasts (evita Parcelable complejo)
        const val KIND_RUNNING = "running"
        const val KIND_STOPPED = "stopped"
        const val KIND_ERROR = "error"
    }
}