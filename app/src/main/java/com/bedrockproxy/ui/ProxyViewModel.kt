package com.bedrockproxy.ui

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bedrockproxy.core.ProxyConfig
import com.bedrockproxy.core.ProxyState
import com.bedrockproxy.service.ProxyForegroundService
import com.bedrockproxy.util.AppLog
import com.bedrockproxy.util.ConfigStore
import com.bedrockproxy.util.NetworkUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class UiState(
    val remoteHost: String = "127.0.0.1",
    val remotePort: String = "19133",
    val localPort: String = "19132",
    val motd: String = "Bedrock Proxy",
    val isRunning: Boolean = false,
    val isBusy: Boolean = false,
    val clientCount: Int = 0,
    val localAddresses: List<String> = emptyList(),
    val lanInterface: String = "",
    val interfaces: List<NetworkUtils.LocalInterface> = emptyList(),
    val errorMessage: String? = null
) {
    fun toConfigOrNull(): ProxyConfig? {
        val rp = remotePort.toIntOrNull() ?: return null
        val lp = localPort.toIntOrNull() ?: return null
        if (remoteHost.isBlank()) return null
        return ProxyConfig(
            remoteHost = remoteHost.trim(),
            remotePort = rp,
            localPort = lp,
            motd = motd.ifBlank { "Bedrock Proxy" },
            lanInterface = lanInterface
        )
    }
}

class ProxyViewModel(application: Application) : AndroidViewModel(application) {

    private val configStore = ConfigStore(application)
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ProxyForegroundService.ACTION_STATE_CHANGED) return
            when (intent.getStringExtra(ProxyForegroundService.EXTRA_STATE_KIND)) {
                ProxyState.KIND_RUNNING -> {
                    val count = intent.getIntExtra(ProxyForegroundService.EXTRA_CLIENT_COUNT, 0)
                    _uiState.value = _uiState.value.copy(
                        isRunning = true,
                        isBusy = false,
                        clientCount = count,
                        errorMessage = null
                    )
                    AppLog.d("UI", "Estado del servicio: en ejecución ($count cliente/s)")
                }
                ProxyState.KIND_STOPPED -> {
                    _uiState.value = _uiState.value.copy(
                        isRunning = false,
                        isBusy = false,
                        clientCount = 0
                    )
                    AppLog.i("UI", "Estado del servicio: detenido")
                }
                ProxyState.KIND_ERROR -> {
                    val error = intent.getStringExtra(ProxyForegroundService.EXTRA_ERROR)
                    _uiState.value = _uiState.value.copy(
                        isRunning = false,
                        isBusy = false,
                        clientCount = 0,
                        errorMessage = error
                    )
                    AppLog.e("UI", "Estado del servicio: error → $error")
                }
            }
        }
    }

    init {
        val app = getApplication<Application>()
        val filter = IntentFilter(ProxyForegroundService.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            app.registerReceiver(stateReceiver, filter)
        }

        viewModelScope.launch {
            val saved = configStore.configFlow.first()
            _uiState.value = _uiState.value.copy(
                remoteHost = saved.remoteHost,
                remotePort = saved.remotePort.toString(),
                localPort = saved.localPort.toString(),
                motd = saved.motd,
                localAddresses = NetworkUtils.localIpv4Addresses(),
                lanInterface = saved.lanInterface,
                interfaces = NetworkUtils.localInterfaces()
            )
            AppLog.i(
                "Config",
                "Configuración cargada: local ${saved.localPort} → " +
                    "${saved.remoteHost}:${saved.remotePort}, " +
                    "interfaz LAN ${saved.lanInterface.ifBlank { "automática" }}"
            )

            // Si el servicio ya estaba corriendo (la Activity se recreó), resincronizar.
            val current = ProxyForegroundService.lastState
            if (current is ProxyState.Running) {
                AppLog.i("UI", "Sincronizando con el servicio ya activo")
                _uiState.value = _uiState.value.copy(
                    isRunning = true,
                    isBusy = false,
                    clientCount = current.clientCount
                )
            }
        }
    }

    fun refreshLocalAddresses() {
        val addresses = NetworkUtils.localIpv4Addresses()
        val interfaces = NetworkUtils.localInterfaces()
        _uiState.value = _uiState.value.copy(
            localAddresses = addresses,
            interfaces = interfaces
        )
        if (addresses.isNotEmpty()) {
            AppLog.d("Net", "Direcciones locales: ${addresses.joinToString(", ")}")
        } else {
            AppLog.w("Net", "No se detectó ninguna dirección IPv4 local")
        }
        if (interfaces.isNotEmpty()) {
            AppLog.d("Net", "Interfaces: ${NetworkUtils.describeInterfaces()}")
            AppLog.d(
                "Net",
                "Ruta por defecto: ${NetworkUtils.defaultRoute() ?: "no disponible"}; " +
                    "ARP: " + NetworkUtils.arpNeighbors().joinToString()
                        .ifBlank { "sin entradas" }
            )
        }
    }

    fun refreshInterfaces() {
        _uiState.value = _uiState.value.copy(interfaces = NetworkUtils.localInterfaces())
    }

    fun onRemoteHostChange(value: String) {
        _uiState.value = _uiState.value.copy(remoteHost = value, errorMessage = null)
    }

    fun onRemotePortChange(value: String) {
        if (value.all { it.isDigit() } && value.length <= 5) {
            _uiState.value = _uiState.value.copy(remotePort = value, errorMessage = null)
        }
    }

    fun onLocalPortChange(value: String) {
        if (value.all { it.isDigit() } && value.length <= 5) {
            _uiState.value = _uiState.value.copy(localPort = value, errorMessage = null)
        }
    }

    fun onMotdChange(value: String) {
        _uiState.value = _uiState.value.copy(motd = value)
    }

    fun onLanInterfaceChange(name: String) {
        _uiState.value = _uiState.value.copy(lanInterface = name, errorMessage = null)
    }

    fun toggleProxy() {
        if (_uiState.value.isBusy) return
        if (_uiState.value.isRunning) stopProxy() else startProxy()
    }

    private fun startProxy() {
        val config = _uiState.value.toConfigOrNull()
        if (config == null) {
            AppLog.w("Config", "No se pudo iniciar: configuración incompleta")
            _uiState.value = _uiState.value.copy(errorMessage = "Configuración inválida")
            return
        }
        when (val result = config.validate()) {
            is ProxyConfig.ValidationResult.Invalid -> {
                AppLog.w("Config", "Validación fallida: ${result.errors.first()}")
                _uiState.value = _uiState.value.copy(errorMessage = result.errors.first())
                return
            }
            ProxyConfig.ValidationResult.Valid -> Unit
        }

        AppLog.i("Proxy", "Solicitud de inicio recibida")
        _uiState.value = _uiState.value.copy(isBusy = true, errorMessage = null)
        refreshLocalAddresses()

        viewModelScope.launch {
            configStore.save(config)
            AppLog.d("Config", "Configuración guardada")
            ProxyForegroundService.startService(getApplication(), config)
            AppLog.d("Service", "Intento de arranque del servicio en primer plano")
            awaitStateOrTimeout()
        }
    }

    private fun stopProxy() {
        AppLog.i("Proxy", "Solicitud de parada recibida")
        _uiState.value = _uiState.value.copy(isBusy = true)
        ProxyForegroundService.stopService(getApplication())
        viewModelScope.launch { awaitStateOrTimeout() }
    }

    /**
     * Si el servicio no emite ningún estado en unos segundos, se desbloquea la UI
     * para que el usuario pueda volver a intentarlo.
     */
    private suspend fun awaitStateOrTimeout() {
        delay(6000)
        if (_uiState.value.isBusy) {
            AppLog.w("Service", "El servicio no respondió en 6 s")
            _uiState.value = _uiState.value.copy(
                isBusy = false,
                errorMessage = _uiState.value.errorMessage
                    ?: "El servicio no respondió. Comprueba los permisos de la app."
            )
        }
    }

    override fun onCleared() {
        try {
            getApplication<Application>().unregisterReceiver(stateReceiver)
        } catch (_: Exception) {
        }
        AppLog.d("UI", "ViewModel liberado")
        super.onCleared()
    }
}
