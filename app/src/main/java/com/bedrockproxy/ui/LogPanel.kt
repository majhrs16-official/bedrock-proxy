package com.bedrockproxy.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bedrockproxy.ui.theme.LogColors
import com.bedrockproxy.util.AppLog
import kotlinx.coroutines.flow.collect
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val logFont = FontFamily.Monospace
private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

@Composable
fun LogPanel(
    entries: List<AppLog.Entry>,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    var autoScroll by remember { mutableStateOf(true) }

    // Desactiva el auto-scroll si el usuario sube manualmente y lo reactiva al llegar abajo.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) autoScroll = !listState.canScrollForward
        }
    }

    LaunchedEffect(entries.lastOrNull()?.id) {
        if (autoScroll && entries.isNotEmpty()) {
            listState.scrollToItem(entries.lastIndex)
        }
    }

    Surface(
        modifier = modifier,
        color = LogColors.background,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
    ) {
        Column(Modifier.fillMaxSize()) {
            // ------------------------------------------------------ cabecera
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "REGISTRO",
                    fontFamily = logFont,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "(${entries.size})",
                    fontFamily = logFont,
                    fontSize = 10.sp,
                    color = LogColors.timestamp
                )

                Spacer(Modifier.weight(1f))

                TextButton(
                    onClick = { clipboard.setText(AnnotatedString(AppLog.dump())) },
                    enabled = entries.isNotEmpty()
                ) {
                    Text("Copiar", fontSize = 11.sp)
                }
                TextButton(onClick = onClear, enabled = entries.isNotEmpty()) {
                    Text("Limpiar", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

            // --------------------------------------------------------- cuerpo
            if (entries.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Sin actividad todavía…",
                        fontFamily = logFont,
                        fontSize = 11.sp,
                        color = LogColors.timestamp
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    items(entries, key = { it.id }) { entry ->
                        LogRow(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogRow(entry: AppLog.Entry) {
    val color = when (entry.level) {
        AppLog.Level.DEBUG -> LogColors.debug
        AppLog.Level.INFO -> LogColors.info
        AppLog.Level.WARN -> LogColors.warn
        AppLog.Level.ERROR -> LogColors.error
    }

    val levelLabel = when (entry.level) {
        AppLog.Level.DEBUG -> "D"
        AppLog.Level.INFO -> "I"
        AppLog.Level.WARN -> "W"
        AppLog.Level.ERROR -> "E"
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = timeFormat.format(Date(entry.timestamp)),
            fontFamily = logFont,
            fontSize = 10.sp,
            lineHeight = 14.sp,
            color = LogColors.timestamp
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = levelLabel,
            fontFamily = logFont,
            fontSize = 10.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "${entry.tag}: ",
            fontFamily = logFont,
            fontSize = 10.sp,
            lineHeight = 14.sp,
            color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.85f)
        )
        Text(
            text = entry.message,
            modifier = Modifier.weight(1f),
            fontFamily = logFont,
            fontSize = 10.sp,
            lineHeight = 14.sp,
            color = if (entry.level == AppLog.Level.ERROR) LogColors.error else Color(0xFFCBD5E1)
        )
    }
}
