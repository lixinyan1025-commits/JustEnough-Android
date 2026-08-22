package com.justenough.planner.task

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.justenough.planner.MainActivity
import com.justenough.planner.R
import com.justenough.planner.data.SettingsState
import com.justenough.planner.ui.TaskReminderActivity

/**
 * 闹钟式任务开始铃声：循环播放直到用户关闭、进入任务或达到最长响铃时长。
 * 关闭铃声只负责静音，不会结束任务计时。
 */
class RingtoneAlarmService : Service() {
    private lateinit var notificationManager: NotificationManager
    private val handler = Handler(Looper.getMainLooper())
    private var mediaPlayer: MediaPlayer? = null
    private var toneGenerator: ToneGenerator? = null
    private var vibrator: Vibrator? = null
    private var running = false
    private val chime = object : Runnable {
        override fun run() {
            if (!running) return
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 240)
            handler.postDelayed({ toneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, 320) }, 300)
            handler.postDelayed(this, 1200)
        }
    }
    private val stopNow = Runnable(::stopAlarm)

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            android.app.NotificationChannel(CHANNEL, "任务开始铃声", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopAlarm()
                return START_NOT_STICKY
            }
        }
        if (running) return START_NOT_STICKY
        running = true
        startForeground(NOTIFICATION_ID, buildNotification())
        val source = intent?.getStringExtra(EXTRA_SOURCE) ?: "BUILTIN"
        val uri = intent?.getStringExtra(EXTRA_URI)
        val durationSeconds = (intent?.getIntExtra(EXTRA_DURATION, 120) ?: 120).coerceAtLeast(0)
        val vibrate = intent?.getBooleanExtra(EXTRA_VIBRATE, true) ?: true
        val ramp = intent?.getBooleanExtra(EXTRA_RAMP, true) ?: true
        when {
            source == "SILENT" -> { /* 静音仍保留通知与提醒页 */ }
            source == "BUILTIN" -> startBuiltin(ramp)
            uri != null -> startMedia(Uri.parse(uri), ramp)
            else -> startBuiltin(ramp)
        }
        if (vibrate && source != "SILENT") startVibration()
        if (durationSeconds > 0) handler.postDelayed(stopNow, durationSeconds * 1000L)
        return START_NOT_STICKY
    }

    private fun startBuiltin(ramp: Boolean) {
        val initial = if (ramp) 32 else 92
        toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, initial)
        if (ramp) {
            var step = 0
            val ramper = object : Runnable {
                override fun run() {
                    if (!running) return
                    step++
                    val volume = (32 + step * 6).coerceAtMost(92)
                    toneGenerator?.release()
                    toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, volume)
                    if (step < 10) handler.postDelayed(this, 400)
                }
            }
            handler.post(ramper)
        }
        handler.post(chime)
    }

    private fun startMedia(uri: Uri, ramp: Boolean) {
        runCatching {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setDataSource(this@RingtoneAlarmService, uri)
                isLooping = true
                prepare()
                setVolume(if (ramp) 0f else 1f, if (ramp) 0f else 1f)
                start()
            }
            if (ramp) {
                var step = 0
                val ramper = object : Runnable {
                    override fun run() {
                        if (!running) return
                        step++
                        val volume = (step / 10f).coerceAtMost(1f)
                        mediaPlayer?.setVolume(volume, volume)
                        if (step < 10) handler.postDelayed(this, 200)
                    }
                }
                handler.post(ramper)
            }
        }.onFailure {
            toneGenerator?.release()
            toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 92)
            handler.post(chime)
        }
    }

    private fun startVibration() {
        vibrator = if (android.os.Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 500, 700), 0))
    }

    private fun buildNotification(): Notification {
        val close = PendingIntent.getService(
            this,
            1,
            Intent(this, RingtoneAlarmService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val enter = PendingIntent.getActivity(
            this,
            2,
            Intent(this, TaskReminderActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("任务到点了")
            .setContentText("铃声正在提醒你，点击进入查看任务。")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(enter)
            .addAction(0, "关闭铃声", close)
            .addAction(0, "进入任务", enter)
            .build()
    }

    private fun stopAlarm() {
        if (!running && mediaPlayer == null && toneGenerator == null && vibrator == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        running = false
        handler.removeCallbacksAndMessages(null)
        mediaPlayer?.release(); mediaPlayer = null
        toneGenerator?.release(); toneGenerator = null
        vibrator?.cancel(); vibrator = null
        isRinging = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        mediaPlayer?.release(); mediaPlayer = null
        toneGenerator?.release(); toneGenerator = null
        vibrator?.cancel(); vibrator = null
        isRinging = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "task_start_ringtone"
        private const val NOTIFICATION_ID = 7801
        private const val ACTION_STOP = "com.justenough.planner.RING_STOP"
        private const val EXTRA_SOURCE = "source"
        private const val EXTRA_URI = "uri"
        private const val EXTRA_DURATION = "duration"
        private const val EXTRA_VIBRATE = "vibrate"
        private const val EXTRA_RAMP = "ramp"
        @Volatile var isRinging = false
            private set

        fun start(context: Context, settings: SettingsState) {
            if (isRinging) return
            isRinging = true
            try {
                val intent = Intent(context, RingtoneAlarmService::class.java).setAction("com.justenough.planner.RING_START").apply {
                    putExtra(EXTRA_SOURCE, settings.taskRingtone)
                    putExtra(EXTRA_URI, settings.customRingtoneUri)
                    putExtra(EXTRA_DURATION, settings.ringtoneDurationSeconds)
                    putExtra(EXTRA_VIBRATE, settings.ringtoneVibrate)
                    putExtra(EXTRA_RAMP, settings.ringtoneVolumeRamp)
                }
                ContextCompat.startForegroundService(context, intent)
            } catch (failure: Throwable) {
                isRinging = false
            }
        }

        fun stop(context: Context) {
            isRinging = false
            runCatching { context.stopService(Intent(context, RingtoneAlarmService::class.java).setAction(ACTION_STOP)) }
        }
    }
}
