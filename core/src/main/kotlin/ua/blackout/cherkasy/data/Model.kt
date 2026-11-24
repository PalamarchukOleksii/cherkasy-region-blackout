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
    /**
     * Temporary individual schedule for part of Uman district (today and tomorrow), published as
     * open-ended "Тимчасові графіки відключень в Уманському районі" posts. Empty when none is in force.
     */
    val umanDays: List<DaySchedule> = emptyList(),
)

/** Which schedule a user follows. Uman's temporary schedule only has the N.2 subqueues. */
enum class Area(val id: String, val label: String, val queues: List<String>) {
    REGION("region", "Вся область", QUEUES),
    UMAN("uman", "Уманський район", QUEUES.filter { it.endsWith(".2") });

    companion object {
        fun of(id: String?): Area = entries.firstOrNull { it.id == id } ?: REGION
    }
}

/**
 * The snapshot as seen from [area]: for Uman, its temporary schedule replaces the regional days.
 * Falls back to the regional ГПВ when no temporary schedule is in force (see [usesFallback]).
 */
fun Snapshot.forArea(area: Area): Snapshot =
    if (area == Area.UMAN && umanDays.isNotEmpty()) copy(days = umanDays) else this

/** Uman was chosen but no temporary schedule was found, so the regional ГПВ is shown instead. */
fun Snapshot.usesFallback(area: Area): Boolean = area == Area.UMAN && umanDays.isEmpty()
