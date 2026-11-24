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
            val today = LocalDate.now(KYIV)
            val listHtml = getText(ScheduleParser.LIST_URL, userAgent)
            coroutineScope {
                val daily = async { fetchDaily(listHtml, today, userAgent) }
                val temporary = async { fetchTemporaryPosts(listHtml, previous, today, userAgent) }
                val (region, umanSections) = daily.await()
                Snapshot(
                    fetchedAt = now,
                    days = keepPast(previous, previous?.days, region),
                    umanDays = keepPast(previous, previous?.umanDays, umanDays(temporary.await(), umanSections, today)),
                )
            }
        }

    /**
     * Uman district's schedule for today and tomorrow. Two sources count as versions of the same
     * day, newest in force (see [Timeline.combineVersions]): the Uman section of a dated daily post,
     * and the older undated "Тимчасові графіки … в Уманському районі" posts, which stay in force
     * from the day they were published until replaced. Empty when neither exists, so Uman falls
     * back to the regional ГПВ.
     */
    fun umanDays(temporary: List<DaySchedule>, sections: List<DaySchedule>, today: LocalDate): List<DaySchedule> =
        listOf(today, today.plusDays(1)).mapNotNull { date ->
            val iso = date.toString()
            val inForce = temporary.filter { it.date <= iso }.map { it.copy(date = iso) } + sections.filter { it.date == iso }
            if (inForce.isEmpty()) null else Timeline.combineVersions(inForce)
        }

    /**
     * Undated Uman posts. Page 2 of the news list is only checked while such a post was in force,
     * so the usual case costs no extra request.
     */
    private suspend fun fetchTemporaryPosts(listHtml: String, previous: Snapshot?, today: LocalDate, userAgent: String): List<DaySchedule> =
        coroutineScope {
            var paths = ScheduleParser.findUmanPosts(listHtml)
            if (paths.isEmpty() && previous?.umanDays.orEmpty().any { ScheduleParser.isUmanPost(it.sourceUrl) }) {
                paths = ScheduleParser.findUmanPosts(getText(ScheduleParser.LIST_URL + "&page=2", userAgent))
            }
            paths.map { path ->
                async {
                    val url = ScheduleParser.BASE + path
                    ScheduleParser.parseTemporaryPost(url, getText(url, userAgent), today)
                }
            }.awaitAll()
        }

    /** Today's and tomorrow's daily posts: the regional days, and the Uman sections they carry. */
    private suspend fun fetchDaily(listHtml: String, today: LocalDate, userAgent: String): Pair<List<DaySchedule>, List<DaySchedule>> =
        coroutineScope {
            val wanted = setOf(today, today.plusDays(1))
            // Slugs can be off by a day (see ScheduleParser.dateFromTitle), so fetch a day either side
            // and keep posts by the date in their title.
            val posts = ScheduleParser.findSchedulePosts(listHtml, today)
                .filter { it.date in setOf(today.minusDays(1), today, today.plusDays(1)) }
            // All versions of each day, merged so outages that already happened aren't lost.
            val parsed = posts.map { post ->
                async {
                    val url = ScheduleParser.BASE + post.path
                    ScheduleParser.parsePostSections(post.date, url, getText(url, userAgent))
                }
            }.awaitAll().filter { LocalDate.parse(it.region.date) in wanted }
            val region = parsed.map { it.region }
                .groupBy { it.date }
                .map { (_, versions) -> Timeline.combineVersions(versions) }
                .sortedBy { it.date }
            region to parsed.mapNotNull { it.uman }
        }

    /** Fallback: carry over the past part of the previous fetch for the same day. */
    private fun keepPast(previous: Snapshot?, oldDays: List<DaySchedule>?, days: List<DaySchedule>): List<DaySchedule> {
        if (previous == null || previous.fetchedAt <= 0 || oldDays == null) return days
        val fetched = Instant.ofEpochMilli(previous.fetchedAt).atZone(KYIV)
        return days.map { day ->
            // Skip cached days built from a mislabelled post (title names another date).
            val old = oldDays.firstOrNull { it.date == day.date && titleMatches(it) } ?: return@map day
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
