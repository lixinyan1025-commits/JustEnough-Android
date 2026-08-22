package com.justenough.planner.pet

import android.app.*
import android.content.*
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.*
import android.provider.Settings
import android.text.TextUtils
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.WorkManager
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieDrawable
import com.justenough.planner.MainActivity
import com.justenough.planner.R
import com.justenough.planner.appContainer
import com.justenough.planner.data.PetVisibility
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

class AiPetService : Service() {
    private lateinit var wm: WindowManager
    private var pet: LottieAnimationView? = null
    private var panel: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var inputView: EditText? = null
    private var panelFocused = false
    private var panelOriginalY = 0
    private var panelMovedForIme = false
    private var bubble: View? = null
    private var bubblePromptId: Long = -1
    private var bubbleSeconds = 8
    private var petParams: WindowManager.LayoutParams? = null
    private var petLocked = false
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val closePanel = Runnable(::hidePanel)
    private val wander = object : Runnable {
        override fun run() {
            val view = pet; val params = petParams
            if (view != null && params != null && !petLocked && view.visibility == View.VISIBLE && panel == null) {
                play(PetMotion.WALK)
                val range = safeVerticalRange(params.height)
                val direction = if ((System.currentTimeMillis() / 12_000) % 2L == 0L) 1 else -1
                val start = params.y; val distance = (36 * resources.displayMetrics.density).toInt() * direction
                val started = System.currentTimeMillis()
                val move = object : Runnable {
                    override fun run() {
                        val elapsed = (System.currentTimeMillis() - started).coerceAtMost(1600)
                        params.y = (start + distance * (elapsed / 1600f)).toInt().coerceIn(range.first, range.last)
                        runCatching { wm.updateViewLayout(view, params) }
                        if (elapsed < 1600) handler.postDelayed(this, 40) else play(PetMotion.IDLE)
                    }
                }
                handler.post(move)
            }
            handler.postDelayed(this, 12_000)
        }
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) pet?.pauseAnimation() else pet?.resumeAnimation()
        }
    }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "小满桌宠", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, NOTIFICATION_ID, Intent(this, MainActivity::class.java).putExtra("screen", "ai"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        startForeground(NOTIFICATION_ID, NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle("小满在这里").setContentText("点一下小满，可以用小窗口聊天").setOngoing(true).setContentIntent(open).build())
        ContextCompat.registerReceiver(this, screenReceiver, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON) }, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scope.launch {
            val state = appContainer.settings.state.first()
            bubbleSeconds = state.petBubbleSeconds
            if (!PetVisibility.isVisible(state.petVisibility) || !state.aiConnectionVerified || !Settings.canDrawOverlays(this@AiPetService)) {
                stopNow(); return@launch
            }
            showPet(state.petSize, state.petLocked)
            when (intent?.action) {
                ACTION_CELEBRATE -> play(PetMotion.CELEBRATE)
                ACTION_THINK -> play(PetMotion.THINK)
                ACTION_REMIND -> {
                    play(PetMotion.REMIND)
                    intent.getStringExtra(EXTRA_BUBBLE)?.let { message ->
                        showBubble(message, intent.getLongExtra(EXTRA_PROMPT_ID, -1), intent.getStringExtra(EXTRA_OPTIONS).orEmpty(), intent.getBooleanExtra(EXTRA_PERSISTENT, false))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showPet(size: Int, locked: Boolean) {
        if (pet != null || resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) return
        petLocked = locked
        val px = (size.coerceIn(48, 80) * resources.displayMetrics.density).toInt()
        val view = LottieAnimationView(this).apply {
            setAnimation(R.raw.xiaoman_character)
            repeatCount = LottieDrawable.INFINITE
            setBackgroundColor(Color.TRANSPARENT)
            setFailureListener { stopNow() }
            playAnimation()
        }
        val params = WindowManager.LayoutParams(px, px, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - px - 8
            y = safeVerticalRange(px).first + resources.displayMetrics.heightPixels / 4
        }
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false; var downAt = 0L
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; startX = params.x; startY = params.y; moved = false; downAt = System.currentTimeMillis(); true }
                MotionEvent.ACTION_MOVE -> { if (!petLocked) { val ox = event.rawX - downX; val oy = event.rawY - downY; moved = moved || abs(ox) > 8 || abs(oy) > 8; params.x = (startX + ox.toInt()).coerceIn(0, resources.displayMetrics.widthPixels - px); val range = safeVerticalRange(px); params.y = (startY + oy.toInt()).coerceIn(range.first, range.last); runCatching { wm.updateViewLayout(view, params) } }; true }
                MotionEvent.ACTION_UP -> {
                    if (moved) snapToEdge(view, params)
                    else if (bubble != null) { removeBubble(); togglePanel(params) }
                    else if (panel != null) hidePanel()
                    else if (System.currentTimeMillis() - downAt > 650) showMenu()
                    else togglePanel(params)
                    true
                }
                else -> false
            }
        }
        runCatching { wm.addView(view, params) }.onSuccess { pet = view; petParams = params; play(PetMotion.IDLE); handler.removeCallbacks(wander); handler.postDelayed(wander, 4_000) }
    }

    private fun snapToEdge(view: View, params: WindowManager.LayoutParams) {
        val right = params.x + params.width / 2 >= resources.displayMetrics.widthPixels / 2
        params.x = if (right) resources.displayMetrics.widthPixels - params.width else 0
        view.scaleX = if (right) -1f else 1f
        runCatching { wm.updateViewLayout(view, params) }
    }

    private fun safeVerticalRange(height: Int): IntRange {
        val top = (32 * resources.displayMetrics.density).toInt()
        val bottom = (resources.displayMetrics.heightPixels - height - 80 * resources.displayMetrics.density).toInt().coerceAtLeast(top)
        return top..bottom
    }

    private fun play(motion: PetMotion) {
        val view = pet ?: return
        view.setMinAndMaxFrame(motion.start, motion.end)
        view.repeatCount = if (motion.loop) LottieDrawable.INFINITE else 0
        view.progress = 0f
        view.playAnimation()
        if (!motion.loop) handler.postDelayed({ if (pet === view) play(PetMotion.IDLE) }, motion.durationMs)
    }

    /** Compact transparent chat: about 72% wide and at most 35% of the screen. */
    private fun togglePanel(anchor: WindowManager.LayoutParams) {
        if (panel != null) { hidePanel(); return }
        renderPanel(anchor)
    }

    private fun renderPanel(anchor: WindowManager.LayoutParams) {
        scope.launch {
            val snapshot = appContainer.repository.todaySnapshot()
            val state = appContainer.repository.state.first()
            val settings = appContainer.settings.state.first()
            if (!settings.petCompactChatEnabled) { showMenu(); return@launch }
            val pendingPrompt = appContainer.repository.pendingPetPrompt()?.takeIf { it.expiresAt == null || it.expiresAt > System.currentTimeMillis() }
            val root = LinearLayout(this@AiPetService).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setBackgroundResource(R.drawable.pet_transparent_panel)
            }
            val header = LinearLayout(this@AiPetService).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            header.addView(TextView(this@AiPetService).apply {
                text = "小满"
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.rgb(32, 48, 38))
                includeFontPadding = false
                setPadding(0, dp(2), 0, dp(2))
            }, LinearLayout.LayoutParams(0, WindowManager.LayoutParams.WRAP_CONTENT, 1f))
            header.addView(TextView(this@AiPetService).apply {
                text = "完整版"
                textSize = 12f
                setTextColor(Color.rgb(49, 93, 81))
                setPadding(dp(8), dp(2), dp(4), dp(2))
                setOnClickListener {
                    startActivity(Intent(this@AiPetService, MainActivity::class.java).putExtra("screen", "ai").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    hidePanel()
                }
            }, LinearLayout.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT))
            header.addView(TextView(this@AiPetService).apply {
                text = "×"
                textSize = 20f
                gravity = Gravity.CENTER
                setPadding(dp(10), 0, 0, 0)
                setOnClickListener { hidePanel() }
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            root.addView(header)
            val scroller = ScrollView(this@AiPetService).apply { isFillViewport = true; setVerticalScrollBarEnabled(false) }
            val messages = LinearLayout(this@AiPetService).apply { orientation = LinearLayout.VERTICAL }
            messages.addText("${java.time.LocalTime.now().withSecond(0).withNano(0)}   ${meter(snapshot.fulfillmentTotal)}", 14, true)
            snapshot.activeRun?.let { run -> messages.addText("正在进行：${run.name}", 13, true) }
            val history = state.aiMessages.takeLast(12)
            if (history.isEmpty()) messages.addText("小满在这里。想聊什么都可以。", 13, false)
            else history.forEach { item -> messages.addText("${if (item.role == "user") "你" else "小满"}：${item.content.take(80)}", 12, item.role != "user") }
            scroller.addView(messages, LinearLayout.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT))
            root.addView(scroller, LinearLayout.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, 0, 1f))
            root.addView(View(this@AiPetService).apply { setBackgroundColor(Color.argb(45, 49, 93, 81)) }, LinearLayout.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, dp(1)))
            val inputRow = LinearLayout(this@AiPetService).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0) }
            val input = EditText(this@AiPetService).apply {
                hint = if (pendingPrompt != null) "详细回答这个问题…" else "和小满说一句…"
                textSize = 13f
                // Single-line so the soft keyboard shows a real "发送" action key
                // instead of a newline key (multiline would swallow IME_ACTION_SEND).
                setSingleLine(true)
                isFocusableInTouchMode = true
                setTextColor(Color.rgb(32, 48, 38))
                setHintTextColor(Color.rgb(100, 112, 104))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    // Must be fully opaque: Android 12+ only delivers touches to opaque
                    // pixels of TYPE_APPLICATION_OVERLAY windows, so a semi-transparent
                    // box lets taps fall through and the IME never opens.
                    setColor(Color.argb(255, 255, 255, 255))
                    setStroke(dp(1), Color.argb(70, 49, 93, 81))
                }
                setPadding(dp(10), dp(8), dp(10), dp(8))
                minHeight = dp(44)
                setOnFocusChangeListener { view, hasFocus ->
                    if (hasFocus) {
                        handler.removeCallbacks(closePanel)
                        view.post {
                            getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                                ?.showSoftInput(view, android.view.inputmethod.InputMethodManager.SHOW_FORCED)
                        }
                    }
                }
                setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_UP) focusForInput()
                    false
                }
            }
            input.setOnClickListener { focusForInput() }
            inputView = input
            inputRow.addView(input, LinearLayout.LayoutParams(0, WindowManager.LayoutParams.WRAP_CONTENT, 1f))
            val send = Button(this@AiPetService).apply { text = "发送"; textSize = 12f }
            send.setOnClickListener {
                val value = input.text.toString().trim()
                if (value.isBlank()) return@setOnClickListener
                handler.removeCallbacks(closePanel)
                input.setText("")
                send.isEnabled = false
                input.isEnabled = false
                scope.launch {
                    val outcome = runCatching {
                        if (pendingPrompt != null) PetConversationEngine.answer(this@AiPetService, pendingPrompt.id, null, value)
                        else PetConversationEngine.chat(this@AiPetService, value).getOrThrow()
                    }
                    outcome.onFailure { failure ->
                        runCatching {
                            appContainer.repository.addAiMessage("assistant", "没发出去：${(failure.message ?: "发送失败").take(120)}", "PET_BUBBLE", "LOCAL")
                        }
                    }
                    hidePanel()
                    renderPanel(anchor)
                }
            }
            input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND)
            input.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND || actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { send.performClick(); true } else false
            }
            inputRow.addView(send, LinearLayout.LayoutParams(dp(72), WindowManager.LayoutParams.WRAP_CONTENT))
            root.addView(inputRow)
            val width = (resources.displayMetrics.widthPixels * .72f).toInt()
            val height = (resources.displayMetrics.heightPixels * .40f).toInt()
            val params = WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (anchor.x - width + anchor.width).coerceIn(dp(8), resources.displayMetrics.widthPixels - width - dp(8))
                y = (anchor.y + anchor.height + dp(6)).coerceIn(dp(40), resources.displayMetrics.heightPixels - height - dp(80))
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
            }
            root.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE) hidePanel()
                else {
                    handler.removeCallbacks(closePanel)
                    handler.postDelayed(closePanel, 30_000)
                }
                false
            }
            runCatching { wm.addView(root, params) }.onSuccess {
                panel = root
                panelParams = params
                root.viewTreeObserver.addOnWindowFocusChangeListener { hasFocus ->
                    if (!hasFocus && panel === root) releasePanelFocus(root, params)
                }
                handler.removeCallbacks(closePanel)
                handler.postDelayed(closePanel, 30_000)
            }
        }
    }

    /**
     * Android does not let a permanently non-focusable overlay host an IME reliably.
     * The standard approach is to briefly make the compact chat window focusable while
     * the user is typing, then restore FLAG_NOT_FOCUSABLE as soon as focus leaves it.
     */
    private fun focusForInput() {
        val root = panel ?: return
        val params = panelParams ?: return
        val input = inputView ?: return
        input.isFocusable = true
        input.isFocusableInTouchMode = true
        if (!panelFocused) {
            panelFocused = true
            if (!panelMovedForIme) {
                panelMovedForIme = true
                panelOriginalY = params.y
                // Keep the whole chat visible above the keyboard. ADJUST_PAN is not
                // reliable for overlay windows, so move the panel deterministically.
                params.y = dp(40)
            }
            root.isFocusable = true
            root.isFocusableInTouchMode = true
            params.flags = (params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() and WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM.inv()) or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
            runCatching { wm.updateViewLayout(root, params) }
        }
        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        handler.post { if (panel === root) root.requestFocus() }
        repeat(5) { attempt ->
            handler.postDelayed({
                if (panel !== root || inputView !== input) return@postDelayed
                val gotFocus = input.requestFocus()
                val hasWinFocus = input.hasWindowFocus()
                if (gotFocus || hasWinFocus) {
                    imm?.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_FORCED)
                }
            }, attempt * 120L)
        }
    }

    private fun releasePanelFocus(root: View, params: WindowManager.LayoutParams) {
        if (!panelFocused) return
        panelFocused = false
        val input = inputView
        runCatching { getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.hideSoftInputFromWindow(input?.windowToken, 0) }
        input?.clearFocus()
        if (panelMovedForIme) {
            panelMovedForIme = false
            params.y = panelOriginalY
        }
        params.flags = (params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) and WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM.inv()
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        runCatching { wm.updateViewLayout(root, params) }
    }

    private fun showMenu() {
        hidePanel(); val anchor = petParams ?: return
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(10)); setBackgroundResource(R.drawable.pet_transparent_panel)
            addText(if (petLocked) "解锁位置" else "锁定位置", 14, true) { petLocked = !petLocked; scope.launch { appContainer.settings.update { it.copy(petLocked = petLocked) } }; hidePanel() }
            listOf(48, 64, 80).forEach { size -> addText("大小 ${size}dp", 14, false) { scope.launch { appContainer.settings.update { it.copy(petSize = size) } }; val px = dp(size); anchor.width = px; anchor.height = px; pet?.let { wm.updateViewLayout(it, anchor) }; hidePanel() } }
            addText("暂时隐藏", 14, false) { hide(this@AiPetService) }
            addText("彻底关闭小满", 14, false) { disable(this@AiPetService) }
        }
        val params = WindowManager.LayoutParams(dp(190), WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = (anchor.x - width).coerceAtLeast(dp(8)); y = anchor.y }
        runCatching { wm.addView(root, params) }.onSuccess { panel = root; handler.postDelayed(closePanel, 30_000) }
    }

    private fun showBubble(message: String, promptId: Long, options: String, persistent: Boolean) {
        val anchor = petParams ?: return
        if(bubblePromptId>0&&promptId<=0)return
        removeBubble()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(8), dp(8)); setBackgroundResource(R.drawable.pet_transparent_panel) }
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        titleRow.addView(TextView(this).apply {
            text = message.take(80)
            textSize = 13f
            setTextColor(Color.rgb(32, 48, 38))
            setShadowLayer(3f, 0f, 1f, Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            setLineSpacing(0f, 1.05f)
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(2), dp(4), dp(2), dp(4))
        }, LinearLayout.LayoutParams(0, WindowManager.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(TextView(this).apply { text = "×"; textSize = 18f; gravity = Gravity.CENTER; setPadding(dp(10), 0, dp(4), 0); setOnClickListener { if (promptId > 0) scope.launch { PetConversationEngine.dismiss(this@AiPetService, promptId) }; removeBubble() } }, LinearLayout.LayoutParams(dp(38), dp(38)))
        root.addView(titleRow)
        decodeOptions(options).forEach { (code, label) -> root.addText(label, 12, true) {
            if(code.startsWith("START_")) {
                code.removePrefix("START_").toLongOrNull()?.let{planId->startActivity(Intent(this@AiPetService,com.justenough.planner.ui.ScheduledTaskStartActivity::class.java).putExtra("plan_id",planId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
                removeBubble()
            } else scope.launch { PetConversationEngine.answer(this@AiPetService, promptId, code); removeBubble() }
        } }
        if (promptId > 0) root.addText("想详细说说，点小满进入小聊天窗", 11, false) { removeBubble(); togglePanel(anchor) }
        val width = (resources.displayMetrics.widthPixels * .62f).toInt()
        val params = WindowManager.LayoutParams(width, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (anchor.x - width + anchor.width).coerceIn(dp(8), resources.displayMetrics.widthPixels - width - dp(8))
            y = (anchor.y - dp(120)).coerceAtLeast(dp(36))
        }
        runCatching { wm.addView(root, params) }.onSuccess { bubble = root;bubblePromptId=promptId; if (!persistent) handler.postDelayed({ if (bubble === root) removeBubble() }, bubbleSeconds.coerceIn(3, 60) * 1000L) }
    }

    private fun decodeOptions(value: String) = value.split(';').mapNotNull { part -> val fields = part.split('|'); if (fields.size >= 2) fields[0] to fields[1] else null }
    private fun removeBubble() { bubble?.let { runCatching { wm.removeView(it) } }; bubble = null;bubblePromptId=-1 }
    private fun hidePanel() { handler.removeCallbacks(closePanel); panel?.let { runCatching { wm.removeView(it) } }; panel = null; panelParams = null; inputView = null; panelFocused = false; panelMovedForIme = false }
    private fun stopNow() { handler.removeCallbacksAndMessages(null); hidePanel(); removeBubble(); pet?.let { runCatching { wm.removeView(it) } }; pet = null; petParams = null; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) { pet?.visibility = View.GONE; hidePanel(); removeBubble() } else pet?.visibility = View.VISIBLE }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); hidePanel(); removeBubble(); pet?.let { runCatching { wm.removeView(it) } }; pet = null; petParams = null; runCatching { unregisterReceiver(screenReceiver) }; scope.cancel(); super.onDestroy() }
    private fun LinearLayout.addText(value: String, size: Int, bold: Boolean, click: (() -> Unit)? = null) { addView(TextView(context).apply { text = value; textSize = size.toFloat(); setTextColor(Color.rgb(32, 48, 38)); gravity = Gravity.CENTER_VERTICAL; includeFontPadding = false; setPadding(dp(4), dp(8), dp(4), dp(8)); if (bold) setTypeface(typeface, Typeface.BOLD); click?.let { setOnClickListener { it() } } }) }
    private fun meter(total: Int) = (0 until 5).joinToString("") { if (it < total) "■" else "□" } + if (total > 5) " +${total - 5}" else ""
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val CHANNEL = "xiaoman_pet"
        private const val NOTIFICATION_ID = 8801
        const val ACTION_CELEBRATE = "com.justenough.planner.PET_CELEBRATE"
        const val ACTION_THINK = "com.justenough.planner.PET_THINK"
        const val ACTION_REMIND = "com.justenough.planner.PET_REMIND"
        private const val EXTRA_BUBBLE = "bubble"
        private const val EXTRA_PROMPT_ID = "prompt_id"
        private const val EXTRA_OPTIONS = "options"
        private const val EXTRA_PERSISTENT = "persistent"
        private val commandLock = Mutex()
        private fun launch(context: Context, block: suspend () -> Unit) { val app = context.applicationContext; CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { commandLock.withLock { block() } } }
        fun show(context: Context) = launch(context) { context.appContainer.settings.update { it.copy(petVisibility = PetVisibility.VISIBLE, petEnabled = true) }; PetBubbleWorker.schedule(context); ContextCompat.startForegroundService(context, Intent(context, AiPetService::class.java)) }
        fun hide(context: Context) = launch(context) { context.appContainer.settings.update { it.copy(petVisibility = PetVisibility.HIDDEN, petEnabled = false) }; PetBubbleWorker.cancel(context); WorkManager.getInstance(context).cancelAllWorkByTag("xiaoman-question"); context.stopService(Intent(context, AiPetService::class.java)); context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID) }
        fun disable(context: Context) = launch(context) { context.appContainer.settings.update { it.copy(petVisibility = PetVisibility.DISABLED, petEnabled = false) }; PetBubbleWorker.cancel(context); WorkManager.getInstance(context).cancelAllWorkByTag("xiaoman-question"); context.stopService(Intent(context, AiPetService::class.java)); context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID) }
        fun restart(context: Context) = launch(context) { val state = context.appContainer.settings.state.first(); if (PetVisibility.isVisible(state.petVisibility)) { context.stopService(Intent(context, AiPetService::class.java)); delay(250); ContextCompat.startForegroundService(context, Intent(context, AiPetService::class.java)) } }
        fun trigger(context: Context, action: String, bubble: String? = null, promptId: Long = -1, options: String = "", persistent: Boolean = false) = launch(context) {
            val state = context.appContainer.settings.state.first()
            if (PetVisibility.isVisible(state.petVisibility) && state.aiConnectionVerified) ContextCompat.startForegroundService(context, Intent(context, AiPetService::class.java).setAction(action).putExtra(EXTRA_BUBBLE, bubble).putExtra(EXTRA_PROMPT_ID, promptId).putExtra(EXTRA_OPTIONS, options).putExtra(EXTRA_PERSISTENT, persistent))
        }
    }
}

private enum class PetMotion(val start: Int, val end: Int, val loop: Boolean, val durationMs: Long) { IDLE(0, 29, true, 0), WALK(30, 59, true, 0), THINK(60, 79, true, 0), REMIND(80, 99, false, 1700), CELEBRATE(100, 119, false, 1700) }
