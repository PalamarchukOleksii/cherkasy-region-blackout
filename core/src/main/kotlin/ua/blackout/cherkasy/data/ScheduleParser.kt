package ua.blackout.cherkasy.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import java.time.LocalDate
import java.time.LocalDateTime

/** Scrapes ГПВ schedules from cherkasyoblenergo.com news posts. Pure functions, no I/O. */
object ScheduleParser {
    const val BASE = "https://www.cherkasyoblenergo.com"
    const val LIST_URL = "$BASE/media?lang=uk"

    private val MONTHS = mapOf(
        "sichnya" to 1, "lyutoho" to 2, "bereznya" to 3, "kvitnya" to 4, "travnya" to 5, "chervnya" to 6,
        "lypnya" to 7, "serpnya" to 8, "veresnya" to 9, "zhovtnya" to 10, "lystopada" to 11, "hrudnya" to 12,
    )

    data class PostRef(val date: LocalDate, val path: String)

    private val postLinkRe = Regex("""href="(/media/[^"?]*hpv-na-(\d{1,2})-([a-z]+)(?:-\d+)?(?:\?[^"]*)?)"""")

    /**
     * Every schedule post on the list page, including all "Оновлено…" versions of the same day
     * (each path once). Updates rewrite the whole day, so older versions are needed to know
     * which outages already happened.
     */
    fun findSchedulePosts(listHtml: String, today: LocalDate): List<PostRef> {
        val seen = LinkedHashMap<String, PostRef>()
        for (m in postLinkRe.findAll(listHtml)) {
            val (path, day, monthSlug) = m.destructured
            val month = MONTHS[monthSlug] ?: continue
            var year = today.year
            if (today.monthValue == 12 && month == 1) year++
            if (today.monthValue == 1 && month == 12) year--
            val date = runCatching { LocalDate.of(year, month, day.toInt()) }.getOrNull() ?: continue
            seen.putIfAbsent(path.substringBefore('?'), PostRef(date, path))
        }
        return seen.values.toList()
    }

    private val nuxtDataRe = Regex("""<script[^>]*id="__NUXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)

    /**
     * When a post version took effect: the earlier of its createdAt / publishedAt (editors sometimes
     * set publishedAt hours after the update was written). Both are Kyiv local time in the Nuxt
     * payload, stored as indices into its flat data array.
     */
    fun publishTime(postHtml: String): LocalDateTime? {
        val json = nuxtDataRe.find(postHtml)?.groupValues?.get(1) ?: return null
        val arr = runCatching { Json.parseToJsonElement(json).jsonArray }.getOrNull() ?: return null
        val post = arr.firstOrNull { it is JsonObject && "publishedAt" in it } as? JsonObject ?: return null
        fun resolve(key: String): LocalDateTime? {
            val index = (post[key] as? JsonPrimitive)?.intOrNull ?: return null
            val text = (arr.getOrNull(index) as? JsonPrimitive)?.contentOrNull ?: return null
            return runCatching { LocalDateTime.parse(text.take(19)) }.getOrNull()
        }
        return listOfNotNull(resolve("publishedAt"), resolve("createdAt")).minOrNull()
    }

    /** Extracts the article body HTML from the Nuxt payload (a JSON string with \u003C escapes). */
    fun extractBody(postHtml: String): String? {
        val i = postHtml.lastIndexOf("Години відсутності")
        if (i == -1) return null
        var start = i
        while (start > 0 && !(postHtml[start - 1] == '"' && postHtml.getOrNull(start - 2) != '\\')) start--
        var end = i
        while (end < postHtml.length && !(postHtml[end] == '"' && postHtml[end - 1] != '\\')) end++
        val raw = postHtml.substring(start, end)
        return runCatching { Json.decodeFromString<String>("\"$raw\"") }
            .getOrElse { raw.replace("\\u003C", "<").replace("\\\"", "\"") }
    }

    fun toPlainText(html: String): String = html
        .replace(Regex("</p>|<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace(Regex("&nbsp;|\u00A0"), " ")
        .replace("&amp;", "&")
        .replace(Regex("[ \t]+"), " ")
        .replace(Regex("\\s*\n\\s*"), "\n")
        .trim()

    private val lineRe =
        Regex("""(\d\.\d)\s+((?:\d{1,2}\s*:\s*\d{2}\s*[-–—]\s*\d{1,2}\s*:\s*\d{2}[\s,;]*)+)""")
    private val rangeRe = Regex("""(\d{1,2})\s*:\s*(\d{2})\s*[-–—]\s*(\d{1,2})\s*:\s*(\d{2})""")

    fun parseQueues(text: String): Map<String, List<Interval>> {
        val queues = LinkedHashMap<String, List<Interval>>()
        for (m in lineRe.findAll(text)) {
            val (q, ranges) = m.destructured
            if (q !in QUEUES || q in queues) continue
            queues[q] = rangeRe.findAll(ranges).map {
                val (h1, m1, h2, m2) = it.destructured
                Interval(h1.toInt() * 60 + m1.toInt(), h2.toInt() * 60 + m2.toInt())
            }.toList()
        }
        return queues
    }

    private val titleRe = Regex("""<meta property="og:title" content="([^"]*)"""")

    private val UA_MONTHS = mapOf(
        "січня" to 1, "лютого" to 2, "березня" to 3, "квітня" to 4, "травня" to 5, "червня" to 6,
        "липня" to 7, "серпня" to 8, "вересня" to 9, "жовтня" to 10, "листопада" to 11, "грудня" to 12,
    )
    private val titleDateRe = Regex("""на\s+(\d{1,2})\s+(\p{L}+)""", RegexOption.IGNORE_CASE)

    /**
     * The day a post is about, from its title ("…(ГПВ) на 19 жовтня"). Slugs can't be trusted: the
     * site has published the 19 October schedule as "…hpv-na-18-zhovtnya-4". The year is the one
     * closest to [near].
     */
    fun dateFromTitle(title: String, near: LocalDate): LocalDate? {
        val m = titleDateRe.find(title) ?: return null
        val month = UA_MONTHS[m.groupValues[2].lowercase()] ?: return null
        val day = m.groupValues[1].toInt()
        return listOf(near.year - 1, near.year, near.year + 1)
            .mapNotNull { y -> runCatching { LocalDate.of(y, month, day) }.getOrNull() }
            .minByOrNull { kotlin.math.abs(it.toEpochDay() - near.toEpochDay()) }
    }

    /** [slugDate] is only a hint; the title's date wins when it has one. */
    fun parsePost(slugDate: LocalDate, sourceUrl: String, html: String): DaySchedule {
        val title = titleRe.find(html)?.groupValues?.get(1).orEmpty()
        val date = dateFromTitle(title, slugDate) ?: slugDate
        val body = extractBody(html)
            ?: return DaySchedule(
                date.toString(), title, sourceUrl, emptyMap(),
                error = "Не вдалося знайти текст графіка", publishedAt = publishTime(html)?.toString(),
            )
        val text = toPlainText(body)
        val queues = parseQueues(text)
        return DaySchedule(
            date = date.toString(),
            title = title,
            sourceUrl = sourceUrl,
            queues = queues,
            note = if (queues.isEmpty()) text.take(500) else null,
            publishedAt = publishTime(html)?.toString(),
        )
    }
}
