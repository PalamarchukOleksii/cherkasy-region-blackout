package ua.blackout.cherkasy.web

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import ua.blackout.cherkasy.data.ScheduleFetcher
import ua.blackout.cherkasy.data.Snapshot
import java.io.File
import java.net.URI

private val json = Json { ignoreUnknownKeys = true }

/**
 * Writes `<outDir>/schedule.json` for the GitHub Pages site.
 *
 * Args: `<outDir> [previousUrl]`. The previously published snapshot is loaded so that outages that
 * already happened survive (same as the app's on-device cache). If the site can't be fetched, the
 * last good data is republished with `lastError` set, so the page can say it's stale.
 */
fun main(args: Array<String>) {
    val outDir = File(args.getOrNull(0) ?: error("usage: <outDir> [previousUrl]"))
    val previous = args.getOrNull(1)?.let(::loadPrevious)
    val snapshot = try {
        runBlocking { ScheduleFetcher.fetch(previous, userAgent = "blackout-widget-pages/1.0") }
    } catch (e: Exception) {
        val message = e.message ?: e.javaClass.simpleName
        System.err.println("Fetch failed: $message")
        previous?.copy(lastError = message) ?: Snapshot(fetchedAt = 0, days = emptyList(), lastError = message)
    }
    outDir.mkdirs()
    File(outDir, "schedule.json").writeText(json.encodeToString(Snapshot.serializer(), snapshot))
    println("schedule.json: ${snapshot.days.map { "${it.date} (${it.queues.size} queues)" }}, error=${snapshot.lastError}")
}

private fun loadPrevious(url: String): Snapshot? = runCatching {
    val text = URI(url).toURL().openStream().bufferedReader().use { it.readText() }
    json.decodeFromString<Snapshot>(text)
}.onFailure { System.err.println("No previous snapshot ($url): ${it.message}") }.getOrNull()
