package com.bedrockproxy.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Logger global de la aplicación.
 *
 * - Guarda las últimas [MAX_ENTRIES] entradas en memoria (buffer circular).
 * - Las expone como [StateFlow] para que la UI se actualice en tiempo real.
 * - Sigue escribiendo en logcat para depuración con adb.
 * - El código nativo (JNI) puede escribir aquí mediante [nativeLog].
 */
object AppLog {

    enum class Level { DEBUG, INFO, WARN, ERROR }

    data class Entry(
        val id: Long,
        val timestamp: Long,
        val level: Level,
        val tag: String,
        val message: String
    )

    private const val MAX_ENTRIES = 500
    private const val CATEGORY = "BedrockProxy"

    private val counter = AtomicLong(0)

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    // ------------------------------------------------------------------ API

    fun d(tag: String, message: String) = add(Level.DEBUG, tag, message)

    fun i(tag: String, message: String) = add(Level.INFO, tag, message)

    fun w(tag: String, message: String) = add(Level.WARN, tag, message)

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val text = if (throwable == null) {
            message
        } else {
            "$message (${throwable.javaClass.simpleName}: ${throwable.message})"
        }
        add(Level.ERROR, tag, text)
        if (throwable != null) {
            Log.e(CATEGORY, "$tag: $message", throwable)
        }
    }

    fun clear() {
        _entries.value = emptyList()
        d("Logger", "Registro limpiado")
    }

    /** Texto completo del registro, para copiar al portapapeles. */
    fun dump(): String {
        val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        return _entries.value.joinToString(separator = "\n") { entry ->
            "${fmt.format(Date(entry.timestamp))} ${entry.level.name.padEnd(5)} " +
                "${entry.tag}: ${entry.message}"
        }
    }

    // --------------------------------------------------------------- interno

    private fun add(level: Level, tag: String, message: String, mirror: Boolean = true) {
        if (mirror) {
            Log.println(level.toPriority(), CATEGORY, "$tag: $message")
        }
        val now = System.currentTimeMillis()
        val entry = Entry(counter.incrementAndGet(), now, level, tag, message)
        _entries.update { current ->
            val next = current + entry
            if (next.size > MAX_ENTRIES) next.takeLast(MAX_ENTRIES) else next
        }
    }

    /**
     * Llamado desde C++ (JNI). [priority] usa los valores de android.util.Log.
     */
    @JvmStatic
    fun nativeLog(priority: Int, tag: String, message: String) {
        val level = when {
            priority >= Log.ERROR -> Level.ERROR
            priority == Log.WARN -> Level.WARN
            priority == Log.INFO -> Level.INFO
            else -> Level.DEBUG
        }
        // El nativo ya escribió en logcat; aquí solo alimentamos la UI.
        add(level, tag, message, mirror = false)
    }

    private fun Level.toPriority(): Int = when (this) {
        Level.DEBUG -> Log.DEBUG
        Level.INFO -> Log.INFO
        Level.WARN -> Log.WARN
        Level.ERROR -> Log.ERROR
    }
}
