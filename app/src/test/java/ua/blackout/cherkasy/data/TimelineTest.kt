package ua.blackout.cherkasy.data

import org.junit.Assert.assertEquals
import org.junit.Test
import ua.blackout.cherkasy.domain.PowerStatus
import ua.blackout.cherkasy.domain.Slot
import ua.blackout.cherkasy.domain.Timeline
import java.time.LocalDate
import java.time.ZonedDateTime

class TimelineTest {
    private val today = LocalDate.of(2026, 10, 18)

    private fun at(h: Int, m: Int) = ZonedDateTime.of(today.atTime(h, m), KYIV)

    private val snapshot = Snapshot(
        fetchedAt = 1,
        days = listOf(
            DaySchedule("2026-10-18", "", "", mapOf("1.2" to listOf(Interval(960, 1080), Interval(1320, 1440)))),
            DaySchedule("2026-10-19", "", "", mapOf("1.2" to listOf(Interval(0, 120), Interval(630, 720)))),
        ),
    )

    @Test
    fun onBeforeOutage() {
        assertEquals(PowerStatus.On(960), Timeline.status(snapshot, "1.2", at(9, 0)))
    }

    @Test
    fun offInsideOutage() {
        assertEquals(PowerStatus.Off(1080), Timeline.status(snapshot, "1.2", at(16, 0)))
    }

    @Test
    fun outageAcrossMidnightIsMerged() {
        assertEquals(PowerStatus.Off(1440 + 120), Timeline.status(snapshot, "1.2", at(23, 10)))
    }

    @Test
    fun noTodayPostMeansNotPublished() {
        val s = snapshot.copy(days = snapshot.days.drop(1))
        assertEquals(PowerStatus.NotPublished, Timeline.status(s, "1.2", at(9, 0)))
    }

    @Test
    fun unparsedTodayPostIsNotReportedAsNoOutages() {
        val s = snapshot.copy(days = listOf(DaySchedule("2026-10-18", "", "", emptyMap(), error = "x")))
        assertEquals(PowerStatus.Unreadable, Timeline.status(s, "1.2", at(9, 0)))
    }

    @Test
    fun slotsMarkPastNowAndFuture() {
        val slots = Timeline.slots(Timeline.day(snapshot, today), "1.2", nowMin = 16 * 60 + 10)
        assertEquals(Slot.PAST_ON, slots[0])
        assertEquals(Slot.NOW_OFF, slots[32])
        assertEquals(Slot.OFF, slots[33])
        assertEquals(Slot.ON, slots[36])
        assertEquals(Slot.OFF, slots[47])
    }

    @Test
    fun nextTickPicksNearestBoundary() {
        assertEquals(at(9, 30).plusSeconds(5), Timeline.nextTick(snapshot, at(9, 12)))
        // 10:30 tomorrow is a boundary, but for today the half-hour mark comes first.
        assertEquals(at(23, 30).plusSeconds(5), Timeline.nextTick(snapshot, at(23, 0)))
        assertEquals(today.plusDays(1).atStartOfDay(KYIV).plusSeconds(5), Timeline.nextTick(snapshot, at(23, 45)))
    }

    @Test
    fun hhmmFormatting() {
        assertEquals("09:05", Timeline.hhmm(545))
        assertEquals("24:00", Timeline.hhmm(1440))
        assertEquals("02:00", Timeline.hhmm(1560))
    }

    @Test
    fun keepPastOnlyBeforePreviousFetch() {
        val old = DaySchedule("2026-10-18", "", "", mapOf("5.2" to listOf(Interval(480, 600), Interval(1080, 1200))))
        val new = DaySchedule("2026-10-18", "", "", mapOf("5.2" to listOf(Interval(720, 840), Interval(1080, 1200))))
        // Previous fetch at 10:43: its 08–10 is kept; its future isn't re-added over the new data.
        val merged = Timeline.keepPast(old, new, untilMin = 643)
        assertEquals(listOf(Interval(480, 600), Interval(720, 840), Interval(1080, 1200)), merged.queues["5.2"])
        val cancelled = new.copy(queues = mapOf("5.2" to listOf(Interval(720, 840))))
        assertEquals(listOf(Interval(480, 600), Interval(720, 840)), Timeline.keepPast(old, cancelled, 643).queues["5.2"])
    }
}
