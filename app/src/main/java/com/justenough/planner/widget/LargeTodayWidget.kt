package com.justenough.planner.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.justenough.planner.R
import com.justenough.planner.appContainer
import com.justenough.planner.data.TodaySnapshot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class LargeTodayWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) =
        ids.forEach { update(context, manager, it) }

    companion object {
        fun update(context: Context, manager: AppWidgetManager, id: Int) {
            val snapshot = runBlocking { context.appContainer.repository.todaySnapshot() }
            val settings = runBlocking { context.appContainer.settings.state.first() }
            val colors = widgetColors(context, settings.widgetTextMode, settings.widgetContrast)
            val scale = settings.widgetFontScale.coerceIn(.85f, 1.3f)
            val views = RemoteViews(context.packageName, R.layout.widget_large)
            views.setInt(R.id.widget_root, "setBackgroundResource", if (settings.subtleBacking) R.drawable.widget_subtle_backing else android.R.color.transparent)
            views.setTextViewText(R.id.widget_fulfillment, fulfillment(snapshot.fulfillmentTotal))
            views.setTextColor(R.id.widget_fulfillment, colors.first)
            views.setTextColor(R.id.widget_empty, colors.second)
            views.setTextViewTextSize(R.id.widget_fulfillment, android.util.TypedValue.COMPLEX_UNIT_SP, 18f * scale)
            views.setTextViewTextSize(R.id.widget_empty, android.util.TypedValue.COMPLEX_UNIT_SP, 14f * scale)
            views.setRemoteAdapter(R.id.widget_schedule, Intent(context, LargeWidgetListService::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .setData(android.net.Uri.parse("justenough://widget/$id/${System.currentTimeMillis()}")))
            views.setPendingIntentTemplate(R.id.widget_schedule, PendingIntent.getActivity(
                context, 900_100 + id, Intent(context, WidgetTaskActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            ))
            views.setEmptyView(R.id.widget_schedule, R.id.widget_empty)
            manager.updateAppWidget(id, views)
            manager.notifyAppWidgetViewDataChanged(id, R.id.widget_schedule)
        }

        private fun fulfillment(total: Int) = (0 until 5).joinToString("  ") { if (it < total) "■" else "□" } + if (total > 5) "   +${total - 5}" else ""
        private fun widgetColors(context: Context, mode: String, contrast: Float): Pair<Int, Int> {
            val darkSystem = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            val dark = mode == "DARK" || (mode == "SYSTEM" && !darkSystem)
            val alpha = (255 * contrast.coerceIn(.55f, 1f)).toInt()
            val channel = if (dark) 0 else 255
            return Color.argb(alpha, channel, channel, channel) to Color.argb((alpha * .72f).toInt(), channel, channel, channel)
        }
    }
}

class LargeWidgetListService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(applicationContext)

    private data class Row(val start: Int, val end: Int, val blockName: String, val planId: Long?, val planName: String?, val completed: Boolean, val activeRunId: Long?)

    private class Factory(private val context: Context) : RemoteViewsFactory {
        private var snapshot: TodaySnapshot? = null
        private var mainColor = Color.WHITE
        private var mutedColor = 0xBFFFFFFF.toInt()
        private var fontScale = 1f

        override fun onCreate() = Unit
        override fun onDataSetChanged() {
            snapshot = runBlocking { context.appContainer.repository.todaySnapshot() }
            val settings = runBlocking { context.appContainer.settings.state.first() }
            val darkSystem = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            val dark = settings.widgetTextMode == "DARK" || (settings.widgetTextMode == "SYSTEM" && !darkSystem)
            val alpha = (255 * settings.widgetContrast.coerceIn(.55f, 1f)).toInt()
            val channel = if (dark) 0 else 255
            mainColor = Color.argb(alpha, channel, channel, channel)
            mutedColor = Color.argb((alpha * .72f).toInt(), channel, channel, channel)
            fontScale = settings.widgetFontScale.coerceIn(.85f, 1.3f)
        }
        override fun onDestroy() = Unit

        private fun rows(): List<Row> = snapshot?.todayBlocks.orEmpty().flatMap { group ->
            if (group.plans.isEmpty()) listOf(Row(group.block.startMinute, group.block.endMinute, group.block.name, null, null, false, null))
            else group.plans.map { plan -> Row(
                group.block.startMinute, group.block.endMinute, group.block.name, plan.id, plan.name,
                plan.id in snapshot!!.completedPlanIds,
                snapshot!!.activeRun?.takeIf { it.planId == plan.id }?.runId,
            ) }
        }

        override fun getCount() = rows().size
        override fun getViewAt(position: Int): RemoteViews? {
            val row = rows().getOrNull(position) ?: return null
            val time = "%02d:%02d–%02d:%02d".format(row.start / 60, row.start % 60, row.end / 60, row.end % 60)
            val status = when { row.completed -> "✓"; row.activeRunId != null -> "◉"; else -> "○" }
            val label = if (row.planName == null) "$time   ${row.blockName}" else "$time   $status ${row.planName}"
            return RemoteViews(context.packageName, R.layout.widget_schedule_row).apply {
                setTextViewText(R.id.widget_schedule_text, label)
                setTextColor(R.id.widget_schedule_text, if (row.completed || row.planName == null) mutedColor else mainColor)
                setTextViewTextSize(R.id.widget_schedule_text, android.util.TypedValue.COMPLEX_UNIT_SP, 14f * fontScale)
                if (!row.completed && row.planId != null) {
                    setOnClickFillInIntent(R.id.widget_schedule_text, Intent().putExtra("plan_id", row.planId).apply {
                        row.activeRunId?.let { putExtra("run_id", it) }
                    })
                }
            }
        }
        override fun getLoadingView(): RemoteViews = RemoteViews(context.packageName, R.layout.widget_loading)
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int) = rows().getOrNull(position)?.let { (it.planId ?: -it.start.toLong()) * 31 + it.start } ?: position.toLong()
        override fun hasStableIds() = true
    }
}
