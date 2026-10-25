package com.balancefloat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.balancefloat.data.BalanceRepo
import com.balancefloat.data.CodexOAuth
import com.balancefloat.data.GeminiOAuth
import com.balancefloat.data.Kind
import com.balancefloat.data.Money
import com.balancefloat.data.Provider
import com.balancefloat.data.QuotaWindow
import com.balancefloat.data.Store
import com.balancefloat.ui.EditorDialog
import com.balancefloat.ui.Fmt
import com.balancefloat.ui.LoginActivity
import com.balancefloat.ui.OverlayService
import com.balancefloat.ui.Ui
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var store: Store
    private lateinit var listProviders: LinearLayout
    private lateinit var tvStatus: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var tvInterval: TextView
    private lateinit var tvPillInterval: TextView
    private lateinit var btnOverlay: TextView
    private lateinit var btnRefresh: TextView
    private lateinit var swAuto: MaterialSwitch
    private lateinit var swBlur: MaterialSwitch
    private lateinit var swScrollApp: MaterialSwitch
    private lateinit var swLockApp: MaterialSwitch
    private lateinit var swPinApp: MaterialSwitch
    private lateinit var tvOverlaySize: TextView
    private lateinit var tvOverlayColor: TextView
    private lateinit var rowBlur: View

    private var pendingOverlayStart = false

    private lateinit var tvLoginCodex: TextView
    private lateinit var tvLoginClaude: TextView
    private lateinit var tvLoginOpenCode: TextView
    private lateinit var tvLoginGemini: TextView

    /** 网页登录回来时认领是哪一家 */
    private var pendingLogin = ""

    private val loginLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val kind = pendingLogin
            pendingLogin = ""
            if (res.resultCode != RESULT_OK) return@registerForActivityResult
            val cookie = res.data?.getStringExtra(LoginActivity.RESULT_COOKIE).orEmpty()
            val ws = res.data?.getStringExtra(LoginActivity.RESULT_WORKSPACE).orEmpty()
            if (cookie.isBlank()) {
                toast("没拿到凭据")
                return@registerForActivityResult
            }
            when (kind) {
                LoginActivity.KIND_CLAUDE -> saveCookieLogin(Kind.CLAUDE_QUOTA, cookie, "")
                LoginActivity.KIND_OPENCODE -> saveCookieLogin(Kind.OPENCODE_GO, cookie, ws)
            }
        }

    private val repoListener: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        applyBackground()
        applySystemInsets()
        store = Store(this)

        listProviders = findViewById(R.id.listProviders)
        tvStatus = findViewById(R.id.tvStatus)
        tvEmpty = findViewById(R.id.tvEmpty)
        tvInterval = findViewById(R.id.tvInterval)
        tvPillInterval = findViewById(R.id.tvPillInterval)
        btnOverlay = findViewById(R.id.btnOverlay)
        btnRefresh = findViewById(R.id.btnRefresh)
        swAuto = findViewById(R.id.swAuto)
        swBlur = findViewById(R.id.swBlur)
        swScrollApp = findViewById(R.id.swScrollApp)
        swLockApp = findViewById(R.id.swLockApp)
        swPinApp = findViewById(R.id.swPinApp)
        tvOverlaySize = findViewById(R.id.tvOverlaySize)
        tvOverlayColor = findViewById(R.id.tvOverlayColor)
        rowBlur = findViewById(R.id.rowOverlayBlur)

        findViewById<View>(R.id.btnAdd).setOnClickListener { openEditor(null) }
        btnOverlay.setOnClickListener { toggleOverlay() }
        btnRefresh.setOnClickListener { manualRefresh() }
        findViewById<View>(R.id.rowInterval).setOnClickListener { pickInterval() }
        findViewById<View>(R.id.rowPillInterval).setOnClickListener { pickPillInterval() }

        swAuto.isChecked = store.autoShow
        swAuto.setOnCheckedChangeListener { _, checked -> store.autoShow = checked }

        rowBlur.visibility = if (Build.VERSION.SDK_INT >= 31) View.VISIBLE else View.GONE
        swBlur.isChecked = store.overlayBlur
        swBlur.setOnCheckedChangeListener { _, checked ->
            store.overlayBlur = checked
            if (checked) toast("部分 ROM（ColorOS）会把整屏一起糊掉，可再关掉")
            OverlayService.reload(this)
        }

        tvInterval.text = Fmt.intervalLabel(store.intervalSec)
        tvPillInterval.text = Fmt.pillIntervalLabel(store.pillIntervalSec)

        swScrollApp.isChecked = store.pillAutoScroll
        swScrollApp.setOnCheckedChangeListener { _, on ->
            store.pillAutoScroll = on
            OverlayService.pushLook(this)
        }

        swLockApp.isChecked = store.lockPosition
        swLockApp.setOnCheckedChangeListener { _, on ->
            store.lockPosition = on
            OverlayService.pushLook(this)
        }

        swPinApp.isChecked = store.pinExpanded
        swPinApp.setOnCheckedChangeListener { _, on ->
            store.pinExpanded = on
            OverlayService.pushLook(this)
        }

        findViewById<View>(R.id.rowOverlaySize).setOnClickListener { showOverlaySizeDialog() }
        findViewById<View>(R.id.rowOverlayColor).setOnClickListener { showOverlayColorDialog() }

        tvLoginCodex = findViewById(R.id.tvLoginCodex)
        tvLoginClaude = findViewById(R.id.tvLoginClaude)
        tvLoginOpenCode = findViewById(R.id.tvLoginOpenCode)
        tvLoginGemini = findViewById(R.id.tvLoginGemini)
        findViewById<View>(R.id.rowLoginCodex).setOnClickListener { loginCodex() }
        findViewById<View>(R.id.rowLoginClaude).setOnClickListener {
            pendingLogin = LoginActivity.KIND_CLAUDE
            loginLauncher.launch(LoginActivity.intent(this, LoginActivity.KIND_CLAUDE))
        }
        findViewById<View>(R.id.rowLoginOpenCode).setOnClickListener {
            pendingLogin = LoginActivity.KIND_OPENCODE
            loginLauncher.launch(LoginActivity.intent(this, LoginActivity.KIND_OPENCODE))
        }
        findViewById<View>(R.id.rowLoginGemini).setOnClickListener { loginGemini() }

        refreshLoginLabels()
        applyBlobBlur()

        // 仅 debug：`am start --ez open_editor true --es provider_id <id>` 便于无头验证编辑器
        if (BuildConfig.DEBUG && intent?.getBooleanExtra("open_editor", false) == true) {
            window.decorView.postDelayed({
                val id = intent?.getStringExtra("provider_id")
                val p = store.providers().firstOrNull { it.id == id } ?: store.providers().firstOrNull()
                if (p != null) openEditor(p)
            }, 350)
        }
    }

    override fun onResume() {
        super.onResume()
        BalanceRepo.addListener(repoListener)
        render()
        if (this::tvLoginCodex.isInitialized) refreshLoginLabels()

        if (pendingOverlayStart && Settings.canDrawOverlays(this)) {
            pendingOverlayStart = false
            requestNotifThenStart()
        } else if (store.autoShow && store.overlayOn && !OverlayService.isRunning) {
            requestNotifThenStart()
        }

        if (store.intervalSec > 0) {
            val last = maxOf(BalanceRepo.lastUpdateAt(), store.lastRefreshAt)
            val stale = last == 0L || System.currentTimeMillis() - last > store.intervalSec * 1000L
            if (stale && store.providers().any { it.enabled && (it.apiKey.isNotBlank() || it.auth == "none") }) {
                BalanceRepo.refreshAll(store.providers()) {
                    store.lastRefreshAt = System.currentTimeMillis()
                    render()
                }
            }
        }
    }

    override fun onPause() {
        BalanceRepo.removeListener(repoListener)
        super.onPause()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIF) startOverlayFinal()
    }

    /** targetSdk 35+ 强制 edge-to-edge，手动让出状态栏/导航栏。 */
    private fun applySystemInsets() {
        val root = findViewById<View>(R.id.rootScroll)
        val scrim = findViewById<View>(R.id.statusBarScrim)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            if (scrim.layoutParams.height != bars.top) {
                scrim.layoutParams = scrim.layoutParams.apply { height = bars.top }
            }
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /**
     * 全屏渐变底：顶部混一点主色、底部混一点次色。
     * 状态栏遮罩用同一个顶部色，滚动内容不会压到状态栏图标。
     */
    private fun applyBackground() {
        val root = findViewById<View>(R.id.root)
        val scrim = findViewById<View>(R.id.statusBarScrim)
        val surface = MaterialColors.getColor(root, com.google.android.material.R.attr.colorSurface)
        val primary = MaterialColors.getColor(root, com.google.android.material.R.attr.colorPrimary)
        val secondary = MaterialColors.getColor(root, com.google.android.material.R.attr.colorSecondary)
        val top = ColorUtils.blendARGB(surface, primary, 0.18f)
        val bottom = ColorUtils.blendARGB(surface, secondary, 0.10f)
        val colors = intArrayOf(top, surface, bottom)
        root.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors).apply {
            setColors(colors, floatArrayOf(0f, 0.45f, 1f))
        }
        scrim.background = ColorDrawable(top)
    }

    /** Android 12+ 用 RenderEffect 把环境光斑真正模糊掉 */
    private fun applyBlobBlur() {
        if (Build.VERSION.SDK_INT < 31) return
        runCatching {
            val blur = RenderEffect.createBlurEffect(70f, 70f, Shader.TileMode.CLAMP)
            findViewById<View>(R.id.blobBlue).setRenderEffect(blur)
            findViewById<View>(R.id.blobTeal).setRenderEffect(blur)
            findViewById<View>(R.id.blobBottom).setRenderEffect(blur)
        }
    }

    // ---------- 渲染 ----------

    private fun render() {
        val providers = store.providers()
        val results = BalanceRepo.results()

        listProviders.removeAllViews()
        providers.forEach { p -> listProviders.addView(buildRow(p, results[p.id])) }
        tvEmpty.visibility = if (providers.isEmpty()) View.VISIBLE else View.GONE

        val (ok, bad) = BalanceRepo.statusOf(providers)
        val enabledCount = providers.count { it.enabled }
        val last = BalanceRepo.lastUpdateAt()
        tvStatus.text = when {
            providers.isEmpty() -> "余额与订阅额度，钉在屏幕上"
            enabledCount == 0 -> "没有启用的渠道"
            BalanceRepo.refreshing -> "刷新中… · $enabledCount 个渠道"
            ok + bad == 0 -> "$enabledCount 个渠道 · 尚未查询"
            bad == 0 -> "$ok/$enabledCount 正常 · ${Fmt.ago(last)}"
            else -> "$ok/$enabledCount 正常 · $bad 个失败 · ${Fmt.ago(last)}"
        }

        btnOverlay.text = if (OverlayService.isRunning) "隐藏悬浮窗" else "显示悬浮窗"
        btnRefresh.text = if (BalanceRepo.refreshing) "刷新中" else "刷新"
        tvInterval.text = Fmt.intervalLabel(store.intervalSec)
        tvOverlaySize.text = overlaySizeLabel()
        tvOverlayColor.text = overlayColorLabel()
    }

    private fun overlaySizeLabel(): String {
        val pw = store.pillWidthDp
        val ph = store.pillHeightDp
        val dw = store.detailWidthDp
        if (pw == 0 && ph == 0 && dw == 0) return "自动"
        fun v(x: Int) = if (x == 0) "自动" else x.toString() + "dp"
        return "胶囊 " + v(pw) + "×" + v(ph) + " · 详情 " + v(dw)
    }

    private fun overlayColorLabel(): String {
        fun name(c: Int) = Ui.BG_PRESETS.firstOrNull { it.second == c }?.first ?: "自定义"
        return if (store.pillBg == 0 && store.detailBg == 0) "默认"
        else name(store.pillBg) + " / " + name(store.detailBg)
    }

    private fun buildRow(p: Provider, r: BalanceRepo.Result?): View {
        val row = layoutInflater.inflate(R.layout.item_provider, listProviders, false)
        val dot = row.findViewById<View>(R.id.dot)
        val name = row.findViewById<TextView>(R.id.tvName)
        val sub = row.findViewById<TextView>(R.id.tvSub)
        val amount = row.findViewById<TextView>(R.id.tvAmount)
        val unit = row.findViewById<TextView>(R.id.tvUnit)
        val windowsBox = row.findViewById<LinearLayout>(R.id.windowsBox)

        name.text = p.name.ifBlank { p.kind.label }
        row.alpha = if (p.enabled) 1f else 0.45f

        if (p.isQuota()) {
            val peak = BalanceRepo.peakOf(r)
            Ui.tintDot(dot, Ui.dotColor(this, p, r))

            when {
                !p.enabled -> {
                    sub.text = "已停用 · " + p.kind.label
                    sub.setTextColor(getColor(R.color.text_muted))
                }
                p.apiKey.isBlank() && p.auth != "none" -> {
                    sub.text = "未填 Token · " + p.kind.label
                    sub.setTextColor(getColor(R.color.amber))
                }
                r == null -> {
                    sub.text = p.kind.hint
                    sub.setTextColor(getColor(R.color.text_muted))
                }
                !r.ok -> {
                    sub.text = r.error
                    sub.setTextColor(getColor(R.color.red))
                }
                else -> {
                    // 具体窗口交给下面的进度条，避免同一串数字出现两遍
                    sub.text = buildString {
                        append(Fmt.clock(r.at)).append(" 更新")
                        if (r.note.isNotBlank()) append(" · ").append(r.note)
                    }
                    sub.setTextColor(getColor(R.color.text_muted))
                }
            }

            amount.text = if (peak != null) Fmt.percent(peak) else "—"
            amount.setTextColor(if (peak != null) Fmt.quotaColor(this, peak) else getColor(R.color.text_muted))
            unit.text = if (r?.ok == true) {
                r.windows.maxByOrNull { it.usedPercent }?.label ?: "已用"
            } else ""

            windowsBox.removeAllViews()
            if (r != null && r.ok && r.windows.isNotEmpty()) {
                windowsBox.visibility = View.VISIBLE
                r.windows.forEach { w -> windowsBox.addView(buildWindowRow(w)) }
            } else {
                windowsBox.visibility = View.GONE
            }
        } else {
            windowsBox.visibility = View.GONE
            val level = BalanceRepo.warnLevel(p, r)
            Ui.tintDot(dot, Ui.dotColor(this, p, r))

            when {
                !p.enabled -> {
                    sub.text = "已停用 · " + p.kind.label
                    sub.setTextColor(getColor(R.color.text_muted))
                }
                p.apiKey.isBlank() && p.auth != "none" -> {
                    sub.text = "未填 API Key · " + p.kind.label
                    sub.setTextColor(getColor(R.color.amber))
                }
                r == null -> {
                    sub.text = p.kind.hint
                    sub.setTextColor(getColor(R.color.text_muted))
                }
                r.ok -> {
                    sub.text = buildString {
                        append(p.kind.label).append(" · ").append(Fmt.clock(r.at)).append(" 更新")
                        if (r.note.isNotBlank()) append(" · ").append(r.note)
                        when (level) {
                            2 -> append(" · 极低余额")
                            1 -> append(" · 低余额")
                            else -> Unit
                        }
                    }
                    sub.setTextColor(
                        when (level) {
                            2 -> getColor(R.color.red)
                            1 -> getColor(R.color.amber)
                            else -> getColor(R.color.text_muted)
                        }
                    )
                }
                else -> {
                    sub.text = r.error
                    sub.setTextColor(getColor(R.color.red))
                }
            }

            if (r != null && r.ok) {
                val amounts = r.amounts.ifEmpty {
                    r.amount?.let { listOf(Money(it, r.unit.ifBlank { p.unit })) } ?: emptyList()
                }
                val primary = amounts.firstOrNull { it.unit.equals(r.unit, true) }
                    ?: amounts.firstOrNull()
                val extras = if (primary == null) emptyList()
                else amounts.filter { !it.unit.equals(primary.unit, true) }
                if (primary != null && extras.isNotEmpty()) {
                    amount.text = Fmt.money(primary.value, primary.unit)
                    unit.text = extras.joinToString("  ") { Fmt.money(it.value, it.unit) }
                } else {
                    amount.text = Fmt.plain(r.amount)
                    unit.text = (r.unit.ifBlank { p.unit }).ifBlank { " " }
                }
                amount.setTextColor(
                    when (level) {
                        2 -> getColor(R.color.red)
                        1 -> getColor(R.color.amber)
                        else -> getColor(R.color.text)
                    }
                )
            } else {
                amount.text = "—"
                amount.setTextColor(getColor(R.color.text_muted))
                unit.text = p.unit.ifBlank { " " }
            }
        }

        row.setOnClickListener { openEditor(p) }
        row.setOnLongClickListener {
            confirmDelete(p)
            true
        }
        return row
    }

    /** 每层额度一行：标签 + 百分比 + 重置倒计时 + 细进度条 */
    private fun buildWindowRow(w: QuotaWindow): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(
            TextView(this).apply {
                text = w.label + "  " + Fmt.percent(w.usedPercent)
                setTextColor(getColor(R.color.text_sub))
                textSize = 10f
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
        )
        head.addView(
            TextView(this).apply {
                text = Fmt.resetIn(w.resetAt)
                setTextColor(getColor(R.color.text_muted))
                textSize = 9f
            }
        )
        wrap.addView(head)

        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = w.usedPercent.toInt().coerceIn(0, 100)
            progressTintList = ColorStateList.valueOf(Fmt.quotaColor(this@MainActivity, w.usedPercent))
            progressBackgroundTintList = ColorStateList.valueOf(0x22FFFFFF)
        }
        wrap.addView(
            bar,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4)).apply {
                topMargin = dp(3)
            }
        )
        return wrap
    }

    // ---------- 操作 ----------

    private fun openEditor(existing: Provider?) {
        EditorDialog.show(
            this,
            existing,
            onSave = { p ->
                store.upsert(p)
                store.lastRefreshAt = 0L
                BalanceRepo.refreshAll(store.providers()) {
                    store.lastRefreshAt = System.currentTimeMillis()
                    render()
                }
                render()
                OverlayService.reload(this)
            },
            onDelete = { p ->
                store.remove(p.id)
                render()
                OverlayService.reload(this)
            }
        )
    }

    private fun confirmDelete(p: Provider) {
        AlertDialog.Builder(this)
            .setTitle("删除渠道")
            .setMessage("删除「" + p.name.ifBlank { p.kind.label } + "」？")
            .setPositiveButton("删除") { _, _ ->
                store.remove(p.id)
                render()
                OverlayService.reload(this)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun manualRefresh() {
        if (store.providers().none { it.enabled }) {
            toast("先添加一个渠道")
            return
        }
        BalanceRepo.refreshAll(store.providers()) {
            store.lastRefreshAt = System.currentTimeMillis()
            render()
        }
        render()
    }

    private fun pickInterval() {
        val labels = arrayOf("1 分钟", "5 分钟", "15 分钟", "30 分钟", "手动刷新")
        val values = intArrayOf(60, 300, 900, 1800, 0)
        val idx = values.indexOf(store.intervalSec).let { if (it >= 0) it else 1 }
        AlertDialog.Builder(this)
            .setTitle("刷新间隔")
            .setSingleChoiceItems(labels, idx) { d, which ->
                store.intervalSec = values[which]
                tvInterval.text = Fmt.intervalLabel(store.intervalSec)
                OverlayService.reload(this)
                d.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun pickPillInterval() {
        val labels = arrayOf("1 秒", "2 秒", "3 秒", "4 秒", "5 秒", "8 秒", "10 秒")
        val values = intArrayOf(1, 2, 3, 4, 5, 8, 10)
        val idx = values.indexOf(store.pillIntervalSec).let { if (it >= 0) it else 3 }
        AlertDialog.Builder(this)
            .setTitle("胶囊轮播间隔")
            .setSingleChoiceItems(labels, idx) { d, which ->
                store.pillIntervalSec = values[which]
                tvPillInterval.text = Fmt.pillIntervalLabel(store.pillIntervalSec)
                OverlayService.reload(this)
                d.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------- 悬浮窗外观 ----------

    // ---------- 账号登录 ----------

    /** Codex：系统浏览器走 OAuth，本机 1455 收回调。 */
    private fun loginCodex() {
        toast("已打开浏览器，登录后切回 App")
        thread {
            val r = CodexOAuth.login { url ->
                runOnUiThread {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                        .onFailure { toast("没有可用浏览器") }
                }
            }
            runOnUiThread {
                r.onSuccess { t ->
                    val base = store.providers().firstOrNull { it.kind == Kind.CODEX_QUOTA }
                        ?: Provider.preset(Kind.CODEX_QUOTA)
                    store.upsert(
                        base.copy(
                            apiKey = t.access,
                            refreshToken = t.refresh,
                            tokenExpiresAt = t.expiresAt,
                            accountId = t.accountId.ifBlank { base.accountId },
                            enabled = true
                        )
                    )
                    afterLogin("Codex" + if (t.plan.isNotBlank()) "（${t.plan}）" else "")
                }.onFailure { e ->
                    toast("登录失败：" + (e.message ?: "未知错误"))
                }
            }
        }
    }

    /** Gemini：Google OAuth，随机 loopback 端口收回调。 */
    private fun loginGemini() {
        toast("已打开浏览器，登录后切回 App")
        thread {
            val r = GeminiOAuth.login { url ->
                runOnUiThread {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                        .onFailure { toast("没有可用浏览器") }
                }
            }
            runOnUiThread {
                r.onSuccess { t ->
                    val base = store.providers().firstOrNull { it.kind == Kind.GEMINI_QUOTA }
                        ?: Provider.preset(Kind.GEMINI_QUOTA)
                    store.upsert(
                        base.copy(
                            apiKey = t.access,
                            refreshToken = t.refresh,
                            tokenExpiresAt = t.expiresAt,
                            enabled = true
                        )
                    )
                    afterLogin("Gemini")
                }.onFailure { e ->
                    toast("登录失败：" + (e.message ?: "未知错误"))
                }
            }
        }
    }

    /** Claude / OpenCode：WebView 登录回来，写进对应渠道。 */
    private fun saveCookieLogin(kind: Kind, cookie: String, ws: String) {
        val base = store.providers().firstOrNull { it.kind == kind } ?: Provider.preset(kind)
        store.upsert(
            base.copy(
                apiKey = cookie,
                workspaceId = if (ws.isNotBlank()) ws else base.workspaceId,
                enabled = true
            )
        )
        afterLogin(kind.label)
    }

    private fun afterLogin(label: String) {
        refreshLoginLabels()
        toast("$label 已登录")
        BalanceRepo.refreshAll(store.providers()) { render() }
        OverlayService.reload(this)
    }

    private fun refreshLoginLabels() {
        val ps = store.providers()

        val codex = ps.firstOrNull { it.kind == Kind.CODEX_QUOTA }
        tvLoginCodex.text = if (codex == null || codex.apiKey.isBlank()) {
            "浏览器授权 · token 自动续期"
        } else {
            val left = (codex.tokenExpiresAt - System.currentTimeMillis()).coerceAtLeast(0)
            "已登录" + if (left > 0) " · 剩 " + leftLabel(left) else ""
        }

        val claude = ps.firstOrNull { it.kind == Kind.CLAUDE_QUOTA }
        tvLoginClaude.text = if (claude == null || claude.apiKey.isBlank()) {
            "网页登录 · 自动取 sessionKey"
        } else {
            "已登录 · sessionKey 已保存"
        }

        val oc = ps.firstOrNull { it.kind == Kind.OPENCODE_GO }
        tvLoginOpenCode.text = if (oc == null || oc.apiKey.isBlank()) {
            "网页登录 · 自动取 auth cookie"
        } else if (oc.workspaceId.isBlank()) {
            "已登录 · 还缺 workspace id"
        } else {
            "已登录 · " + oc.workspaceId.take(11) + "…"
        }

        val gm = ps.firstOrNull { it.kind == Kind.GEMINI_QUOTA }
        tvLoginGemini.text = if (gm == null || gm.apiKey.isBlank()) {
            "Google 授权 · Code Assist 配额"
        } else {
            val left = (gm.tokenExpiresAt - System.currentTimeMillis()).coerceAtLeast(0)
            "已登录" + if (left > 0) " · 剩 " + leftLabel(left) else ""
        }
    }

    private fun leftLabel(ms: Long): String {
        val m = (ms / 60_000).coerceAtLeast(0)
        return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
    }

    private fun showOverlaySizeDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(12), dp(22), 0)
        }

        fun addRow(title: String, max: Int, value: Int, write: (Int) -> Unit) {
            val head = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, 0)
            }
            val label = TextView(this).apply {
                text = if (value == 0) "自动" else value.toString() + "dp"
                setTextColor(getColor(R.color.text_sub))
                textSize = 12f
            }
            head.addView(
                TextView(this).apply {
                    text = title
                    setTextColor(getColor(R.color.text))
                    textSize = 13f
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                }
            )
            head.addView(label)
            box.addView(head)

            val bar = SeekBar(this).apply {
                this.max = max
                progress = value
            }
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    label.text = if (p == 0) "自动" else p.toString() + "dp"
                }

                override fun onStartTrackingTouch(sb: SeekBar) = Unit

                override fun onStopTrackingTouch(sb: SeekBar) {
                    write(sb.progress)
                    tvOverlaySize.text = overlaySizeLabel()
                    OverlayService.pushLook(this@MainActivity)
                }
            })
            box.addView(bar)
        }

        addRow("胶囊宽度", 320, store.pillWidthDp) { store.pillWidthDp = it }
        addRow("胶囊高度", 120, store.pillHeightDp) { store.pillHeightDp = it }
        addRow("详情宽度", 380, store.detailWidthDp) { store.detailWidthDp = it }
        box.addView(
            TextView(this).apply {
                text = "0 = 随内容自适应"
                setTextColor(getColor(R.color.text_muted))
                textSize = 11f
                setPadding(0, dp(4), 0, dp(4))
            }
        )

        AlertDialog.Builder(this)
            .setTitle("悬浮窗尺寸")
            .setView(box)
            .setPositiveButton("完成", null)
            .show()
    }

    private fun showOverlayColorDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(12), dp(22), dp(8))
        }

        fun caption(t: String) = TextView(this).apply {
            text = t
            setTextColor(getColor(R.color.text_sub))
            textSize = 12f
            setPadding(0, dp(10), 0, dp(6))
        }

        val rowPill = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val rowDetail = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        box.addView(caption("胶囊"))
        box.addView(rowPill)
        box.addView(caption("详情卡片"))
        box.addView(rowDetail)

        lateinit var sync: () -> Unit
        fun commit(after: () -> Unit) {
            after()
            sync()
            tvOverlayColor.text = overlayColorLabel()
            OverlayService.pushLook(this)
        }
        sync = {
            Ui.fillSwatches(this, rowPill, store.pillBg) { c -> commit { store.pillBg = c } }
            Ui.fillSwatches(this, rowDetail, store.detailBg) { c -> commit { store.detailBg = c } }
        }
        sync()

        AlertDialog.Builder(this)
            .setTitle("悬浮窗配色")
            .setView(box)
            .setPositiveButton("完成", null)
            .show()
    }

    // ---------- 悬浮窗权限与启动 ----------

    private fun toggleOverlay() {
        if (OverlayService.isRunning) {
            store.overlayOn = false
            OverlayService.stop(this)
            btnOverlay.postDelayed({ render() }, 250)
            return
        }
        requestNotifThenStart()
    }

    private fun requestNotifThenStart() {
        if (!Settings.canDrawOverlays(this)) {
            pendingOverlayStart = true
            toast("请允许「显示在其他应用上层」")
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            return
        }
        startOverlayFinal()
    }

    private fun startOverlayFinal() {
        if (!Settings.canDrawOverlays(this)) {
            pendingOverlayStart = true
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }
        store.overlayOn = true
        OverlayService.start(this)
        btnOverlay.postDelayed({ render() }, 350)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val REQ_NOTIF = 0x21
    }
}
