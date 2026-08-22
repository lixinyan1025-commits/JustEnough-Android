package com.justenough.planner.task

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.justenough.planner.appContainer
import com.justenough.planner.reminder.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

/**
 * 自动打开绑定 App 时显示的短时任务说明浮层，不阻塞下方应用的触摸。
 */
class TaskOverlayService : Service() {
    private var wm: WindowManager? = null
    private var view: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private val dismiss = Runnable(::remove)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val planId = intent?.getLongExtra(EXTRA_PLAN_ID, -1) ?: -1
        if (planId < 0 || Settings.canDrawOverlays(this).not()) {
            if (planId >= 0) CoroutineScope(Dispatchers.IO).launch {
                appContainer.repository.getPlan(planId)?.let { NotificationHelper.showTaskDescriptionFallback(this@TaskOverlayService, it) }
            }
            stopSelf()
            return START_NOT_STICKY
        }
        CoroutineScope(Dispatchers.IO).launch {
            val plan = appContainer.repository.getPlan(planId)
            val settings = appContainer.settings.state.first()
            if (!settings.taskOverlayEnabled) { stopSelf(); return@launch }
            val seconds = settings.taskOverlaySeconds.coerceAtLeast(0)
            if (plan != null) {
                handler.post {
                    show(plan.name, plan.minimumGoal)
                    handler.removeCallbacks(dismiss)
                    if (seconds > 0) handler.postDelayed(dismiss, seconds * 1000L)
                }
            } else stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun show(name: String, goal: String) {
        val manager = getSystemService(WindowManager::class.java) ?: return
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xE6FBF8F1.toInt())
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Color.parseColor("#66315D51"))
            }
        }
        root.addView(TextView(this).apply {
            text = "现在开始：$name"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(49, 93, 81))
        })
        root.addView(TextView(this).apply {
            text = "目标：$goal"
            textSize = 14f
            setTextColor(Color.rgb(70, 84, 78))
            setPadding(0, dp(4), 0, 0)
        })
        root.addView(TextView(this).apply {
            text = "点击此浮层或 × 立即关闭"
            textSize = 11f
            setTextColor(Color.rgb(110, 120, 114))
            setPadding(0, dp(6), 0, 0)
        })
        root.setOnClickListener { remove() }
        val params = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * .86f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(54)
        }
        runCatching { manager.addView(root, params) }.onSuccess {
            wm = manager
            view = root
        }.onFailure { stopSelf() }
    }

    private fun remove() {
        handler.removeCallbacks(dismiss)
        view?.let { runCatching { wm?.removeView(it) } }
        view = null
        wm = null
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        remove()
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_PLAN_ID = "plan_id"
        fun start(context: Context, planId: Long) {
            runCatching {
                context.startService(Intent(context, TaskOverlayService::class.java).putExtra(EXTRA_PLAN_ID, planId))
            }
        }
    }
}
