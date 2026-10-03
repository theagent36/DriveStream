package com.example.drivestream

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostic logger for thumbnail operations, network requests,
 * and image loader lifecycle.
 */
object ThumbnailDiagnosticManager {
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun log(tag: String, message: String) {
        val time = dateFormat.format(Date())
        val line = "[$time][$tag] $message"
        android.util.Log.d(tag, message)
        _logs.update { current ->
            (current + line).takeLast(100)
        }
    }

    fun clear() {
        _logs.value = emptyList()
    }

    fun getAllLogsText(): String {
        return _logs.value.joinToString("\n")
    }
}
