package ua.blackout.cherkasy.domain

import ua.blackout.cherkasy.data.DaySchedule
import ua.blackout.cherkasy.data.Interval
import ua.blackout.cherkasy.data.KYIV
import ua.blackout.cherkasy.data.QUEUES
import ua.blackout.cherkasy.data.Snapshot
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

sealed interface PowerStatus {
    /** Nothing cached yet (first launch, offline). */
    data object NoData : PowerStatus

    /** Data is there but today's post isn't published (or has no rows for this queue's day). */
    data object NotPublished : PowerStatus

    /** Today's post exists but no queue rows could be parsed from it — never claim "no outages". */
    data object Unreadable : PowerStatus

    /** Power is off; comes back at [until] minutes from today's midnight (may be ≥ 1440). */
    data class Off(val until: Int) : PowerStatus

    /** Power is on; next outage starts at [nextOff] minutes from today's midnight, if any is known. */
    data class On(val nextOff: Int?) : PowerStatus
}

enum class Slot { PAST_ON, PAST_OFF, ON, OFF, NOW_ON, NOW_OFF }

object Timeline {
    const val SLOT_MIN = 30
    const val SLOTS = 1440 / SLOT_MIN

    fun minuteOfDay(now: ZonedDateTime) = now.hour * 60 + now.minute

    fun day(snapshot: Snapshot?, date: LocalDate): DaySchedule? =
        snapshot?.days?.firstOrNull { it.date == date.toString() }

    /** Outage intervals for [queue] over today (0..1440) and tomorrow (1440..2880), merged. */
    fun merged(snapshot: Snapshot, queue: String, today: LocalDate): List<Interval> {
        val all = buildList {
            for ((offset, date) in listOf(0 to today, 1440 to today.plusDays(1))) {
                day(snapshot, date)?.queues?.get(queue)?.forEach { add(Interval(it.start + offset, it.end + offset)) }
            }
        }
        return mergeIntervals(all)
    }

    fun status(snapshot: Snapshot?, queue: String, now: ZonedDateTime): PowerStatus {
        if (snapshot == null || (snapshot.fetchedAt == 0L && snapshot.days.isEmpty())) return PowerStatus.NoData
        val today = now.toLocalDate()
        val todayPost = day(snapshot, today) ?: return PowerStatus.NotPublished
        if (todayPost.queues.isEmpty()) return PowerStatus.Unreadable
        val min = minuteOfDay(now)
        val ivs = merged(snapshot, queue, today)
        ivs.firstOrNull { min >= it.start && min < it.end }?.let { return PowerStatus.Off(it.end) }
        return PowerStatus.On(ivs.firstOrNull { it.start > min }?.start)
    }

    /** 48 half-hour slots for one day's bar. [nowMin] is null when the day isn't today. */
    fun slots(day: DaySchedule?, queue: String, nowMin: Int?, isPastDay: Boolean = false): List<Slot> {
        val ivs = day?.queues?.get(queue).orEmpty()
        return List(SLOTS) { i ->
            val s = i * SLOT_MIN
            val e = s + SLOT_MIN
            val off = ivs.any { it.start < e && it.end > s }
            when {
                isPastDay || (nowMin != null && e <= nowMin) -> if (off) Slot.PAST_OFF else Slot.PAST_ON
                nowMin != null && nowMin in s until e -> if (off) Slot.NOW_OFF else Slot.NOW_ON
                else -> if (off) Slot.OFF else Slot.ON
            }
        }
    }

    /**
     * When the widget next needs re-rendering: the nearest of the next half-hour mark (the bar's
     * "now" slot moves), any outage boundary for any queue, or Kyiv midnight.
     */
    fun nextTick(snapshot: Snapshot?, now: ZonedDateTime): ZonedDateTime {
        val midnight = now.toLocalDate().atStartOfDay(KYIV)
        val nowMin = minuteOfDay(now)
        var next = (nowMin / SLOT_MIN + 1) * SLOT_MIN
        snapshot?.days?.forEach { d ->
            val offset = when (d.date) {
                now.toLocalDate().toString() -> 0
                now.toLocalDate().plusDays(1).toString() -> 1440
                else -> return@forEach
            }
            d.queues.values.flatten().forEach { iv ->
                for (b in intArrayOf(iv.start + offset, iv.end + offset)) if (b in (nowMin + 1) until next) next = b
            }
        }
        return midnight.plus(next.toLong(), ChronoUnit.MINUTES).plusSeconds(5)
    }

    /**
     * Rebuilds one day from all its published versions. Each version counts only while it was in
     * effect — from its publish time until the next version — and the newest covers the rest of the
     * day. So an outage that already happened survives a later update that drops it, while one that
     * was replaced before it started stays out.
     */
    fun combineVersions(versions: List<DaySchedule>): DaySchedule {
        val usable = versions.filter { it.queues.isNotEmpty() }
            .sortedBy { it.publishedAt ?: "" } // ISO strings sort chronologically; unknown = oldest
        if (usable.isEmpty()) return versions.maxBy { it.publishedAt ?: "" }
        val newest = usable.last()
        if (usable.size == 1) return newest
        val date = LocalDate.parse(newest.date)
        fun minuteOf(iso: String?): Int {
            val t = iso?.let { LocalDateTime.parse(it) } ?: return 0
            return when {
                t.toLocalDate().isBefore(date) -> 0
                t.toLocalDate().isAfter(date) -> 1440
                else -> t.hour * 60 + t.minute
            }
        }
        val queues = usable.flatMap { it.queues.keys }.distinct().associateWith { q ->
            mergeIntervals(
                usable.flatMapIndexed { i, v ->
                    val from = minuteOf(v.publishedAt)
                    val to = if (i == usable.lastIndex) 1440 else minuteOf(usable[i + 1].publishedAt)
                    clip(v.queues[q].orEmpty(), from, to)
                },
            )
        }
        return newest.copy(queues = queues)
    }

    /**
     * Keeps outages from an earlier fetch of the same day that ended before [untilMin] (the minute
     * of that fetch) — a fallback for when old versions have dropped off the news list.
     */
    fun keepPast(old: DaySchedule, new: DaySchedule, untilMin: Int): DaySchedule {
        if (untilMin <= 0 || old.date != new.date) return new
        val keys = (old.queues.keys + new.queues.keys).filter { it in QUEUES }
        return new.copy(queues = keys.associateWith { q ->
            mergeIntervals(new.queues[q].orEmpty() + clip(old.queues[q].orEmpty(), 0, untilMin))
        })
    }

    private fun clip(ivs: List<Interval>, from: Int, to: Int): List<Interval> =
        ivs.mapNotNull { iv ->
            val s = maxOf(iv.start, from)
            val e = minOf(iv.end, to)
            if (s < e) Interval(s, e) else null
        }

    /** Sorted, with overlapping or touching intervals joined. */
    fun mergeIntervals(ivs: List<Interval>): List<Interval> {
        val out = mutableListOf<Interval>()
        for (iv in ivs.sortedBy { it.start }) {
            val last = out.lastOrNull()
            if (last != null && iv.start <= last.end) out[out.lastIndex] = last.copy(end = maxOf(last.end, iv.end))
            else out += iv
        }
        return out
    }

    /** "16:00", "24:00", or "02:00" for times on the following day (caller adds "завтра"). */
    fun hhmm(min: Int): String {
        if (min == 1440) return "24:00"
        val m = min % 1440
        return "%02d:%02d".format(m / 60, m % 60)
    }

    fun isTomorrow(min: Int) = min > 1440
}
