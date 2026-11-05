package ua.blackout.cherkasy.widget

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.LocalSize
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import ua.blackout.cherkasy.R
import ua.blackout.cherkasy.domain.PowerStatus
import ua.blackout.cherkasy.domain.Slot
import ua.blackout.cherkasy.domain.Timeline

internal fun textStyle(color: ColorProvider, size: TextUnit, bold: Boolean = false) =
    TextStyle(color = color, fontSize = size, fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium)

private class StatusLook(
    val container: ColorProvider,
    val onContainer: ColorProvider,
    val badge: ColorProvider,
    val onBadge: ColorProvider,
    @DrawableRes val shape: Int,
    @DrawableRes val icon: Int,
)

@Composable
private fun lookFor(status: PowerStatus): StatusLook {
    val c = GlanceTheme.colors
    return when (status) {
        is PowerStatus.Off -> StatusLook(c.tertiaryContainer, c.onTertiaryContainer, c.tertiary, c.onTertiary, R.drawable.shape_clover4, R.drawable.ic_power_off)
        is PowerStatus.On -> StatusLook(c.primaryContainer, c.onPrimaryContainer, c.primary, c.onPrimary, R.drawable.shape_cookie9, R.drawable.ic_bolt)
        else -> StatusLook(c.secondaryContainer, c.onSecondaryContainer, c.secondary, c.onSecondary, R.drawable.shape_sunny8, R.drawable.ic_schedule)
    }
}

private class StatusText(val title: String, val label: String, val time: String?, val tomorrow: Boolean)

private fun textFor(status: PowerStatus) = when (status) {
    is PowerStatus.Off -> StatusText("Світла немає", "Увімкнуть о", Timeline.hhmm(status.until), Timeline.isTomorrow(status.until))
    is PowerStatus.On -> status.nextOff?.let { next ->
        StatusText("Світло є", "Відключення о", Timeline.hhmm(next), Timeline.isTomorrow(next))
    } ?: StatusText("Світло є", "Відключень не заплановано", null, false)
    PowerStatus.NotPublished -> StatusText("Немає графіка", "На сьогодні ще не опубліковано", null, false)
    PowerStatus.Unreadable -> StatusText("Графік незрозумілий", "Перевірте на сайті", null, false)
    PowerStatus.NoData -> StatusText("Немає даних", "Натисніть ⟳, щоб оновити", null, false)
}

/** The expressive hero: tinted shape + icon, status line and a big next-change time. */
@Composable
internal fun StatusCard(ui: UiState, compact: Boolean, modifier: GlanceModifier) {
    val look = lookFor(ui.status)
    val text = textFor(ui.status)
    val card = modifier.background(look.container).cornerRadius(if (compact) 24.dp else 28.dp)
    Column(card.padding(10.dp)) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Spacer(GlanceModifier.defaultWeight())
            QueueChip(ui.queue, short = true, action = ui.openConfig, onCard = true)
        }
        Spacer(GlanceModifier.defaultWeight())
        Text(text.title, style = textStyle(look.onContainer, 14.sp, bold = true), maxLines = 1)
        Text(text.label, style = textStyle(look.onContainer, 11.sp), maxLines = if (text.time == null) 2 else 1)
        if (text.time != null) TimeLine(text, look.onContainer, 24.sp)
    }
}

/** 4-wide headline: sits directly on the widget surface; status color only on the time. */
@Composable
internal fun StatusHeadline(ui: UiState, title: TextUnit, time: TextUnit) {
    val look = lookFor(ui.status)
    val text = textFor(ui.status)
    val accent = if (ui.status is PowerStatus.Off) GlanceTheme.colors.tertiary else GlanceTheme.colors.primary
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(GlanceModifier.defaultWeight()) {
            Text(text.title, style = textStyle(GlanceTheme.colors.onSurface, title, bold = true), maxLines = 1)
            Text(text.label, style = textStyle(GlanceTheme.colors.onSurfaceVariant, 12.sp), maxLines = 2)
        }
        if (text.time != null) {
            Spacer(GlanceModifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(text.time, style = textStyle(accent, time, bold = true), maxLines = 1)
                if (text.tomorrow) Text("завтра", style = textStyle(accent, 12.sp, bold = true), maxLines = 1)
            }
        }
    }
}

@Composable
private fun TimeLine(text: StatusText, color: ColorProvider, size: TextUnit) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(text.time!!, style = textStyle(color, size, bold = true), maxLines = 1)
        if (text.tomorrow) {
            Spacer(GlanceModifier.width(4.dp))
            Text("завтра", style = textStyle(color, 12.sp), modifier = GlanceModifier.padding(bottom = 5.dp))
        }
    }
}

@Composable
private fun ShapeBadge(look: StatusLook, size: Dp) {
    Box(GlanceModifier.size(size), contentAlignment = Alignment.Center) {
        Image(ImageProvider(look.shape), null, GlanceModifier.fillMaxSize(), colorFilter = ColorFilter.tint(look.badge))
        Image(ImageProvider(look.icon), null, GlanceModifier.size(size * 0.5f), colorFilter = ColorFilter.tint(look.onBadge))
    }
}

@Composable
internal fun QueueChip(queue: String, short: Boolean, action: Action, onCard: Boolean = false) {
    Box(
        GlanceModifier
            .background(if (onCard) GlanceTheme.colors.surface else GlanceTheme.colors.secondaryContainer)
            .cornerRadius(100.dp)
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .clickable(action),
    ) {
        Text(if (short) queue else "Черга $queue", style = textStyle(if (onCard) GlanceTheme.colors.onSurface else GlanceTheme.colors.onSecondaryContainer, 12.sp, bold = true))
    }
}

@Composable
internal fun IconPill(
    @DrawableRes icon: Int,
    background: ColorProvider,
    tint: ColorProvider,
    action: Action,
    size: Dp = 32.dp,
    description: String = "Оновити",
) {
    Box(
        GlanceModifier.size(size).background(background).cornerRadius(size / 2).clickable(action),
        contentAlignment = Alignment.Center,
    ) {
        Image(ImageProvider(icon), description, GlanceModifier.size(size * 0.56f), colorFilter = ColorFilter.tint(tint))
    }
}

/**
 * 24 rounded hour segments, each split into two half-hour halves. Glance rows hold at most 10
 * children, so hours are grouped 4 × 6.
 */
@Composable
internal fun DayBar(slots: List<Slot>, height: Dp) {
    val nowHour = slots.indexOfFirst { it == Slot.NOW_ON || it == Slot.NOW_OFF }.takeIf { it >= 0 }?.div(2)
    Row(GlanceModifier.fillMaxWidth().height(height)) {
        for (group in 0 until 4) {
            Row(GlanceModifier.defaultWeight().fillMaxHeight()) {
                for (h in 0 until 6) {
                    val hour = group * 6 + h
                    val isNow = hour == nowHour
                    // The current hour is a full-height pill; the rest are inset so it pokes out.
                    val inset = if (isNow) 0.dp else height * 0.22f
                    Box(GlanceModifier.defaultWeight().fillMaxHeight().padding(horizontal = 1.dp, vertical = inset)) {
                        Row(GlanceModifier.fillMaxSize().cornerRadius(if (isNow) 6.dp else 4.dp)) {
                            SlotCell(slots[hour * 2], isNow)
                            SlotCell(slots[hour * 2 + 1], isNow)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun androidx.glance.layout.RowScope.SlotCell(slot: Slot, inCurrentHour: Boolean) {
    val c = GlanceTheme.colors
    val off = slot == Slot.OFF || slot == Slot.PAST_OFF || slot == Slot.NOW_OFF
    val color = when {
        inCurrentHour -> if (off) c.tertiary else c.primary
        slot == Slot.ON -> c.secondaryContainer
        slot == Slot.PAST_ON -> c.surfaceVariant
        slot == Slot.OFF -> c.tertiary
        else -> c.tertiaryContainer // PAST_OFF
    }
    Box(GlanceModifier.defaultWeight().fillMaxHeight().background(color)) {}
}

@Composable
internal fun HourLabels() {
    Row(GlanceModifier.fillMaxWidth().padding(top = 2.dp)) {
        for (h in listOf("00", "03", "06", "09", "12", "15", "18", "21")) {
            Text(h, style = textStyle(GlanceTheme.colors.onSurfaceVariant, 10.sp), modifier = GlanceModifier.defaultWeight())
        }
    }
}
