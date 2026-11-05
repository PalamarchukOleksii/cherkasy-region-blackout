package ua.blackout.cherkasy.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/** Fetches schedules (via [ScheduleFetcher]) and caches the last good result on disk. */
object ScheduleRepository {
    private val json = Json { ignoreUnknownKeys = true }
    private val state = MutableStateFlow<Snapshot?>(null)
    private var loaded = false

    private fun file(context: Context) = File(context.applicationContext.filesDir, "schedule.json")

    fun snapshot(context: Context): StateFlow<Snapshot?> {
        if (!loaded) synchronized(this) {
            if (!loaded) {
                state.value = runCatching { json.decodeFromString<Snapshot>(file(context).readText()) }.getOrNull()
                loaded = true
            }
        }
        return state.asStateFlow()
    }

    suspend fun refresh(context: Context): Snapshot = withContext(Dispatchers.IO) {
        val previous = snapshot(context).value
        val next = try {
            ScheduleFetcher.fetch(previous, userAgent = "blackout-widget/1.0")
        } catch (e: Exception) {
            val message = e.message ?: e.javaClass.simpleName
            previous?.copy(lastError = message) ?: Snapshot(fetchedAt = 0, days = emptyList(), lastError = message)
        }
        state.value = next
        runCatching { file(context).writeText(json.encodeToString(Snapshot.serializer(), next)) }
        next
    }
}
