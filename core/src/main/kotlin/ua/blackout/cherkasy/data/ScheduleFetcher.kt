package ua.blackout.cherkasy.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import ua.blackout.cherkasy.domain.Timeline
import java.net.HttpURLConnection
import java.net.URI
import java.time.Instant
import java.time.LocalDate

/**
 * Downloads today's and tomorrow's schedules from cherkasyoblenergo.com. Shared by the Android app
 * and the GitHub Pages generator, so both show exactly the same data.
 */
object ScheduleFetcher {
    /**
     * A fresh [Snapshot]; outages from [previous] that already happened are kept. Throws on any
     * network or HTTP error, so the caller can keep its last good data.
     */
    suspend fun fetch(previous: Snapshot?, userAgent: String, now: Long = System.currentTimeMillis()): Snapshot =
        withContext(Dispatchers.IO) {
            Snapshot(fetchedAt = now, days = keepPast(previous, fetchDays(userAgent)))
        }

    private suspend fun fetchDays(userAgent: String): List<DaySchedule> = coroutineScope {
        val today = LocalDate.now(KYIV)
        val wanted = setOf(today, today.plusDays(1))
        // Slugs can be off by a day (see ScheduleParser.dateFromTitle), so fetch a day either side
        // and keep posts by the date in their title.
        val posts = ScheduleParser.findSchedulePosts(getText(ScheduleParser.LIST_URL, userAgent), today)
            .filter { it.date in setOf(today.minusDays(1), today, today.plusDays(1)) }
        // All versions of each day, merged so outages that already happened aren't lost.
        posts.map { post ->
            async {
                val url = ScheduleParser.BASE + post.path
                ScheduleParser.parsePost(post.date, url, getText(url, userAgent))
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

    private fun getText(url: String, userAgent: String): String {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("User-Agent", userAgent)
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
