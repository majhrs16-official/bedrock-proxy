package com.bedrockproxy.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bedrockproxy.core.ProxyConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "proxy_config")

class ConfigStore(private val context: Context) {

    companion object {
        private val KEY_REMOTE_HOST = stringPreferencesKey("remote_host")
        private val KEY_REMOTE_PORT = intPreferencesKey("remote_port")
        private val KEY_LOCAL_PORT = intPreferencesKey("local_port")
        private val KEY_MOTD = stringPreferencesKey("motd")
        private val KEY_MAX_CONNECTIONS = intPreferencesKey("max_connections")
        private val KEY_LAN_INTERFACE = stringPreferencesKey("lan_interface")
    }

    val configFlow: Flow<ProxyConfig> = context.dataStore.data
        .catch { e ->
            if (e is IOException) {
                AppLog.w("Config", "No se pudo leer la configuración guardada")
                emit(androidx.datastore.preferences.core.emptyPreferences())
            } else {
                throw e
            }
        }
        .map { prefs ->
            ProxyConfig(
                remoteHost = prefs[KEY_REMOTE_HOST] ?: "127.0.0.1",
                remotePort = prefs[KEY_REMOTE_PORT] ?: 19133,
                localPort = prefs[KEY_LOCAL_PORT] ?: 19132,
                motd = prefs[KEY_MOTD] ?: "Bedrock Proxy",
                maxConnections = prefs[KEY_MAX_CONNECTIONS] ?: 100,
                lanInterface = prefs[KEY_LAN_INTERFACE] ?: ""
            )
        }

    suspend fun save(config: ProxyConfig) {
        context.dataStore.edit { prefs ->
            prefs[KEY_REMOTE_HOST] = config.remoteHost
            prefs[KEY_REMOTE_PORT] = config.remotePort
            prefs[KEY_LOCAL_PORT] = config.localPort
            prefs[KEY_MOTD] = config.motd
            prefs[KEY_MAX_CONNECTIONS] = config.maxConnections
            prefs[KEY_LAN_INTERFACE] = config.lanInterface
        }
    }
}