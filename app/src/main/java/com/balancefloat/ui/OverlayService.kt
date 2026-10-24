package com.balancefloat.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.balancefloat.MainActivity
import com.balancefloat.R
import com.balancefloat.data.BalanceRepo
import com.balancefloat.data.Money
import com.balancefloat.data.Provider
import com.balancefloat.data.Store
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 悬浮窗：胶囊（合计）↔ 明细卡片。
 * 拖动移位，单击刷新，双击切换形态，长按打开 App。
 * 前台服务保证锁屏/切后台后仍按间隔刷新。
 */
class OverlayService : Service() {

    /** 胶囊轮播的一条：文案 + 指示灯颜色（逐渠道，不做跨渠道合计）。 */
    private data class PillEntry(val text: String, val color: Int)

    companion object {
        const val ACTION_RELOAD = "com.balancefloat.action.RELOAD"
        const val ACTION_APPLY = "com.balancefloat.action.APPLY"
        private const val CHANNEL_ID = "balance_float"
        private const val NOTIF_ID = 0x8B1

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, OverlayService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, OverlayService::class.java))
        }

        fun reload(ctx: Context) {
            if (!isRunning) return
            runCatching { ctx.startService(Intent(ctx, OverlayService::class.java).setAction(ACTION_RELOAD)) }
        }

        /** 只重建外观（颜色/尺寸/开关），不触发网络刷新。 */
        fun pushLook(ctx: Context) {
            if (!isRunning) return
            runCatching { ctx.startService(Intent(ctx, OverlayService::class.java).setAction(ACTION_APPLY)) }
        }
    }

    private lateinit var wm: WindowManager
    private lateinit var store: Store
    private lateinit var root: FrameLayout
    private lateinit var params: WindowManager.LayoutParams

    private var compactView: View? = null
    private var detailView: View? = null
    private var expanded = false

    private var pillEntries: List<PillEntry> = emptyList()
    private var pillIndex = 0
    private var modeAnim: ValueAnimator? = null
    private val rotateRunnable = Runnable { advancePill() }

    private val ui = Handler(Looper.getMainLooper())
    private var ticker: Runnable? = null

    private val repoListener: () -> Unit = { renderAll() }

    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var dragging = false
    private var lastTapAt = 0L

    private val longPressRunnable = Runnable {
        lastTapAt = 0
        dragging = true // 吞掉抬手时的点击
        root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        refreshNow()
    }

    private val singleTapRunnable = Runnable {
        lastTapAt = 0L
        // 详情卡片里单击不做事，避免误触；刷新走长按或按钮
        if (!expanded) advancePill()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        isRunning = true
        createChannel()
        startForegroundCompat()

        if (!Settings.canDrawOverlays(this)) {
            toast("没有悬浮窗权限")
            stopSelf()
            return
        }
        if (!initWindow()) {
            stopSelf()
            return
        }

        expanded = store.pinExpanded || store.expanded
        BalanceRepo.addListener(repoListener)
        applyMode(false)
        scheduleTick()
        refreshNow()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_APPLY -> {
                if (::params.isInitialized) {
                    applyWindowBlur()
                    runCatching { wm.updateViewLayout(root, params) }
                }
                // App 里打开「常驻展开」时立即展开，不必等下次重启
                if (store.pinExpanded) expanded = true
                applyMode(false)
            }

            ACTION_RELOAD -> {
                if (::params.isInitialized) {
                    applyWindowBlur()
                    runCatching { wm.updateViewLayout(root, params) }
                }
                applyMode(false)
                scheduleTick()
                refreshNow()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        BalanceRepo.removeListener(repoListener)
        ticker?.let { ui.removeCallbacks(it) }
        ui.removeCallbacksAndMessages(null)
        if (::root.isInitialized) {
            runCatching { wm.removeView(root) }
        }
        super.onDestroy()
    }

    // ---------- 窗口 ----------

    private fun initWindow(): Boolean {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 未保存过位置时用 dp 计算，避免默认值被当成像素贴到状态栏下
            x = if (store.hasOverlayPosition) store.overlayX else dp(16)
            y = if (store.hasOverlayPosition) store.overlayY else dp(220)
        }
        applyWindowBlur()

        root = FrameLayout(this)
        root.setOnTouchListener { _, event -> handleTouch(event) }
        return runCatching {
            wm.addView(root, params)
            true
        }.getOrElse {
            toast("悬浮窗添加失败：" + (it.message ?: "未知错误"))
            false
        }
    }

    /**
     * 窗口级「后方模糊」：只糊窗口后面的内容，胶囊/明细本身保持清晰。
     * Android 12+ 才有；部分 ROM 不支持或行为异常时可在 App 里关掉。
     */
    private fun applyWindowBlur() {
        if (Build.VERSION.SDK_INT < 31) return
        @Suppress("DEPRECATION")
        val flag = WindowManager.LayoutParams.FLAG_BLUR_BEHIND
        params.flags = if (store.overlayBlur) params.flags or flag
        else params.flags and flag.inv()
        params.blurBehindRadius = if (store.overlayBlur) dp(30) else 0
    }

    private fun applyMode(animate: Boolean) {
        if (!::root.isInitialized) return
        modeAnim?.cancel()
        modeAnim = null
        store.expanded = expanded

        val oldView = root.getChildAt(0)
        val newView = try {
            if (expanded) inflateDetail() else inflateCompact()
        } catch (e: Throwable) {
            // 布局炸了也别让服务整个挂掉，退回胶囊
            expanded = false
            store.expanded = false
            toast("悬浮窗布局加载失败：" + (e.message ?: e.javaClass.simpleName))
            return
        }

        if (!animate || oldView == null || oldView.width == 0) {
            root.removeAllViews()
            root.addView(newView, contentLp(expanded))
            renderAll()
            clampIntoScreen()
            return
        }

        // 先填好内容再量目标尺寸（不挂到窗口也能量）
        renderAll()
        newView.layoutParams = contentLp(expanded)
        val dm = resources.displayMetrics
        newView.measure(
            View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.AT_MOST)
        )
        val w1 = newView.measuredWidth.coerceAtLeast(dp(60))
        val h1 = newView.measuredHeight.coerceAtLeast(dp(36))
        val w0 = oldView.width.coerceAtLeast(dp(60))
        val h0 = oldView.height.coerceAtLeast(dp(36))

        root.removeAllViews()
        root.addView(oldView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(newView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        oldView.alpha = 1f
        newView.alpha = 0f

        params.width = w0
        params.height = h0
        runCatching { wm.updateViewLayout(root, params) }

        val anim = ValueAnimator.ofFloat(0f, 1f).setDuration(230)
        anim.interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
        anim.addUpdateListener { a ->
            val t = a.animatedValue as Float
            params.width = (w0 + (w1 - w0) * t).toInt()
            params.height = (h0 + (h1 - h0) * t).toInt()
            oldView.alpha = 1f - t
            newView.alpha = t
            runCatching { wm.updateViewLayout(root, params) }
        }
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                modeAnim = null
                root.removeAllViews()
                root.addView(newView, contentLp(expanded))
                newView.alpha = 1f
                params.width = ViewGroup.LayoutParams.WRAP_CONTENT
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                runCatching { wm.updateViewLayout(root, params) }
                clampIntoScreen()
                renderAll()
            }
        })
        modeAnim = anim
        anim.start()
    }

    private fun inflateCompact(): View {
        val v = LayoutInflater.from(this).inflate(R.layout.overlay_compact, root, false)
        Ui.applyScale(v, store.pillScale)
        paintBg(v, false)
        compactView = v
        detailView = null
        pillEntries = emptyList()
        pillIndex = 0
        return v
    }

    private fun inflateDetail(): View {
        val v = LayoutInflater.from(this).inflate(R.layout.overlay_detail, root, false)
        val content = v.findViewById<View>(R.id.detailContent)
        Ui.applyScale(content, store.detailScale)
        paintBg(v, true)

        v.findViewById<TextView>(R.id.btnClose).setOnClickListener {
            store.overlayOn = false
            stopSelf()
        }
        v.findViewById<TextView>(R.id.btnRefreshDetail).setOnClickListener { refreshNow() }
        v.findViewById<TextView>(R.id.btnOpenApp).setOnClickListener { openApp() }

        val hint = v.findViewById<TextView>(R.id.tvHint)
        fun syncHint() {
            hint.text = if (store.lockPosition) "已锁定 · 长按刷新" else "长按刷新 · 双击收起"
        }
        syncHint()

        val panel = v.findViewById<View>(R.id.settingsPanel)
        val tvPillScale = v.findViewById<TextView>(R.id.tvPillScale)
        val tvDetailScale = v.findViewById<TextView>(R.id.tvDetailScale)
        val seekPill = v.findViewById<SeekBar>(R.id.seekPillScale)
        val seekDetail = v.findViewById<SeekBar>(R.id.seekDetailScale)

        fun scaleLabel(scale: Float) = ((scale * 100).roundToInt()).toString() + "%"

        fun syncScales() {
            val pill = store.pillScale
            val detail = store.detailScale
            seekPill.progress = ((pill - 0.8f) * 100).roundToInt().coerceIn(0, seekPill.max)
            seekDetail.progress = ((detail - 0.8f) * 100).roundToInt().coerceIn(0, seekDetail.max)
            tvPillScale.text = scaleLabel(pill)
            tvDetailScale.text = scaleLabel(detail)
        }
        syncScales()

        seekPill.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                tvPillScale.text = scaleLabel(0.8f + progress / 100f)
            }

            override fun onStartTrackingTouch(sb: SeekBar) = Unit

            override fun onStopTrackingTouch(sb: SeekBar) {
                store.pillScale = 0.8f + sb.progress / 100f
            }
        })

        seekDetail.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                tvDetailScale.text = scaleLabel(0.8f + progress / 100f)
            }

            override fun onStartTrackingTouch(sb: SeekBar) = Unit

            override fun onStopTrackingTouch(sb: SeekBar) {
                store.detailScale = 0.8f + sb.progress / 100f
                Ui.applyScale(content, store.detailScale)
                root.requestLayout()
                root.post { clampIntoScreen() }
            }
        })

        // ---------- 行为开关 ----------

        val swScroll = v.findViewById<Switch>(R.id.swScroll)
        val swLock = v.findViewById<Switch>(R.id.swLock)
        val swPin = v.findViewById<Switch>(R.id.swPin)

        swScroll.isChecked = store.pillAutoScroll
        swScroll.setOnCheckedChangeListener { _, on ->
            store.pillAutoScroll = on
            scheduleRotation()
        }

        swLock.isChecked = store.lockPosition
        swLock.setOnCheckedChangeListener { _, on ->
            store.lockPosition = on
            syncHint()
        }

        swPin.isChecked = store.pinExpanded
        swPin.setOnCheckedChangeListener { _, on ->
            store.pinExpanded = on
            if (on && !expanded) {
                expanded = true
                applyMode(true)
            }
        }

        // ---------- 固定尺寸 ----------

        val seekPillW = v.findViewById<SeekBar>(R.id.seekPillW)
        val seekPillH = v.findViewById<SeekBar>(R.id.seekPillH)
        val seekDetailW = v.findViewById<SeekBar>(R.id.seekDetailW)
        val tvPillW = v.findViewById<TextView>(R.id.tvPillW)
        val tvPillH = v.findViewById<TextView>(R.id.tvPillH)
        val tvDetailW = v.findViewById<TextView>(R.id.tvDetailW)

        fun syncSizes() {
            seekPillW.progress = store.pillWidthDp
            seekPillH.progress = store.pillHeightDp
            seekDetailW.progress = store.detailWidthDp
        }
        syncSizes()

        bindDpSlider(seekPillW, tvPillW, 320) { store.pillWidthDp = it; applySizes() }
        bindDpSlider(seekPillH, tvPillH, 120) { store.pillHeightDp = it; applySizes() }
        bindDpSlider(seekDetailW, tvDetailW, 380) { store.detailWidthDp = it; applySizes() }

        // ---------- 配色 ----------

        val rowPillBg = v.findViewById<LinearLayout>(R.id.swatchesPill)
        val rowDetailBg = v.findViewById<LinearLayout>(R.id.swatchesDetail)

        fun syncSwatches() {
            Ui.fillSwatches(this, rowPillBg, store.pillBg) { c ->
                store.pillBg = c
                syncSwatches()
                compactView?.let { paintBg(it, false) }
            }
            Ui.fillSwatches(this, rowDetailBg, store.detailBg) { c ->
                store.detailBg = c
                syncSwatches()
                paintBg(v, true)
            }
        }
        syncSwatches()

        v.findViewById<View>(R.id.btnScaleReset).setOnClickListener {
            store.pillScale = 1f
            store.detailScale = 1f
            store.pillWidthDp = 0
            store.pillHeightDp = 0
            store.detailWidthDp = 0
            store.pillBg = 0
            store.detailBg = 0
            syncScales()
            syncSizes()
            syncSwatches()
            Ui.applyScale(content, 1f)
            paintBg(v, true)
            applySizes()
        }

        v.findViewById<View>(R.id.btnSettings).setOnClickListener {
            val show = panel.visibility != View.VISIBLE
            panel.visibility = if (show) View.VISIBLE else View.GONE
            if (show) {
                syncScales()
                syncSizes()
                syncSwatches()
            }
            root.post { clampIntoScreen() }
        }

        detailView = v
        compactView = null
        return v
    }

    /** 窗口里那层内容的布局参数：固定 dp 尺寸或自适应。 */
    private fun contentLp(detail: Boolean): FrameLayout.LayoutParams {
        val w = if (detail) store.detailWidthDp else store.pillWidthDp
        val h = if (detail) 0 else store.pillHeightDp
        return FrameLayout.LayoutParams(
            if (w > 0) dp(w) else ViewGroup.LayoutParams.WRAP_CONTENT,
            if (h > 0) dp(h) else ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    /** 改尺寸后让当前形态立即套用。 */
    private fun applySizes() {
        val v = (if (expanded) detailView else compactView) ?: return
        v.layoutParams = contentLp(expanded)
        root.requestLayout()
        root.post { clampIntoScreen() }
    }

    /** 内置 drawable 或自定义 ARGB。 */
    private fun paintBg(v: View, detail: Boolean) {
        val c = if (detail) store.detailBg else store.pillBg
        v.background = if (c == 0) {
            getDrawable(if (detail) R.drawable.bg_detail else R.drawable.bg_pill)
        } else {
            Ui.roundedBg(this, c, if (detail) 20f else 30f)
        }
    }

    /** 0 表示自适应；松手才回调，避免拖动时反复重建视图。 */
    private fun bindDpSlider(seek: SeekBar, label: TextView, max: Int, onChange: (Int) -> Unit) {
        seek.max = max
        fun show(p: Int) {
            label.text = if (p == 0) "自动" else p.toString() + "dp"
        }
        show(seek.progress)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) = show(progress)

            override fun onStartTrackingTouch(sb: SeekBar) = Unit

            override fun onStopTrackingTouch(sb: SeekBar) = onChange(sb.progress)
        })
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                startX = params.x
                startY = params.y
                dragging = false
                ui.removeCallbacks(longPressRunnable)
                ui.postDelayed(longPressRunnable, 520)
            }

            MotionEvent.ACTION_MOVE -> {
                if (store.lockPosition) return true
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (!dragging && hypot(dx.toDouble(), dy.toDouble()) > dp(6).toDouble()) {
                    dragging = true
                    ui.removeCallbacks(longPressRunnable)
                }
                if (dragging) {
                    params.x = startX + dx.toInt()
                    params.y = startY + dy.toInt()
                    clampIntoWindowParams()
                    runCatching { wm.updateViewLayout(root, params) }
                }
            }

            MotionEvent.ACTION_UP -> {
                ui.removeCallbacks(longPressRunnable)
                if (dragging) {
                    store.overlayX = params.x
                    store.overlayY = params.y
                    dragging = false
                } else {
                    onTap()
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                ui.removeCallbacks(longPressRunnable)
                dragging = false
            }
        }
        return true
    }

    private fun onTap() {
        val now = SystemClock.uptimeMillis()
        if (now - lastTapAt in 1..260) {
            lastTapAt = 0L
            ui.removeCallbacks(singleTapRunnable)
            toggleMode()
        } else {
            lastTapAt = now
            ui.postDelayed(singleTapRunnable, 280)
        }
    }

    private fun toggleMode() {
        if (expanded && store.pinExpanded) {
            toast("常驻展开已开启，先关掉才能收起")
            return
        }
        expanded = !expanded
        applyMode(true)
    }

    private fun openApp() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        }
    }

    private fun clampIntoWindowParams() {
        val dm = resources.displayMetrics
        val w = if (::root.isInitialized && root.width > 0) root.width else dp(180)
        val h = if (::root.isInitialized && root.height > 0) root.height else dp(48)
        params.x = params.x.coerceIn(0, (dm.widthPixels - w).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (dm.heightPixels - h).coerceAtLeast(0))
    }

    private fun clampIntoScreen() {
        root.post {
            if (!::params.isInitialized) return@post
            clampIntoWindowParams()
            runCatching { wm.updateViewLayout(root, params) }
        }
    }

    // ---------- 数据 ----------

    private fun refreshNow() {
        val providers = store.providers()
        if (providers.none { it.enabled && (it.apiKey.isNotBlank() || it.auth == "none") }) {
            renderAll()
            return
        }
        BalanceRepo.refreshAll(providers) {
            store.lastRefreshAt = System.currentTimeMillis()
            renderAll()
        }
    }

    private fun scheduleTick() {
        ticker?.let { ui.removeCallbacks(it) }
        val sec = store.intervalSec
        if (sec <= 0) return
        val r = Runnable {
            refreshNow()
            scheduleTick()
        }
        ticker = r
        ui.postDelayed(r, sec * 1000L)
    }

    private fun renderAll() {
        if (!isRunning || !::root.isInitialized) return
        val allProviders = store.providers()
        val providers = allProviders.filter { it.enabled }
        val results = BalanceRepo.results()
        val entries = buildEntries(allProviders, providers, results)

        compactView?.let { v ->
            val pb = v.findViewById<ProgressBar>(R.id.pbPill)
            updatePill(entries)
            pb.visibility = if (BalanceRepo.refreshing) View.VISIBLE else View.GONE
        }

        detailView?.let { v ->
            val tvUpdated = v.findViewById<TextView>(R.id.tvUpdated)
            val rows = v.findViewById<LinearLayout>(R.id.rows)
            val btnRefresh = v.findViewById<TextView>(R.id.btnRefreshDetail)

            tvUpdated.text = if (BalanceRepo.refreshing) "刷新中" else Fmt.ago(BalanceRepo.lastUpdateAt())
            btnRefresh.text = if (BalanceRepo.refreshing) "…" else "刷新"

            rows.removeAllViews()
            if (providers.isEmpty()) {
                rows.addView(label("还没有渠道，点开 App 添加", R.color.text_muted, 11f))
            }
            providers.forEach { p ->
                rows.addView(detailRow(p, results[p.id]))
            }
            Ui.applyScale(rows, store.detailScale)
        }

        updateNotification(entries)
    }

    /** 逐渠道一条，不做合计：每家计价和币种都不一样。 */
    private fun buildEntries(
        all: List<Provider>,
        providers: List<Provider>,
        results: Map<String, BalanceRepo.Result>
    ): List<PillEntry> {
        val list = ArrayList<PillEntry>()
        providers.forEach { p ->
            val r = results[p.id] ?: return@forEach
            val nm = p.name.ifBlank { p.kind.label }
            val text = when {
                !r.ok -> "$nm 失败"
                p.isQuota() -> {
                    val w = r.windows.maxByOrNull { it.usedPercent } ?: return@forEach
                    "$nm ${w.label} ${Fmt.percent(w.usedPercent)}"
                }
                else -> {
                    val amounts = r.amounts.ifEmpty {
                        r.amount?.let { listOf(Money(it, r.unit.ifBlank { p.unit })) } ?: emptyList()
                    }
                    if (amounts.isEmpty()) return@forEach
                    "$nm " + Fmt.moneyList(amounts)
                }
            }
            list.add(PillEntry(text, Ui.dotColor(this, p, r)))
        }
        if (list.isEmpty()) {
            val text = when {
                all.isEmpty() -> "未配置渠道"
                providers.isEmpty() -> "无启用渠道"
                BalanceRepo.refreshing -> "查询中…"
                else -> "—"
            }
            list.add(PillEntry(text, getColor(R.color.text_muted)))
        }
        return list
    }

    // ---------- 胶囊轮播 ----------

    private fun updatePill(entries: List<PillEntry>) {
        // 文案没变就不打断轮播；颜色变了只刷新指示灯
        val same = entries.size == pillEntries.size &&
            entries.indices.all { entries[it].text == pillEntries[it].text }
        val reset = !same || pillIndex >= entries.size
        if (reset) pillIndex = 0
        pillEntries = entries
        applyPillText(false)
        if (reset) scheduleRotation()
    }

    /** 自动轮播关掉、或只有一条渠道时都不排定时。 */
    private fun scheduleRotation() {
        ui.removeCallbacks(rotateRunnable)
        if (!store.pillAutoScroll) return
        if (compactView == null || pillEntries.size <= 1) return
        ui.postDelayed(rotateRunnable, store.pillIntervalSec * 1000L)
    }

    /** 单击手动切下一条；只有一条时退化成刷新。 */
    private fun advancePill() {
        if (compactView == null) return
        if (pillEntries.size <= 1) {
            refreshNow()
            return
        }
        pillIndex = (pillIndex + 1) % pillEntries.size
        applyPillText(true)
        scheduleRotation()
    }

    private fun applyPillText(animate: Boolean) {
        val tv = compactView?.findViewById<TextView>(R.id.tvPill) ?: return
        val entry = pillEntries.getOrNull(pillIndex)
        val raw = entry?.text ?: "—"
        val text = if (raw.length > 34) raw.take(33) + "…" else raw
        compactView?.findViewById<View>(R.id.dotPill)?.let {
            val c = entry?.color ?: getColor(R.color.text_muted)
            Ui.tintDot(it, c)
            android.util.Log.d(
                "BFDOT",
                "c=" + Integer.toHexString(c) +
                    " bg=" + Integer.toHexString((it.background as? android.graphics.drawable.ColorDrawable)?.color ?: -1) +
                    " alpha=" + it.alpha +
                    " tint=" + it.backgroundTintList
            )
        }
        if (!animate) {
            tv.animate().cancel()
            tv.text = text
            tv.alpha = 1f
            tv.translationY = 0f
            return
        }
        tv.animate().cancel()
        tv.animate().alpha(0f).translationY(-dp(6).toFloat()).setDuration(120).withEndAction {
            tv.text = text
            tv.translationY = dp(6).toFloat()
            tv.animate().alpha(1f).translationY(0f).setDuration(160).start()
        }.start()
    }

    private fun detailRow(p: Provider, r: BalanceRepo.Result?): View {
        if (p.isQuota()) return quotaRow(p, r)
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(5), 0, dp(5))
        }
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dot = View(this).apply {
            background = getDrawable(R.drawable.bg_dot_glow)
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
        }
        Ui.tintDot(dot, Ui.dotColor(this@OverlayService, p, r))
        val name = TextView(this).apply {
            text = p.name.ifBlank { p.kind.label }
            setTextColor(getColor(R.color.text_sub))
            textSize = 11f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(2) }
        }
        val value = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END
            maxLines = 3
            when {
                r == null -> {
                    text = "—"
                    setTextColor(getColor(R.color.text_muted))
                }
                r.ok -> {
                    text = if (r.amounts.size > 1) Fmt.moneyLines(r.amounts)
                    else Fmt.money(r.amount, r.unit.ifBlank { p.unit })
                    setTextColor(
                        when (BalanceRepo.warnLevel(p, r)) {
                            2 -> getColor(R.color.red)
                            1 -> getColor(R.color.amber)
                            else -> getColor(R.color.text)
                        }
                    )
                }
                else -> {
                    text = "失败"
                    setTextColor(getColor(R.color.red))
                }
            }
        }
        line.addView(dot)
        line.addView(name)
        line.addView(value)
        wrap.addView(line)

        if (r != null && !r.ok) {
            wrap.addView(
                TextView(this).apply {
                    text = r.error
                    setTextColor(getColor(R.color.red))
                    textSize = 9f
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, dp(2), 0, 0)
                    alpha = 0.85f
                }
            )
        }

        if (r != null && r.ok && r.note.isNotBlank()) {
            wrap.addView(
                TextView(this).apply {
                    text = r.note
                    setTextColor(getColor(R.color.text_muted))
                    textSize = 8f
                    setPadding(0, dp(2), 0, 0)
                }
            )
        }
        return wrap
    }

    private fun quotaRow(p: Provider, r: BalanceRepo.Result?): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dot = View(this).apply {
            background = getDrawable(R.drawable.bg_dot_glow)
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
        }
        Ui.tintDot(dot, Ui.dotColor(this@OverlayService, p, r))
        val name = TextView(this).apply {
            text = p.name.ifBlank { p.kind.label }
            setTextColor(getColor(R.color.text_sub))
            textSize = 11f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(2) }
        }
        val peak = BalanceRepo.peakOf(r)
        val value = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            when {
                r == null -> {
                    text = "—"
                    setTextColor(getColor(R.color.text_muted))
                }
                !r.ok -> {
                    text = "失败"
                    setTextColor(getColor(R.color.red))
                }
                else -> {
                    text = Fmt.percent(peak)
                    setTextColor(peak?.let { Fmt.quotaColor(this@OverlayService, it) } ?: getColor(R.color.text))
                }
            }
        }
        line.addView(dot)
        line.addView(name)
        line.addView(value)
        wrap.addView(line)

        if (r != null && r.ok) {
            r.windows.forEach { w ->
                val wl = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(4), 0, 0)
                }
                wl.addView(
                    TextView(this).apply {
                        text = w.label + " " + Fmt.percent(w.usedPercent)
                        setTextColor(getColor(R.color.text_sub))
                        textSize = 9f
                        layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                    }
                )
                wl.addView(
                    TextView(this).apply {
                        text = Fmt.resetIn(w.resetAt)
                        setTextColor(getColor(R.color.text_muted))
                        textSize = 9f
                    }
                )
                wrap.addView(wl)
                wrap.addView(
                    ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                        max = 100
                        progress = w.usedPercent.toInt().coerceIn(0, 100)
                        progressTintList = ColorStateList.valueOf(Fmt.quotaColor(this@OverlayService, w.usedPercent))
                        progressBackgroundTintList = ColorStateList.valueOf(0x22FFFFFF)
                    },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3))
                )
                if (w.detail.isNotBlank()) {
                    wrap.addView(
                        TextView(this).apply {
                            text = w.detail
                            setTextColor(getColor(R.color.text_muted))
                            textSize = 8f
                            setPadding(0, dp(1), 0, 0)
                        }
                    )
                }
            }
            if (r.note.isNotBlank()) {
                wrap.addView(
                    TextView(this).apply {
                        text = r.note
                        setTextColor(getColor(R.color.text_muted))
                        textSize = 8f
                        setPadding(0, dp(3), 0, 0)
                    }
                )
            }
        } else if (r != null) {
            wrap.addView(
                TextView(this).apply {
                    text = r.error
                    setTextColor(getColor(R.color.red))
                    textSize = 9f
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, dp(2), 0, 0)
                }
            )
        }
        return wrap
    }

    private fun label(text: String, colorRes: Int, size: Float): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(getColor(colorRes))
            textSize = size
            setPadding(0, dp(4), 0, dp(4))
        }

    // ---------- 通知 ----------

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL_ID, "余额监控", NotificationManager.IMPORTANCE_LOW).apply {
            description = "悬浮窗运行状态"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("余额浮窗")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(pi)
            .build()
    }

    private fun startForegroundCompat() {
        val n = buildNotification("正在启动…")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification(entries: List<PillEntry>) {
        val text = entries.take(3).joinToString(" · ") { it.text }.ifBlank { "暂无可用数据" }
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
        }
    }

    // ---------- 小工具 ----------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) {
        runCatching { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }
}
