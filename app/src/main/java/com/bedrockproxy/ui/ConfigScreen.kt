package com.bedrockproxy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bedrockproxy.util.NetworkUtils

@Composable
fun ConfigContent(
    state: UiState,
    onRemoteHostChange: (String) -> Unit,
    onRemotePortChange: (String) -> Unit,
    onLocalPortChange: (String) -> Unit,
    onMotdChange: (String) -> Unit,
    onLanInterfaceChange: (String) -> Unit,
    onRefreshInterfaces: () -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    var interfaceMenuExpanded by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(Modifier.height(6.dp))

            SectionLabel("Servidor remoto")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = state.remoteHost,
                    onValueChange = onRemoteHostChange,
                    modifier = Modifier.weight(2f),
                    label = { Text("IP remota") },
                    placeholder = { Text("127.0.0.1") },
                    singleLine = true,
                    enabled = !state.isRunning && !state.isBusy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
                OutlinedTextField(
                    value = state.remotePort,
                    onValueChange = onRemotePortChange,
                    modifier = Modifier.weight(1.2f),
                    label = { Text("Puerto remoto") },
                    placeholder = { Text("19133") },
                    singleLine = true,
                    enabled = !state.isRunning && !state.isBusy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }

            SectionLabel("Servidor local (proxy)")
            OutlinedTextField(
                value = state.localPort,
                onValueChange = onLocalPortChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Puerto local") },
                placeholder = { Text("19132") },
                supportingText = { Text("Usa 19132 para aparecer en LAN automáticamente") },
                singleLine = true,
                enabled = !state.isRunning && !state.isBusy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            SectionLabel("Interfaz LAN")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    OutlinedButton(
                        onClick = {
                            onRefreshInterfaces()
                            interfaceMenuExpanded = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.isRunning && !state.isBusy
                    ) {
                        Text(
                            text = interfaceLabel(state),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    DropdownMenu(
                        expanded = interfaceMenuExpanded,
                        onDismissRequest = { interfaceMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Automática (todas)") },
                            onClick = {
                                onLanInterfaceChange("")
                                interfaceMenuExpanded = false
                            }
                        )
                        state.interfaces.forEach { iface ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "${NetworkUtils.friendlyName(iface.name)} " +
                                            "(${iface.name}) · ${iface.address}"
                                    )
                                },
                                onClick = {
                                    onLanInterfaceChange(iface.name)
                                    interfaceMenuExpanded = false
                                }
                            )
                        }
                    }
                }
                TextButton(
                    onClick = onRefreshInterfaces,
                    enabled = !state.isRunning && !state.isBusy
                ) {
                    Text("Actualizar")
                }
            }
            Text(
                text = "Interfaz donde se anuncia el mundo LAN. En hotspot elige la " +
                    "del hotspot. Se anuncia por multicast 224.0.2.60 y, como " +
                    "alternativa, por broadcast de la subred.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = state.motd,
                onValueChange = onMotdChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("MOTD (nombre visible)") },
                singleLine = true,
                enabled = !state.isRunning && !state.isBusy
            )

            Button(
                onClick = onToggle,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                enabled = !state.isBusy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.isRunning)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.primary,
                    contentColor = if (state.isRunning)
                        MaterialTheme.colorScheme.onError
                    else
                        MaterialTheme.colorScheme.onPrimary
                )
            ) {
                if (state.isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .height(20.dp)
                            .width(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("Procesando…", fontSize = 16.sp)
                } else {
                    Text(
                        text = if (state.isRunning) "Detener" else "Iniciar",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            StatusCard(state)

            val error = state.errorMessage
            if (error != null) {
                ErrorCard(error)
            }

            Spacer(Modifier.height(10.dp))
        }
    }
}

private fun interfaceLabel(state: UiState): String {
    val selected = state.lanInterface
    if (selected.isBlank()) return "Automática (todas)"
    val iface = state.interfaces.firstOrNull { it.name == selected }
    return if (iface != null) {
        "${NetworkUtils.friendlyName(iface.name)} (${iface.name}) · ${iface.address}"
    } else {
        "$selected (no disponible)"
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        letterSpacing = 1.5.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun StatusCard(state: UiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!state.isRunning) {
                Text(
                    text = if (state.isBusy) "Conectando…" else "Proxy detenido",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            } else {
                Text(
                    text = "Clientes conectados: ${state.clientCount}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Escuchando en el puerto ${state.localPort}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val ip = state.interfaces
                    .firstOrNull { it.name == state.lanInterface }
                    ?.address
                    ?: state.localAddresses.firstOrNull()
                if (ip != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Conéctate desde otro dispositivo a:\n$ip:${state.localPort}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(14.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer
        )
    }
}
