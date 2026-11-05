package ua.blackout.cherkasy.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ScheduleParserTest {
    private fun fixture(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    private val today = LocalDate.of(2026, 10, 18)

    @Test
    fun findsAllPostVersions() {
        val posts = ScheduleParser.findSchedulePosts(fixture("list-2026-10-18.html"), today)
        assertEquals(today, posts.first().date)
        assertEquals("/media/onovleno-hrafik-pohodynnykh-vidklyuchen-hpv-na-18-zhovtnya-1?lang=uk", posts.first().path)
        assertEquals(posts.size, posts.map { it.path.substringBefore('?') }.toSet().size)
        assertTrue(posts.count { it.date == today } > 1) // every "Оновлено…" version is kept
        assertTrue(posts.any { it.date == LocalDate.of(2026, 10, 17) })
    }

    @Test
    fun yearRollsOverAroundNewYear() {
        val html = """<a href="/media/hrafik-hpv-na-1-sichnya?lang=uk"></a><a href="/media/hrafik-hpv-na-31-hrudnya"></a>"""
        val posts = ScheduleParser.findSchedulePosts(html, LocalDate.of(2026, 12, 31))
        assertEquals(listOf(LocalDate.of(2027, 1, 1), LocalDate.of(2026, 12, 31)), posts.map { it.date })
    }

    @Test
    fun parsesAllQueuesFromLivePost() {
        val day = ScheduleParser.parsePost(today, "https://example/post", fixture("post-2026-10-18.html"))
        assertNull(day.error)
        assertEquals("Оновлено графік погодинних відключень (ГПВ) на 18 жовтня", day.title)
        assertEquals(QUEUES.toSet(), day.queues.keys)
        // Values cross-checked against the post as published on the site.
        assertEquals(listOf(Interval(600, 720), Interval(960, 1080)), day.queues["1.1"])
        assertEquals(listOf(Interval(960, 1080), Interval(1320, 1440)), day.queues["1.2"])
        assertEquals(listOf(Interval(630, 720), Interval(840, 960)), day.queues["2.1"])
        assertEquals(listOf(Interval(840, 960)), day.queues["2.2"])
        assertEquals(listOf(Interval(1080, 1200), Interval(1320, 1440)), day.queues["6.2"])
    }

    @Test
    fun parseQueuesHandlesDashesAndSpacing() {
        val q = ScheduleParser.parseQueues("1.1 08 :00 – 10:30; 14:00—16:00\n3.2 0:00-4:00\n9.9 01:00-02:00")
        assertEquals(listOf(Interval(480, 630), Interval(840, 960)), q["1.1"])
        assertEquals(listOf(Interval(0, 240)), q["3.2"])
        assertNull(q["9.9"])
    }

    @Test
    fun missingBodyReportsError() {
        val day = ScheduleParser.parsePost(today, "u", "<html><meta property=\"og:title\" content=\"X\"></html>")
        assertTrue(day.queues.isEmpty())
        assertEquals("X", day.title)
        assertTrue(day.error != null)
    }

    private val versionFiles = listOf(
        "hrafik-pohodynnykh-vidklyuchen-hpv-na-18-zhovtnya",
        "onovleno-hrafik-pohodynnykh-vidklyuchen-hpv-na-18-zhovtnya",
        "onovleno-hrafik-pohodynnykh-vidklyuchen-hpv-na-18-zhovtnya-1",
        "onovleno-hrafik-pohodynnykh-vidklyuchen-hpv-na-18-zhovtnya-2",
    )

    private fun versions() = versionFiles.map {
        ScheduleParser.parsePost(today, it, fixture("versions-2026-10-18/$it.html"))
    }

    @Test
    fun publishTimeIsEarlierOfCreatedAndPublished() {
        // -2: created 10:30:36, published 11:18 → in effect from 10:30.
        assertEquals(
            java.time.LocalDateTime.of(2026, 10, 18, 10, 30, 36),
            ScheduleParser.publishTime(fixture("versions-2026-10-18/${versionFiles[3]}.html")),
        )
        // First update: created the evening before (20:36), "published" next morning.
        assertEquals(
            java.time.LocalDateTime.of(2026, 10, 17, 20, 36, 22),
            ScheduleParser.publishTime(fixture("versions-2026-10-18/${versionFiles[1]}.html")),
        )
    }

    @Test
    fun allVersionsOnListPage() {
        val posts = ScheduleParser.findSchedulePosts(fixture("versions-2026-10-18/list.html"), today)
        assertEquals(4, posts.count { it.date == today })
    }

    @Test
    fun combinedDayKeepsOutagesThatAlreadyHappened() {
        val day = ua.blackout.cherkasy.domain.Timeline.combineVersions(versions())
        // The latest post (10:30) dropped 08–10 for 5.2, but it was in effect from 20:36 the night before.
        assertEquals(listOf(Interval(480, 600), Interval(720, 840), Interval(1080, 1200)), day.queues["5.2"])
        // 1.1's 06–08 was replaced at 20:36 the night before, so it never happened and stays out.
        assertEquals(listOf(Interval(600, 720), Interval(960, 1080)), day.queues["1.1"])
        // Title/link come from the newest version.
        assertEquals(versionFiles[3], day.sourceUrl)
    }

    @Test
    fun titleDateWinsOverSlug() {
        // Published as "…hpv-na-18-zhovtnya-4", but it's the 19 October schedule.
        val post = ScheduleParser.parsePost(today, "u", fixture("versions-2026-10-18/mislabelled-19-as-18-zhovtnya-4.html"))
        assertEquals("2026-10-19", post.date)
        assertEquals(LocalDate.of(2027, 1, 1), ScheduleParser.dateFromTitle("ГПВ на 1 січня", LocalDate.of(2026, 12, 31)))
        assertEquals(null, ScheduleParser.dateFromTitle("Новини компанії", today))
    }

    @Test
    fun mislabelledTomorrowDoesNotLeakIntoToday() {
        val all = versions() + ScheduleParser.parsePost(today, "u", fixture("versions-2026-10-18/mislabelled-19-as-18-zhovtnya-4.html"))
        val todayDay = ua.blackout.cherkasy.domain.Timeline.combineVersions(all.filter { it.date == today.toString() })
        assertEquals(listOf(Interval(480, 600), Interval(720, 840), Interval(1080, 1200)), todayDay.queues["5.2"])
    }
}
