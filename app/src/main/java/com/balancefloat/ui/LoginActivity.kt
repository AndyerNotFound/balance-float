package com.balancefloat.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.balancefloat.R

/**
 * 网页登录：加载官方登录页，登录成功后从 WebView 的 cookie 里取凭据。
 *
 * 只读 cookie，不注入任何脚本去读表单、不记录账号密码。
 * CookieManager 拿不到 HttpOnly 时，退回拦截带 Cookie 头的 /api 请求。
 */
class LoginActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_KIND = "kind"
        const val KIND_CLAUDE = "claude"
        const val KIND_OPENCODE = "opencode"
        const val RESULT_COOKIE = "cookie"
        const val RESULT_WORKSPACE = "workspace"

        fun intent(ctx: Context, kind: String): Intent =
            Intent(ctx, LoginActivity::class.java).putExtra(EXTRA_KIND, kind)
    }

    private lateinit var web: WebView
    private lateinit var tvTitle: TextView
    private lateinit var tvHint: TextView
    private lateinit var pb: ProgressBar
    private val ui = Handler(Looper.getMainLooper())

    private var kind = KIND_CLAUDE
    private var done = false
    private var workspace = ""

    @Volatile
    private var captured: String? = null

    private val grab = Runnable { tryGrab() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        kind = intent.getStringExtra(EXTRA_KIND).orEmpty().ifBlank { KIND_CLAUDE }
        web = findViewById(R.id.web)
        tvTitle = findViewById(R.id.tvTitle)
        tvHint = findViewById(R.id.tvHint)
        pb = findViewById(R.id.pb)

        val isClaude = kind == KIND_CLAUDE
        tvTitle.text = if (isClaude) "登录 Claude" else "登录 OpenCode"
        tvHint.text = if (isClaude)
            "登录后自动取 sessionKey，不会记录账号密码"
        else
            "登录后自动取 auth cookie 和 workspace"

        findViewById<TextView>(R.id.btnClose).setOnClickListener { finish() }

        setupWeb()
        show(
            if (isClaude) "https://claude.ai/login" else "https://opencode.ai/auth"
        )

        ui.postDelayed({
            if (!done) tvHint.text = "没自动拿到？确认已完成登录；仍不行就返回手动填凭据"
        }, 90_000)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWeb() {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(web, true)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            // 部分站点对 WebView 的 "wv" 标记更严，去掉
            userAgentString = userAgentString.replace("; wv", "")
        }

        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                pb.visibility = ProgressBar.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                pb.visibility = ProgressBar.GONE
                ui.removeCallbacks(grab)
                ui.postDelayed(grab, 600)
                ui.postDelayed(grab, 1_800)
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                if (!done && request != null) {
                    val u = request.url?.toString().orEmpty()
                    if (u.contains("/api/") || u.contains("/v1/")) {
                        val c = request.requestHeaders?.get("Cookie")
                        if (!c.isNullOrBlank()) {
                            val v = cookieValue(c, cookieName())
                            if (!v.isNullOrBlank()) {
                                captured = v
                                ui.post { if (!done) tryGrab() }
                            }
                        }
                    }
                }
                return null
            }
        }
    }

    private fun show(url: String) {
        web.loadUrl(url)
    }

    private fun domain(): String =
        if (kind == KIND_CLAUDE) "https://claude.ai" else "https://opencode.ai"

    private fun cookieName(): String =
        if (kind == KIND_CLAUDE) "sessionKey" else "auth"

    private fun tryGrab() {
        if (done) return
        val fromCookie = cookieValue(
            CookieManager.getInstance().getCookie(domain()).orEmpty(),
            cookieName()
        )
        val v = fromCookie?.takeIf { it.isNotBlank() } ?: captured?.takeIf { it.isNotBlank() }
        if (v.isNullOrBlank()) return

        if (kind == KIND_OPENCODE) {
            web.evaluateJavascript(
                "(function(){var h=document.documentElement.innerHTML||'';" +
                    "var m=h.match(/wrk_[A-Za-z0-9]{6,}/);return m?m[0]:'';})()"
            ) { s ->
                if (done) return@evaluateJavascript
                workspace = s?.trim('"')?.takeIf { it.startsWith("wrk_") }.orEmpty()
                finishWith(v)
            }
        } else {
            finishWith(v)
        }
    }

    private fun finishWith(v: String) {
        if (done) return
        done = true
        val data = Intent().putExtra(RESULT_COOKIE, v)
        if (workspace.isNotBlank()) data.putExtra(RESULT_WORKSPACE, workspace)
        setResult(RESULT_OK, data)
        finish()
    }

    private fun cookieValue(raw: String, name: String): String? =
        raw.split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')
            ?.takeIf { it.isNotBlank() }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        runCatching {
            web.loadUrl("about:blank")
            web.destroy()
        }
        super.onDestroy()
    }
}
