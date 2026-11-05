package ua.blackout.cherkasy.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import ua.blackout.cherkasy.domain.Timeline

/** Fetches schedules from cherkasyoblenergo.com and caches the last good result on disk. */
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
            Snapshot(fetchedAt = System.currentTimeMillis(), days = keepPast(previous, fetchDays()))
        } catch (e: Exception) {
            val message = e.message ?: e.javaClass.simpleName
            previous?.copy(lastError = message) ?: Snapshot(fetchedAt = 0, days = emptyList(), lastError = message)
        }
        state.value = next
        runCatching { file(context).writeText(json.encodeToString(Snapshot.serializer(), next)) }
        next
    }

    private suspend fun fetchDays(): List<DaySchedule> = coroutineScope {
        val today = LocalDate.now(KYIV)
        val wanted = setOf(today, today.plusDays(1))
        // Slugs can be off by a day (see ScheduleParser.dateFromTitle), so fetch a day either side
        // and keep posts by the date in their title.
        val posts = ScheduleParser.findSchedulePosts(getText(ScheduleParser.LIST_URL), today)
            .filter { it.date in setOf(today.minusDays(1), today, today.plusDays(1)) }
        // All versions of each day, merged so outages that already happened aren't lost.
        posts.map { post ->
            async {
                val url = ScheduleParser.BASE + post.path
                ScheduleParser.parsePost(post.date, url, getText(url))
            }
        }.awaitAll()
            .filter { LocalDate.parse(it.date) in wanted }
            .groupBy { it.date }
            .map { (_, versions) -> Timeline.combineVersions(versions) }
            .sortedBy { it.date }
    }

    /** Fallback: carry over the past part of the previous fetch for the same day. */
    private fun keepPast(previous: Snapshot?, days: List<DaySchedule>): List<DaySchedule> {
        if (previous == null || previous.fetchedAt <= 0) return days
        val fetched = Instant.ofEpochMilli(previous.fetchedAt).atZone(KYIV)
        return days.map { day ->
            // Skip cached days built from a mislabelled post (title names another date).
            val old = previous.days.firstOrNull { it.date == day.date && titleMatches(it) } ?: return@map day
            val until = when {
                fetched.toLocalDate().toString() == day.date -> fetched.hour * 60 + fetched.minute
                fetched.toLocalDate().toString() > day.date -> 1440
                else -> 0
            }
            Timeline.keepPast(old, day, until)
        }
    }

    private fun titleMatches(day: DaySchedule): Boolean {
        val date = LocalDate.parse(day.date)
        return (ScheduleParser.dateFromTitle(day.title, date) ?: date) == date
    }

    private fun getText(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("User-Agent", "blackout-widget/1.0")
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
