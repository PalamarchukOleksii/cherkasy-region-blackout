package ua.blackout.cherkasy.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.background
import androidx.compose.ui.unit.TextUnit
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import ua.blackout.cherkasy.config.ConfigActivity
import ua.blackout.cherkasy.data.KYIV
import ua.blackout.cherkasy.data.ScheduleRepository
import ua.blackout.cherkasy.data.Snapshot
import ua.blackout.cherkasy.domain.PowerStatus
import ua.blackout.cherkasy.domain.Timeline
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

val QUEUE_KEY = stringPreferencesKey("queue")

class BlackoutWidget : GlanceAppWidget() {
    companion object {
        /** 3×2 and wider use the 4×2 layout. */
        val WIDE_FROM = 200.dp

        /** …and the 4×3+ layout once they're this tall. */
        val LARGE_FROM = 220.dp
    }

    // Exact (not Responsive): LocalSize must be the real widget size, not a bucket.
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val snapshots = ScheduleRepository.snapshot(context)
        val defaultQueue = QueuePrefs.default(context)
        provideContent {
            val snapshot by snapshots.collectAsState()
            WidgetClock.tick.collectAsState().value // recompose on every scheduled tick
            val queue = currentState(QUEUE_KEY) ?: defaultQueue
            BlackoutGlanceTheme {
                WidgetContent(snapshot, queue, ZonedDateTime.now(KYIV), appWidgetId)
            }
        }
    }
}

@Composable
private fun WidgetContent(snapshot: Snapshot?, queue: String, now: ZonedDateTime, appWidgetId: Int) {
    val context = LocalContext.current
    val size = LocalSize.current
    val status = Timeline.status(snapshot, queue, now)
    val openConfig = actionStartActivity(
        Intent(context, ConfigActivity::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            .setData(Uri.parse("blackout://widget/$appWidgetId")),
    )
    val openApp = actionStartActivity(Intent(context, ConfigActivity::class.java))
    val ui = UiState(snapshot, queue, now, status, openConfig, openApp)

    val root = GlanceModifier.fillMaxSize()
        .appWidgetBackground()
        .background(GlanceTheme.colors.widgetBackground)
        .let { if (Build.VERSION.SDK_INT >= 31) it.cornerRadius(android.R.dimen.system_app_widget_background_radius) else it }
        .clickable(openApp) // the website link lives in the app

    // Same header in every size (as in the health widget): app · last update · refresh.
    Column(root.padding(12.dp)) {
        val wide = size.width >= BlackoutWidget.WIDE_FROM
        val large = wide && size.height >= BlackoutWidget.LARGE_FROM
        // With the queue chip in the header, the long "Оновлено о" only fits on 4-wide.
        Header(ui, longTime = if (large) size.width >= 300.dp else wide, queueChip = large)
        Spacer(GlanceModifier.height(8.dp))
        when {
            large -> LargeLayout(ui)
            wide -> WideLayout(ui)
            else -> StatusCard(ui, compact = true, modifier = GlanceModifier.fillMaxWidth().defaultWeight())
        }
    }
}

/** App icon (opens the app) on the left, last update time centered, refresh (and on 4×3+ the queue) on the right. */
@Composable
private fun Header(ui: UiState, longTime: Boolean, queueChip: Boolean) {
    Row(GlanceModifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
        IconPill(
            icon = ua.blackout.cherkasy.R.drawable.ic_bolt,
            background = GlanceTheme.colors.primary,
            tint = GlanceTheme.colors.onPrimary,
            action = ui.openApp,
            size = 28.dp,
            description = "Відкрити додаток",
        )
        // Equal-width buttons on both sides, so the weighted text is truly centered.
        UpdatedText(ui, if (longTime) "Оновлено о " else "", GlanceModifier.defaultWeight())
        if (queueChip) {
            QueueChip(ui.queue, short = false, action = ui.openConfig)
            Spacer(GlanceModifier.width(6.dp))
        }
        RefreshButton(size = 28.dp, error = ui.hasError)
    }
}

internal class UiState(
    val snapshot: Snapshot?,
    val queue: String,
    val now: ZonedDateTime,
    val status: PowerStatus,
    val openConfig: Action,
    val openApp: Action,
) {
    val today: LocalDate = now.toLocalDate()
    val tomorrow: LocalDate = today.plusDays(1)
    val hasError: Boolean = snapshot?.lastError != null

    /** "10:46" of the last successful fetch, or null if never fetched. */
    val updatedAt: String? = snapshot?.fetchedAt?.takeIf { it > 0 }?.let {
        Instant.ofEpochMilli(it).atZone(KYIV).format(DateTimeFormatter.ofPattern("HH:mm"))
    }
}

@Composable
internal fun UpdatedText(ui: UiState, prefix: String, modifier: GlanceModifier = GlanceModifier, size: TextUnit = 12.sp) {
    Text(
        ui.updatedAt?.let { prefix + it }.orEmpty(),
        style = textStyle(if (ui.hasError) GlanceTheme.colors.tertiary else GlanceTheme.colors.onSurfaceVariant, size)
            .copy(textAlign = TextAlign.Center),
        maxLines = 1,
        modifier = modifier,
    )
}

/**
 * 4×2: status headline on top, then a full-width day bar.
 *
 *  ⚡        Оновлено о 10:46        ⟳
 *  Світло є                     18:00
 *  Відключення о
 *  Сьогодні                     5.2
 *  ▬▬▬▬▬▬▬▬▬▬▬█▬▬▬▬▬▬▬▬▬▬▬
 *  00  03  06  09  12  15  18  21
 */
@Composable
private fun ColumnScope.WideLayout(ui: UiState) {
    StatusHeadline(ui, title = 17.sp, time = 32.sp)
    Spacer(GlanceModifier.defaultWeight())
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Сьогодні", style = textStyle(GlanceTheme.colors.onSurface, 13.sp, bold = true), maxLines = 1)
        Spacer(GlanceModifier.defaultWeight())
        QueueChip(ui.queue, short = false, action = ui.openConfig)
    }
    Spacer(GlanceModifier.height(6.dp))
    DayBar(Timeline.slots(Timeline.day(ui.snapshot, ui.today), ui.queue, Timeline.minuteOfDay(ui.now)), 20.dp)
    HourLabels()
}

/**
 * 4×3 and up: a bigger headline, then today and tomorrow with their outage times.
 */
@Composable
private fun ColumnScope.LargeLayout(ui: UiState) {
    // Scale up on tall widgets (4×4+) instead of leaving empty bands.
    val tall = LocalSize.current.height >= 320.dp
    Spacer(GlanceModifier.defaultWeight())
    StatusHeadline(ui, title = if (tall) 24.sp else 20.sp, time = if (tall) 52.sp else 40.sp)
    Spacer(GlanceModifier.defaultWeight())
    val hasTomorrow = Timeline.day(ui.snapshot, ui.tomorrow) != null
    val bar = if (tall) 26.dp else 18.dp
    DaySection(ui, ui.today, "Сьогодні", labels = !hasTomorrow, bar = bar)
    Spacer(GlanceModifier.height(if (tall) 16.dp else 10.dp))
    DaySection(ui, ui.tomorrow, "Завтра", labels = true, bar = bar)
}

@Composable
private fun DaySection(ui: UiState, date: LocalDate, label: String, labels: Boolean, bar: Dp) {
    val day = Timeline.day(ui.snapshot, date)
    val dateText = date.format(DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("uk")))
    // Own Column: Glance truncates a Row/Column after 10 children.
    Column(GlanceModifier.fillMaxWidth()) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = textStyle(GlanceTheme.colors.onSurface, 13.sp, bold = true), maxLines = 1)
            Spacer(GlanceModifier.width(6.dp))
            Text(dateText, style = textStyle(GlanceTheme.colors.onSurfaceVariant, 12.sp), maxLines = 1)
            Spacer(GlanceModifier.defaultWeight())
            // The bar shows the outage times; text only when there's no bar to read.
            statusText(day)?.let {
                Text(it, style = textStyle(GlanceTheme.colors.onSurfaceVariant, 12.sp, bold = true), maxLines = 1)
            }
        }
        if (day != null && day.queues.isNotEmpty()) {
            Spacer(GlanceModifier.height(5.dp))
            val nowMin = if (date == ui.today) Timeline.minuteOfDay(ui.now) else null
            DayBar(Timeline.slots(day, ui.queue, nowMin), bar)
            if (labels) HourLabels()
        }
    }
}

@Composable
internal fun RefreshButton(size: androidx.compose.ui.unit.Dp = 32.dp, error: Boolean = false) {
    // Turns red when the last refresh failed (data shown is stale).
    IconPill(
        size = size,
        icon = ua.blackout.cherkasy.R.drawable.ic_refresh,
        background = if (error) GlanceTheme.colors.tertiaryContainer else GlanceTheme.colors.secondaryContainer,
        tint = if (error) GlanceTheme.colors.onTertiaryContainer else GlanceTheme.colors.onSecondaryContainer,
        action = actionRunCallback<RefreshAction>(),
    )
}

/** Why a day has no bar: not published yet, or the post couldn't be parsed. */
internal fun statusText(day: ua.blackout.cherkasy.data.DaySchedule?): String? = when {
    day == null -> "ще не опубліковано"
    day.queues.isEmpty() -> "Не вдалося розібрати — торкніться"
    else -> null
}
