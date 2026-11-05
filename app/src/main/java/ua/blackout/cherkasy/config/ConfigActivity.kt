package ua.blackout.cherkasy.config

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.state.PreferencesGlanceStateDefinition
import kotlinx.coroutines.launch
import ua.blackout.cherkasy.R
import ua.blackout.cherkasy.data.KYIV
import ua.blackout.cherkasy.data.QUEUES
import ua.blackout.cherkasy.data.ScheduleParser
import ua.blackout.cherkasy.data.ScheduleRepository
import ua.blackout.cherkasy.data.Snapshot
import ua.blackout.cherkasy.domain.PowerStatus
import ua.blackout.cherkasy.domain.Timeline
import ua.blackout.cherkasy.widget.BlackoutWidget
import ua.blackout.cherkasy.widget.BlackoutWidgetReceiver
import ua.blackout.cherkasy.widget.FallbackDark
import ua.blackout.cherkasy.widget.FallbackLight
import ua.blackout.cherkasy.widget.QUEUE_KEY
import ua.blackout.cherkasy.widget.QueuePrefs
import ua.blackout.cherkasy.work.RefreshWorker
import ua.blackout.cherkasy.work.TickScheduler
import androidx.lifecycle.lifecycleScope
import java.time.ZonedDateTime

/**
 * Widget configuration (launched with EXTRA_APPWIDGET_ID) and launcher screen. In launcher mode the
 * chosen queue becomes the default and is applied to every placed widget.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
class ConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val widgetMode = widgetId != AppWidgetManager.INVALID_APPWIDGET_ID
        if (widgetMode) setResult(RESULT_CANCELED, resultIntent(widgetId))
        // Opening the app revives widgets left on the loading layout after an update or force-stop
        // (some launchers, e.g. MIUI, don't redeliver APPWIDGET_UPDATE to a stopped app).
        lifecycleScope.launch {
            BlackoutWidget().updateAll(this@ConfigActivity)
            RefreshWorker.schedulePeriodic(this@ConfigActivity)
            TickScheduler.schedule(this@ConfigActivity)
        }

        setContent {
            AppTheme {
                val scope = rememberCoroutineScope()
                var queue by remember { mutableStateOf(QueuePrefs.default(this)) }
                LaunchedEffect(widgetId) {
                    if (widgetMode) runCatching {
                        val glanceId = GlanceAppWidgetManager(this@ConfigActivity).getGlanceIdBy(widgetId)
                        getAppWidgetState(this@ConfigActivity, PreferencesGlanceStateDefinition, glanceId)[QUEUE_KEY]
                    }.getOrNull()?.let { queue = it }
                }
                ConfigScreen(
                    queue = queue,
                    onQueue = { queue = it },
                    widgetMode = widgetMode,
                    onApply = { scope.launch { apply(queue, widgetId, widgetMode) } },
                    onPin = { scope.launch { pin(queue) } },
                )
            }
        }
    }

    private fun resultIntent(widgetId: Int) = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)

    private suspend fun apply(queue: String, widgetId: Int, widgetMode: Boolean) {
        QueuePrefs.setDefault(this, queue)
        val manager = GlanceAppWidgetManager(this)
        val ids = if (widgetMode) listOf(manager.getGlanceIdBy(widgetId))
        else manager.getGlanceIds(BlackoutWidget::class.java)
        for (id in ids) {
            updateAppWidgetState(this, id) { it[QUEUE_KEY] = queue }
            BlackoutWidget().update(this, id)
        }
        RefreshWorker.schedulePeriodic(this)
        if (widgetMode) {
            setResult(RESULT_OK, resultIntent(widgetId))
            finish()
        } else {
            val msg = if (ids.isEmpty()) "Черга $queue збережена" else "Віджети оновлено: черга $queue"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun pin(queue: String) {
        QueuePrefs.setDefault(this, queue) // new widgets start on the default queue
        val ok = GlanceAppWidgetManager(this).requestPinGlanceAppWidget(BlackoutWidgetReceiver::class.java)
        if (!ok) Toast.makeText(this, "Лаунчер не підтримує додавання віджетів з додатка", Toast.LENGTH_LONG).show()
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> FallbackDark
        else -> FallbackLight
    }
    MaterialExpressiveTheme(colorScheme = colors, motionScheme = MotionScheme.expressive(), content = content)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ConfigScreen(
    queue: String,
    onQueue: (String) -> Unit,
    widgetMode: Boolean,
    onApply: () -> Unit,
    onPin: () -> Unit,
) {
    val context = LocalContext.current
    val snapshot by ScheduleRepository.snapshot(context).collectAsState()
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val stale = snapshot?.let { System.currentTimeMillis() - it.fetchedAt > 15 * 60_000 } ?: true
        if (stale) {
            refreshing = true
            ScheduleRepository.refresh(context)
            BlackoutWidget().updateAll(context)
            refreshing = false
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Text(
                "Cherkasy Region Blackout",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Black,
            )
            Text(
                "Погодинні відключення, Черкасиобленерго",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            StatusPreview(snapshot, queue, refreshing)
            Spacer(Modifier.height(24.dp))
            Text("Ваша черга", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            QueuePicker(queue, onQueue)
            Spacer(Modifier.height(20.dp))
            DayRanges(snapshot, queue)
            Spacer(Modifier.height(28.dp))
            Button(
                onClick = onApply,
                modifier = Modifier.fillMaxWidth().heightIn(min = ButtonDefaults.MediumContainerHeight),
                shapes = ButtonDefaults.shapesFor(ButtonDefaults.MediumContainerHeight),
                contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
            ) {
                Text(
                    if (widgetMode) "Зберегти" else "Застосувати",
                    style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight),
                )
            }
            if (!widgetMode && AppWidgetManager.getInstance(context).isRequestPinAppWidgetSupported) {
                Spacer(Modifier.height(10.dp))
                FilledTonalButton(
                    onClick = onPin,
                    modifier = Modifier.fillMaxWidth().heightIn(min = ButtonDefaults.MediumContainerHeight),
                    shapes = ButtonDefaults.shapesFor(ButtonDefaults.MediumContainerHeight),
                    contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
                ) {
                    Text("Додати віджет на екран", style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight))
                }
            }
            Spacer(Modifier.height(8.dp))
            // The widget opens this screen; the original post is one tap away from here.
            TextButton(
                onClick = {
                    val today = ZonedDateTime.now(KYIV).toLocalDate()
                    val url = Timeline.day(snapshot, today)?.sourceUrl ?: ScheduleParser.LIST_URL
                    openLink(context, url)
                },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) { Text("Графік на сайті Черкасиобленерго") }
            TextButton(
                onClick = {
                    openLink(context, "https://www.cherkasyoblenergo.com/off")
                },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) { Text("Не знаєте чергу? Пошук за адресою") }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StatusPreview(snapshot: Snapshot?, queue: String, refreshing: Boolean) {
    val status = Timeline.status(snapshot, queue, ZonedDateTime.now(KYIV))
    val cs = MaterialTheme.colorScheme
    val (container, onContainer, badge, onBadge) = when (status) {
        is PowerStatus.Off -> listOf(cs.tertiaryContainer, cs.onTertiaryContainer, cs.tertiary, cs.onTertiary)
        is PowerStatus.On -> listOf(cs.primaryContainer, cs.onPrimaryContainer, cs.primary, cs.onPrimary)
        else -> listOf(cs.secondaryContainer, cs.onSecondaryContainer, cs.secondary, cs.onSecondary)
    }
    Surface(color = container, contentColor = onContainer, shape = RoundedCornerShape(32.dp)) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(targetState = status::class to refreshing, label = "badge") { (kind, busy) ->
                val shape = when (kind) {
                    PowerStatus.Off::class -> MaterialShapes.Clover4Leaf
                    PowerStatus.On::class -> MaterialShapes.Cookie9Sided
                    else -> MaterialShapes.Sunny
                }.toShape()
                Box(Modifier.size(72.dp).background(badge, shape), contentAlignment = Alignment.Center) {
                    if (busy) LoadingIndicator(Modifier.size(48.dp), color = onBadge)
                    else Icon(
                        painterResource(
                            when (kind) {
                                PowerStatus.Off::class -> R.drawable.ic_power_off
                                PowerStatus.On::class -> R.drawable.ic_bolt
                                else -> R.drawable.ic_schedule
                            },
                        ),
                        contentDescription = null,
                        tint = onBadge,
                        modifier = Modifier.size(36.dp),
                    )
                }
            }
            Spacer(Modifier.width(18.dp))
            Column {
                val (title, detail) = when (status) {
                    is PowerStatus.Off -> "Світла немає" to "Увімкнуть о ${timeText(status.until)}"
                    is PowerStatus.On -> "Світло є" to (status.nextOff?.let { "Відключення о ${timeText(it)}" } ?: "Відключень не заплановано")
                    PowerStatus.NotPublished -> "Немає графіка" to "На сьогодні ще не опубліковано"
                    PowerStatus.Unreadable -> "Графік незрозумілий" to "Не вдалося розібрати — перевірте на сайті"
                    PowerStatus.NoData -> "Немає даних" to (snapshot?.lastError?.let { "Помилка: $it" } ?: "Завантаження…")
                }
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(detail, style = MaterialTheme.typography.titleMedium)
                Text("Черга $queue", style = MaterialTheme.typography.labelLarge, color = onContainer.copy(alpha = 0.75f))
            }
        }
    }
}

/** No browser installed (or disabled) must not crash the app. */
private fun openLink(context: android.content.Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: android.content.ActivityNotFoundException) {
        Toast.makeText(context, "Немає браузера, щоб відкрити посилання", Toast.LENGTH_LONG).show()
    }
}

private fun timeText(min: Int) = Timeline.hhmm(min) + if (Timeline.isTomorrow(min)) " завтра" else ""

/** 12 queues as 6 connected pairs (N.1 | N.2), two pairs per row. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun QueuePicker(selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        QUEUES.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.chunked(2).forEach { pair ->
                    Row(
                        Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                    ) {
                        pair.forEachIndexed { i, q ->
                            ToggleButton(
                                checked = q == selected,
                                onCheckedChange = { onSelect(q) },
                                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                                shapes = if (i == 0) ButtonGroupDefaults.connectedLeadingButtonShapes()
                                else ButtonGroupDefaults.connectedTrailingButtonShapes(),
                            ) {
                                Text(q, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayRanges(snapshot: Snapshot?, queue: String) {
    val today = ZonedDateTime.now(KYIV).toLocalDate()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((label, date) in listOf("Сьогодні" to today, "Завтра" to today.plusDays(1))) {
            val day = Timeline.day(snapshot, date)
            Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val ranges = day?.queues?.get(queue)
            when {
                day == null -> Text("Графік ще не опубліковано", style = MaterialTheme.typography.bodyMedium)
                ranges.isNullOrEmpty() -> RangeChip("Без відключень", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                else -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ranges.forEach {
                        RangeChip(
                            "${Timeline.hhmm(it.start)}–${Timeline.hhmm(it.end)}",
                            MaterialTheme.colorScheme.tertiaryContainer,
                            MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RangeChip(text: String, bg: Color, fg: Color) {
    Surface(color = bg, contentColor = fg, shape = RoundedCornerShape(50)) {
        Text(
            text,
            Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}
