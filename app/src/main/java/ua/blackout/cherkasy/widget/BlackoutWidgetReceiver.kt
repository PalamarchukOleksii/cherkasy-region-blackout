package ua.blackout.cherkasy.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import kotlinx.coroutines.flow.MutableStateFlow
import ua.blackout.cherkasy.work.RefreshWorker
import ua.blackout.cherkasy.work.TickScheduler

class BlackoutWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = BlackoutWidget()

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        RefreshWorker.schedulePeriodic(context)
        TickScheduler.schedule(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        RefreshWorker.cancel(context)
        TickScheduler.cancel(context)
    }
}

class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        RefreshWorker.refreshNow(context)
    }
}

/** Bumped by [TickScheduler] so live Glance sessions recompose with the new "now". */
object WidgetClock {
    val tick = MutableStateFlow(0L)
}

object QueuePrefs {
    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun default(context: Context): String = prefs(context).getString("queue", null) ?: "1.1"

    fun setDefault(context: Context, queue: String) = prefs(context).edit().putString("queue", queue).apply()
}
