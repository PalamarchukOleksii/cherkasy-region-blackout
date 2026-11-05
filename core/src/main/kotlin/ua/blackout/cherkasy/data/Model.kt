package ua.blackout.cherkasy.data

import kotlinx.serialization.Serializable
import java.time.ZoneId

val KYIV: ZoneId = ZoneId.of("Europe/Kyiv")

val QUEUES = listOf("1.1", "1.2", "2.1", "2.2", "3.1", "3.2", "4.1", "4.2", "5.1", "5.2", "6.1", "6.2")

/** Outage window in minutes from local midnight; [end] may be 1440 ("24:00"). */
@Serializable
data class Interval(val start: Int, val end: Int)

@Serializable
data class DaySchedule(
    val date: String, // YYYY-MM-DD, Kyiv time
    val title: String,
    val sourceUrl: String,
    val queues: Map<String, List<Interval>>,
    val note: String? = null,
    val error: String? = null,
    /** When this version took effect (Kyiv local, ISO); null if the page didn't say. */
    val publishedAt: String? = null,
)

@Serializable
data class Snapshot(
    val fetchedAt: Long, // epoch millis of the last successful fetch
    val days: List<DaySchedule>,
    val lastError: String? = null,
)
