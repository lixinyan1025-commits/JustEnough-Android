package com.justenough.planner.widget

import android.content.Context
import android.appwidget.AppWidgetManager
import android.content.ComponentName

object WidgetUpdater {
    suspend fun update(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        manager.getAppWidgetIds(ComponentName(context, LargeTodayWidgetProvider::class.java)).forEach { LargeTodayWidgetProvider.update(context, manager, it) }
    }
}
