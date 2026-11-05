package ua.blackout.cherkasy.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ua.blackout.cherkasy.data.KYIV
import ua.blackout.cherkasy.data.ScheduleRepository
import ua.blackout.cherkasy.domain.Timeline
import ua.blackout.cherkasy.widget.BlackoutWidget
import ua.blackout.cherkasy.widget.WidgetClock
import java.time.ZonedDateTime

/**
 * Re-renders widgets at the next on/off boundary or half-hour mark. Uses an inexact, non-wakeup
 * alarm: if the screen is off nobody is looking, and it fires as soon as the device wakes.
 */
object TickScheduler {
    private fun pendingIntent(context: Context) = PendingIntent.getBroadcast(
        context, 0, Intent(context, TickReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun schedule(context: Context) {
        val snapshot = ScheduleRepository.snapshot(context).value
        val at = Timeline.nextTick(snapshot, ZonedDateTime.now(KYIV)).toInstant().toEpochMilli()
        context.getSystemService(AlarmManager::class.java)
            .setWindow(AlarmManager.RTC, at, 60_000, pendingIntent(context))
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context))
    }
}

class TickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                WidgetClock.tick.value = System.currentTimeMillis()
                BlackoutWidget().updateAll(context)
                TickScheduler.schedule(context)
            } finally {
                pending.finish()
            }
        }
    }
}
