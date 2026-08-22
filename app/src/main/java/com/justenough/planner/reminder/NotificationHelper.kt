package com.justenough.planner.reminder

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.justenough.planner.MainActivity
import com.justenough.planner.R
import com.justenough.planner.data.ActiveRunDetails
import com.justenough.planner.data.BlockWithPlans
import com.justenough.planner.data.PlanEntity
import com.justenough.planner.data.PlanKinds
import com.justenough.planner.task.TaskActionReceiver
import com.justenough.planner.ui.TaskConfirmActivity
import com.justenough.planner.ui.TaskFinishActivity
import com.justenough.planner.ui.TaskReminderActivity
import com.justenough.planner.ui.ScheduledTaskStartActivity

object NotificationHelper {
    const val CHANNEL_CHOICE = "choice_reminders_v4"; const val CHANNEL_ANCHOR = "anchor_reminders_v4"; const val CHANNEL_ACTIVE = "active_task"; const val CHANNEL_AI = "ai_suggestions"
    const val ACTIVE_NOTIFICATION_ID = 31_001

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(listOf(
            NotificationChannel(CHANNEL_CHOICE, "计划提醒", NotificationManager.IMPORTANCE_DEFAULT).apply { setSound(null, null) },
            NotificationChannel(CHANNEL_ANCHOR, "固定任务提醒", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null); enableVibration(true); lockscreenVisibility = Notification.VISIBILITY_PUBLIC },
            NotificationChannel(CHANNEL_ACTIVE, "进行中的任务", NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(CHANNEL_AI, "AI新建议", NotificationManager.IMPORTANCE_LOW).apply { lockscreenVisibility = Notification.VISIBILITY_PRIVATE },
        ))
    }

    @SuppressLint("MissingPermission") fun showUpcoming(context: Context, value: BlockWithPlans, minutes: Int) {
        if (!allowed(context)) return
        val open = PendingIntent.getActivity(context, (90_000 + value.block.id + minutes).toInt(), Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val names = value.plans.take(3).joinToString(" · ") { it.name }.ifBlank { value.block.name }
        NotificationManagerCompat.from(context).notify(
            (90_000 + value.block.id + minutes).toInt(),
            NotificationCompat.Builder(context, CHANNEL_CHOICE).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("还有${minutes}分钟开始")
                .setContentText(names).setStyle(NotificationCompat.BigTextStyle().bigText(names))
                .setContentIntent(open).setAutoCancel(true).build(),
        )
    }

    @SuppressLint("MissingPermission") fun showBlock(context: Context, value: BlockWithPlans) {
        if (!allowed(context)) return
        val anchor = value.block.kind == PlanKinds.ANCHOR
        val open = PendingIntent.getActivity(context, value.block.id.toInt(), Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val body = value.plans.take(3).joinToString(" · ") { it.name }.ifBlank { "打开今天的计划" }
        val builder = NotificationCompat.Builder(context, if (anchor) CHANNEL_ANCHOR else CHANNEL_CHOICE).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (anchor) "${value.block.name}到了" else value.block.name).setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(if (anchor) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT).setAutoCancel(true).setContentIntent(open)
        value.plans.take(3).forEach { plan ->
            val start = PendingIntent.getActivity(context, plan.id.toInt(), Intent(context, ScheduledTaskStartActivity::class.java).putExtra("plan_id", plan.id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(0, if(anchor) "开始并打开" else "开始 ${plan.name.take(8)}", start)
        }
        NotificationManagerCompat.from(context).notify((10_000 + value.block.id).toInt(), builder.build())
    }

    @SuppressLint("MissingPermission") fun showTaskReminder(context: Context, values: List<BlockWithPlans>, autoStarted: Boolean, plan: PlanEntity? = null, planStarted: Boolean = false) {
        if (!allowed(context)) return
        val names = values.flatMap { it.plans }.map { it.name }.distinct().take(3)
        val title = if (autoStarted || planStarted) "任务已自动开始" else "任务到点了"
        val text = buildString {
            if (plan != null) append(plan.name)
            else if (names.isNotEmpty()) append(names.joinToString("、"))
            else append("有安排到点了")
            append(" · 点击进入查看目标和绑定应用")
        }
        val closeRing = PendingIntent.getBroadcast(context, 71_101, Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_STOP_RING), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val enter = PendingIntent.getActivity(context, 71_102, Intent(context, TaskReminderActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(context, CHANNEL_ANCHOR)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setContentIntent(enter)
            .addAction(0, "关闭铃声", closeRing)
            .addAction(0, "进入任务", enter)
        values.flatMap { it.plans }.take(3).forEach { item ->
            val start = PendingIntent.getActivity(context, item.id.toInt(), Intent(context, ScheduledTaskStartActivity::class.java).putExtra("plan_id", item.id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(0, "开始 ${item.name.take(6)}", start)
        }
        NotificationManagerCompat.from(context).notify(71_000, builder.build())
    }

    @SuppressLint("MissingPermission") fun showTaskDescriptionFallback(context: Context, plan: PlanEntity) {
        if (!allowed(context)) return
        val text = "现在开始：${plan.name} · 目标：${plan.minimumGoal}"
        NotificationManagerCompat.from(context).notify(
            71_200 + (plan.id and 0xff).toInt(),
            NotificationCompat.Builder(context, CHANNEL_ANCHOR).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("任务已开始")
                .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true).build(),
        )
    }

    @SuppressLint("MissingPermission") fun showReview(context: Context, count: Int) {
        if (!allowed(context)) return
        val open = PendingIntent.getActivity(context, 50_001, Intent(context, MainActivity::class.java).putExtra("screen", "review"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        NotificationManagerCompat.from(context).notify(50_001, NotificationCompat.Builder(context, CHANNEL_CHOICE).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("用一分钟收好今天").setContentText(if (count > 0) "$count 项安排等待处理" else "看看今天做到哪里已经足够").setAutoCancel(true).setContentIntent(open).build())
    }

    @SuppressLint("MissingPermission") fun showTest(context: Context) { if (allowed(context)) NotificationManagerCompat.from(context).notify(50_002, NotificationCompat.Builder(context, CHANNEL_ANCHOR).setSmallIcon(R.drawable.ic_notification).setContentTitle("测试成功").setContentText("刚刚好可以在后台提醒你").setAutoCancel(true).build()) }

    @SuppressLint("MissingPermission") fun showActive(context: Context, run: ActiveRunDetails) {
        if (!allowed(context)) return
        val toggle = PendingIntent.getBroadcast(context, 60_001, Intent(context, TaskActionReceiver::class.java).setAction(TaskActionReceiver.ACTION_TOGGLE).putExtra("run_id", run.runId), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val extend = PendingIntent.getBroadcast(context, 60_004, Intent(context, TaskActionReceiver::class.java).setAction(TaskActionReceiver.ACTION_EXTEND).putExtra("run_id", run.runId), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val finish = PendingIntent.getActivity(context, 60_002, Intent(context, TaskFinishActivity::class.java).putExtra("run_id", run.runId), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        NotificationManagerCompat.from(context).notify(ACTIVE_NOTIFICATION_ID, NotificationCompat.Builder(context, CHANNEL_ACTIVE).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("正在进行：${run.name}").setContentText("${run.minimumGoal} · 预计${run.plannedMinutes}分钟").setOngoing(true).setOnlyAlertOnce(true)
            .addAction(0, if (run.status == "PAUSED") "继续" else "暂停", toggle).addAction(0, "延长", extend).addAction(0, "完成", finish).build())
    }

    @SuppressLint("MissingPermission") fun showAiSuggestion(context: Context) { if (allowed(context)) NotificationManagerCompat.from(context).notify(70_001, NotificationCompat.Builder(context, CHANNEL_AI).setSmallIcon(R.drawable.ic_notification).setContentTitle("有新建议").setContentIntent(PendingIntent.getActivity(context, 70_001, Intent(context, MainActivity::class.java).putExtra("screen", "ai"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).setAutoCancel(true).build()) }
    @SuppressLint("MissingPermission") fun showWidgetFallback(context: Context, planId: Long) {
        if (!allowed(context)) return
        val open = PendingIntent.getActivity(context, (planId and 0x7fffffff).toInt(), Intent(context, TaskConfirmActivity::class.java).putExtra("plan_id", planId), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        NotificationManagerCompat.from(context).notify(80_000 + (planId and 0xfff).toInt(), NotificationCompat.Builder(context, CHANNEL_CHOICE).setSmallIcon(R.drawable.ic_notification).setContentTitle("点此开始任务").setContentText("桌面阻止了直接打开，请从这条通知继续").setContentIntent(open).setAutoCancel(true).build())
    }
    fun cancelActive(context: Context) = NotificationManagerCompat.from(context).cancel(ACTIVE_NOTIFICATION_ID)
    fun cancelReminder(context: Context) = NotificationManagerCompat.from(context).cancel(71_000)
    private fun allowed(context: Context) = android.os.Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}
