package com.justenough.planner.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.justenough.planner.data.AppSettings
import com.justenough.planner.data.PlannerDao
import com.justenough.planner.data.TimeBlockEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class ReminderScheduler(
    private val context: Context,
    private val dao: PlannerDao,
    private val settings: AppSettings,
) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val scheduleState = context.getSharedPreferences("reminder_schedule", Context.MODE_PRIVATE)
    private val snoozeState = context.getSharedPreferences("snooze_schedule", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    suspend fun rescheduleAll() = mutex.withLock {
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val blocks = dao.getBlocks().filter { it.enabled && it.reminderEnabled }
        val previousIds = scheduleState.getString(KEY_BLOCK_IDS, "").orEmpty().split(',').mapNotNull(String::toLongOrNull).toSet()
        previousIds.forEach(::cancelBlockAlarms)
        cancelLegacyAlarms(blocks, zone)
        blocks.forEach { block ->
            listOf(-30 to ReminderReceiver.STAGE_30_MIN,-5 to ReminderReceiver.STAGE_5_MIN).forEach{(offset,stage)->
                nextTrigger(block, block.startMinute+offset, now, zone)?.let{trigger->schedule(trigger.toEpochMilli(),stableCode(block.id,stage),Intent(context,ReminderReceiver::class.java).apply{action=ReminderReceiver.ACTION_BLOCK;putExtra(ReminderReceiver.EXTRA_BLOCK_ID,block.id);putExtra(ReminderReceiver.EXTRA_KIND,block.kind);putExtra(ReminderReceiver.EXTRA_STAGE,stage)})}
            }
            nextTrigger(block, block.startMinute, now, zone)?.let { trigger ->
                schedule(trigger.toEpochMilli(), stableCode(block.id,ReminderReceiver.STAGE_START), Intent(context, ReminderReceiver::class.java).apply {
                    action = ReminderReceiver.ACTION_BLOCK
                    putExtra(ReminderReceiver.EXTRA_BLOCK_ID, block.id)
                    putExtra(ReminderReceiver.EXTRA_KIND, block.kind)
                    putExtra(ReminderReceiver.EXTRA_STAGE,ReminderReceiver.STAGE_START)
                })
            }
            nextTrigger(block, block.endMinute, now, zone)?.let { trigger ->
                schedule(trigger.toEpochMilli(), stableCode(block.id,ReminderReceiver.STAGE_END), Intent(context, ReminderReceiver::class.java).apply {
                    action = ReminderReceiver.ACTION_REFRESH
                    putExtra(ReminderReceiver.EXTRA_BLOCK_ID, block.id)
                })
            }
        }
        scheduleState.edit().putString(KEY_BLOCK_IDS, blocks.joinToString(",") { it.id.toString() }).apply()
        val reviewMinute = settings.state.first().reviewMinute
        val todayReview = LocalDate.now(zone).atStartOfDay(zone).plusMinutes(reviewMinute.toLong()).toInstant()
        val nextReview = if (todayReview.isAfter(now)) todayReview else LocalDate.now(zone).plusDays(1).atStartOfDay(zone).plusMinutes(reviewMinute.toLong()).toInstant()
        schedule(
            nextReview.toEpochMilli(),
            requestCode = 900_001,
            Intent(context, ReminderReceiver::class.java).apply { action = ReminderReceiver.ACTION_REVIEW },
        )
        rescheduleSnoozes()
    }

    suspend fun scheduleSnooze(planId: Long, atMillis: Long) {
        snoozeState.edit().putLong("plan_$planId", atMillis).apply()
        val pending = PendingIntent.getBroadcast(
            context,
            snoozeCode(planId),
            Intent(context, ReminderReceiver::class.java).apply {
                action = ReminderReceiver.ACTION_SNOOZE
                putExtra(ReminderReceiver.EXTRA_PLAN_ID, planId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
        }
    }

    suspend fun cancelSnooze(planId: Long) {
        cancel(snoozeCode(planId), Intent(context, ReminderReceiver::class.java).apply { action = ReminderReceiver.ACTION_SNOOZE })
        snoozeState.edit().remove("plan_$planId").apply()
    }

    suspend fun pendingSnoozes(): Map<Long, Long> = snoozeState.all.entries.mapNotNull { (key, value) ->
        val id = key.removePrefix("plan_").toLongOrNull() ?: return@mapNotNull null
        id to (value as? Long ?: return@mapNotNull null)
    }.toMap()

    private suspend fun rescheduleSnoozes() {
        val now = System.currentTimeMillis()
        pendingSnoozes().forEach { (planId, atMillis) ->
            if (atMillis > now) scheduleSnooze(planId, atMillis) else cancelSnooze(planId)
        }
    }

    suspend fun scheduleTest(delayMillis: Long = 10_000) {
        val scheduledAt = System.currentTimeMillis() + delayMillis
        settings.update { it.copy(reminderTestScheduledAt = scheduledAt) }
        schedule(
            scheduledAt,
            900_002,
            Intent(context, ReminderReceiver::class.java).apply { action = ReminderReceiver.ACTION_TEST },
        )
        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<ReminderTestCheckWorker>()
                .setInitialDelay(delayMillis + 45_000, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build(),
        )
    }

    private fun schedule(atMillis: Long, requestCode: Int, intent: Intent) {
        val pending = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
        }
    }

    private fun occursOn(mask: Int, epochDay: Long?, date: LocalDate): Boolean {
        if (epochDay != null) return epochDay == date.toEpochDay()
        val bit = 1 shl (date.dayOfWeek.value - DayOfWeek.MONDAY.value)
        return mask and bit != 0
    }

    private fun nextTrigger(block: TimeBlockEntity, minute: Int, now: Instant, zone: ZoneId): Instant? {
        val today = LocalDate.now(zone)
        if (block.dateEpochDay != null) {
            val date = LocalDate.ofEpochDay(block.dateEpochDay)
            val trigger = date.atStartOfDay(zone).plusMinutes(minute.toLong()).toInstant()
            return trigger.takeIf { it.isAfter(now) }
        }
        return (0L..7L).asSequence()
            .map { today.plusDays(it) }
            .filter { occursOn(block.weekdayMask, null, it) }
            .map { it.atStartOfDay(zone).plusMinutes(minute.toLong()).toInstant() }
            .firstOrNull { it.isAfter(now) }
    }

    private fun cancelBlockAlarms(blockId: Long) {
        listOf(ReminderReceiver.STAGE_30_MIN,ReminderReceiver.STAGE_5_MIN,ReminderReceiver.STAGE_START).forEach{stage->cancel(stableCode(blockId,stage), Intent(context, ReminderReceiver::class.java).apply { action = ReminderReceiver.ACTION_BLOCK })}
        cancel(stableCode(blockId,ReminderReceiver.STAGE_END), Intent(context, ReminderReceiver::class.java).apply { action = ReminderReceiver.ACTION_REFRESH })
    }

    private fun cancelLegacyAlarms(blocks: List<TimeBlockEntity>, zone: ZoneId) {
        for (offset in 0..14) {
            val date = LocalDate.now(zone).plusDays(offset.toLong())
            blocks.forEach { block ->
                cancel(legacyCode(block.id, date), Intent(context, ReminderReceiver::class.java).apply { action = ReminderReceiver.ACTION_BLOCK })
                cancel(legacyCode(block.id + 1_000_000, date), Intent(context, ReminderReceiver::class.java).apply { action = ReminderReceiver.ACTION_REFRESH })
            }
        }
    }

    private fun cancel(requestCode: Int, intent: Intent) {
        PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let {
            alarmManager.cancel(it)
            it.cancel()
        }
    }

    private fun stableCode(blockId: Long,stage:Int): Int = (((blockId*7+stage) xor (blockId ushr 32)) and 0x3fffffff).toInt()
    private fun legacyCode(blockId: Long, date: LocalDate): Int = ((blockId * 31 + date.toEpochDay()) and 0x7fffffff).toInt()
    private fun snoozeCode(planId: Long): Int = (300_000 + (planId % 200_000)).toInt()

    companion object {
        private const val KEY_BLOCK_IDS = "block_ids"
    }
}
