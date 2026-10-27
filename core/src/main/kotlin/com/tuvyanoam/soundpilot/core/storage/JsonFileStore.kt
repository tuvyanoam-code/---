package com.tuvyanoam.soundpilot.core.storage

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

val StoreJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    prettyPrint = false
}

/**
 * A small, crash-safe persistent value: the whole value is kept in memory and written to a JSON file with an
 * atomic rename on every update. Suitable for settings, rules and capped logs.
 */
class JsonFileStore<T : Any>(
    private val file: File,
    private val serializer: KSerializer<T>,
    private val default: () -> T,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()
    private val state = MutableStateFlow<T?>(null)

    /** Emits the current value once loaded, then every update. */
    val flow: Flow<T> = state.filterNotNull()

    suspend fun get(): T = mutex.withLock { loadLocked() }

    suspend fun update(transform: (T) -> T): T = mutex.withLock {
        val current = loadLocked()
        val next = transform(current)
        if (next != current) {
            writeLocked(next)
            state.value = next
        }
        next
    }

    suspend fun set(value: T) {
        update { value }
    }

    private suspend fun loadLocked(): T {
        state.value?.let { return it }
        val loaded = withContext(io) { read() } ?: default()
        state.value = loaded
        return loaded
    }

    private fun read(): T? {
        if (!file.exists()) return null
        return try {
            StoreJson.decodeFromString(serializer, file.readText())
        } catch (e: Exception) {
            // A corrupt file must not brick the app; keep a copy for diagnosis and start fresh.
            runCatching { file.copyTo(File(file.parentFile, file.name + ".corrupt"), overwrite = true) }
            null
        }
    }

    private suspend fun writeLocked(value: T) = withContext(io) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(StoreJson.encodeToString(serializer, value))
        if (!tmp.renameTo(file)) {
            // renameTo can fail across some file systems when the target exists.
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("Could not write ${file.absolutePath}")
        }
    }
}
