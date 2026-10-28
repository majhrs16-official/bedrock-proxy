package com.bedrockproxy.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.bedrockproxy.ui.theme.BedrockProxyTheme
import com.bedrockproxy.util.AppLog

class MainActivity : ComponentActivity() {

    private val viewModel: ProxyViewModel by viewModels()

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            AppLog.i("UI", "Permiso de notificaciones: ${if (granted) "concedido" else "denegado"}")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AppLog.i("App", "Bedrock Proxy abierto (Android ${Build.VERSION.SDK_INT})")
        requestNotificationPermissionIfNeeded()

        setContent {
            BedrockProxyTheme {
                val state by viewModel.uiState.collectAsState()
                val logs by AppLog.entries.collectAsState()

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        state = state,
                        logs = logs,
                        onRemoteHostChange = viewModel::onRemoteHostChange,
                        onRemotePortChange = viewModel::onRemotePortChange,
                        onLocalPortChange = viewModel::onLocalPortChange,
                        onMotdChange = viewModel::onMotdChange,
                        onLanInterfaceChange = viewModel::onLanInterfaceChange,
                        onRefreshInterfaces = viewModel::refreshInterfaces,
                        onToggle = viewModel::toggleProxy,
                        onClearLogs = AppLog::clear
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshLocalAddresses()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                AppLog.d("UI", "Solicitando permiso de notificaciones")
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                AppLog.d("UI", "Permiso de notificaciones ya concedido")
            }
        }
    }
}
