package com.bedrockproxy.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.bedrockproxy.R
import com.bedrockproxy.core.BedrockProxy
import com.bedrockproxy.core.ProxyConfig
import com.bedrockproxy.core.ProxyState
import com.bedrockproxy.ui.MainActivity
import com.bedrockproxy.util.AppLog

/**
 * Servicio en primer plano que mantiene el proxy activo aunque la app
 * esté en segundo plano o la pantalla apagada.
 */
class ProxyForegroundService : Service() {

    companion object {
        private const val TAG = "ProxyService"
        const val CHANNEL_ID = "bedrock_proxy_channel"
        const val NOTIFICATION_ID = 1

        const val ACTION_START = "com.bedrockproxy.START"
        const val ACTION_STOP = "com.bedrockproxy.STOP"
        const val ACTION_STATE_CHANGED = "com.bedrockproxy.STATE_CHANGED"

        const val EXTRA_CONFIG = "config"
        const val EXTRA_STATE_KIND = "state_kind"
        const val EXTRA_LOCAL_PORT = "state_local_port"
        const val EXTRA_CLIENT_COUNT = "state_client_count"
        const val EXTRA_ERROR = "state_error"

        /** Último estado conocido; permite a la UI resincronizarse al recrearse. */
        @Volatile
        var lastState: ProxyState = ProxyState.Stopped

        fun startService(context: Context, config: ProxyConfig) {
            val intent = Intent(context, ProxyForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_CONFIG, config)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, ProxyForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private var proxy: BedrockProxy? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        AppLog.d(TAG, "Servicio creado")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                AppLog.i(TAG, "Acción START recibida")
                val config = extractConfig(intent)
                if (config != null) {
                    startProxy(config)
                } else {
                    AppLog.e(TAG, "START sin configuración válida")
                    stopSelf()
                }
            }

            ACTION_STOP -> {
                AppLog.i(TAG, "Acción STOP recibida (notificación o UI)")
                stopProxy()
                stopSelf()
            }

            else -> {
                // Reinicio del sistema sin configuración: no quedar en primer plano.
                AppLog.w(TAG, "Servicio arrancado sin acción; se detiene")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun extractConfig(intent: Intent): ProxyConfig? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_CONFIG, ProxyConfig::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_CONFIG)
        }

    private fun startProxy(config: ProxyConfig) {
        if (proxy != null) {
            AppLog.w(TAG, "Ya hay un proxy activo; se ignora el nuevo arranque")
            return
        }

        AppLog.i(
            TAG,
            "Arrancando proxy local ${config.localPort} → " +
                "${config.remoteHost}:${config.remotePort}"
        )
        acquireLocks()

        val started = BedrockProxy(applicationContext, config) { state ->
            AppLog.d(TAG, "Estado: $state")
            lastState = state
            publishNotification(state)
            broadcastState(state)
        }.also { proxy = it }.start()

        if (!started) {
            // start() ya emitió ProxyState.Error y publicó la notificación.
            AppLog.e(TAG, "El proxy no pudo arrancar")
            proxy = null
            releaseLocks()
            stopForegroundCompat()
            stopSelf()
        } else {
            AppLog.i(TAG, "Proxy en ejecución")
        }
    }

    private fun stopProxy() {
        AppLog.i(TAG, "Deteniendo proxy…")
        proxy?.stop()
        proxy = null
        releaseLocks()

        stopForegroundCompat()
        lastState = ProxyState.Stopped
        broadcastState(ProxyState.Stopped)
        AppLog.d(TAG, "Proxy detenido y servicio finalizado")
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    // ------------------------------------------------------------- notificación

    private fun publishNotification(state: ProxyState) {
        AppLog.d(TAG, "Actualizando notificación")
        val notification = buildNotification(state)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun broadcastState(state: ProxyState) {
        val intent = Intent(ACTION_STATE_CHANGED).apply {
            setPackage(packageName)
            when (state) {
                is ProxyState.Running -> {
                    putExtra(EXTRA_STATE_KIND, ProxyState.KIND_RUNNING)
                    putExtra(EXTRA_LOCAL_PORT, state.localPort)
                    putExtra(EXTRA_CLIENT_COUNT, state.clientCount)
                }

                ProxyState.Stopped -> {
                    putExtra(EXTRA_STATE_KIND, ProxyState.KIND_STOPPED)
                }

                is ProxyState.Error -> {
                    putExtra(EXTRA_STATE_KIND, ProxyState.KIND_ERROR)
                    putExtra(EXTRA_ERROR, state.message)
                }
            }
        }
        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                enableVibration(false)
                setSound(null, null)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            AppLog.d(TAG, "Canal de notificaciones creado")
        }
    }

    private fun buildNotification(state: ProxyState): Notification {
        val text = when (state) {
            is ProxyState.Running -> getString(
                R.string.notification_running, state.localPort, state.clientCount
            )

            ProxyState.Stopped -> getString(R.string.notification_stopped)
            is ProxyState.Error -> getString(R.string.notification_error, state.message)
        }

        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ProxyForegroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val color = when (state) {
            is ProxyState.Running -> 0xFF4CAF50.toInt()
            is ProxyState.Error -> 0xFFF44336.toInt()
            else -> 0xFF9E9E9E.toInt()
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(color)
            .setContentIntent(contentIntent)
            .setOngoing(state is ProxyState.Running)
            .setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_stop, getString(R.string.action_stop), stopIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    // ------------------------------------------------------------------- locks

    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BedrockProxy::proxy").apply {
                setReferenceCounted(false)
                acquire()
            }
            AppLog.d(TAG, "WakeLock adquirido")
        } catch (e: Exception) {
            AppLog.w(TAG, "No se pudo adquirir WakeLock (${e.javaClass.simpleName})")
        }

        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("BedrockProxy::multicast")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            AppLog.d(TAG, "MulticastLock adquirido (descubrimiento LAN)")
        } catch (e: Exception) {
            AppLog.w(TAG, "No se pudo adquirir MulticastLock (${e.javaClass.simpleName})")
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null

        try {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        } catch (_: Exception) {
        }
        multicastLock = null
        AppLog.d(TAG, "Locks liberados")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        AppLog.d(TAG, "Servicio destruido")
        proxy?.stop()
        proxy = null
        lastState = ProxyState.Stopped
        releaseLocks()
        super.onDestroy()
    }
}
