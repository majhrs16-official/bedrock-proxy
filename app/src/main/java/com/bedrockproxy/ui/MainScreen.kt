package com.bedrockproxy.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bedrockproxy.util.AppLog
import kotlinx.coroutines.launch

private enum class AppDestination(val label: String) {
    CONFIG("Configuración"),
    LOGS("Registro")
}

/**
 * Contenedor principal: menú lateral (hamburguesa) con dos pantallas.
 * La configuración es la pantalla inicial; el registro ocupa su propia
 * pestaña para liberar espacio en móviles pequeños.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    state: UiState,
    logs: List<AppLog.Entry>,
    onRemoteHostChange: (String) -> Unit,
    onRemotePortChange: (String) -> Unit,
    onLocalPortChange: (String) -> Unit,
    onMotdChange: (String) -> Unit,
    onLanInterfaceChange: (String) -> Unit,
    onRefreshInterfaces: () -> Unit,
    onToggle: () -> Unit,
    onClearLogs: () -> Unit
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var destination by rememberSaveable { mutableStateOf(AppDestination.CONFIG) }

    val statusColor = when {
        state.isRunning -> MaterialTheme.colorScheme.primary
        state.isBusy -> Color(0xFFFBBF24)
        state.errorMessage != null -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusText = when {
        state.isRunning -> "En ejecución · ${state.clientCount} cliente/s"
        state.isBusy -> "Procesando…"
        state.errorMessage != null -> "Error"
        else -> "Detenido"
    }

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }
    BackHandler(enabled = !drawerState.isOpen && destination == AppDestination.LOGS) {
        destination = AppDestination.CONFIG
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .background(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "BP",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Bedrock Proxy",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Proxy LAN · Minecraft",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(statusColor, CircleShape)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor
                        )
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 16.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                    )

                    Text(
                        text = "PANTALLAS",
                        style = MaterialTheme.typography.labelSmall,
                        letterSpacing = 1.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))

                    NavigationDrawerItem(
                        label = { Text("Configuración") },
                        selected = destination == AppDestination.CONFIG,
                        onClick = {
                            destination = AppDestination.CONFIG
                            scope.launch { drawerState.close() }
                        },
                        icon = {
                            Icon(Icons.Default.Settings, contentDescription = null)
                        },
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                    Spacer(Modifier.height(4.dp))
                    NavigationDrawerItem(
                        label = { Text("Registro") },
                        selected = destination == AppDestination.LOGS,
                        onClick = {
                            destination = AppDestination.LOGS
                            scope.launch { drawerState.close() }
                        },
                        icon = {
                            Icon(
                                Icons.AutoMirrored.Filled.List,
                                contentDescription = null
                            )
                        },
                        badge = {
                            if (logs.isNotEmpty()) {
                                Text("${logs.size}")
                            }
                        },
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )

                    Spacer(Modifier.weight(1f))

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Descubrimiento LAN",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "224.0.2.60 · puerto 19132",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(
                                Icons.Default.Menu,
                                contentDescription = "Abrir menú"
                            )
                        }
                    },
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(9.dp)
                                    .background(statusColor, CircleShape)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = destination.label,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = statusText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = statusColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            }
        ) { innerPadding ->
            when (destination) {
                AppDestination.CONFIG -> ConfigContent(
                    state = state,
                    onRemoteHostChange = onRemoteHostChange,
                    onRemotePortChange = onRemotePortChange,
                    onLocalPortChange = onLocalPortChange,
                    onMotdChange = onMotdChange,
                    onLanInterfaceChange = onLanInterfaceChange,
                    onRefreshInterfaces = onRefreshInterfaces,
                    onToggle = onToggle,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                )
                AppDestination.LOGS -> LogScreen(
                    logs = logs,
                    onClearLogs = onClearLogs,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                )
            }
        }
    }
}

@Composable
private fun LogScreen(
    logs: List<AppLog.Entry>,
    onClearLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    LogPanel(
        entries = logs,
        onClear = onClearLogs,
        modifier = modifier.padding(horizontal = 12.dp, vertical = 10.dp)
    )
}
