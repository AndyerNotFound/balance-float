package com.balancefloat.data

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLException

/**
 * 余额 / 订阅额度查询与缓存。全部在后台线程抓取，结果推回主线程。
 * 单例：Activity 和悬浮窗 Service 共用同一份数据。
 */
object BalanceRepo {

    data class Result(
        val id: String,
        val ok: Boolean,
        val amount: Double?,
        val unit: String,
        val error: String,
        val at: Long,
        val windows: List<QuotaWindow> = emptyList(),
        val note: String = "",
        /** 多币种余额；只有一项时与 amount/unit 相同 */
        val amounts: List<Money> = emptyList()
    )

    private data class HttpResult(val code: Int, val body: String)

    private val results = ConcurrentHashMap<String, Result>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(4)
    private val busy = AtomicBoolean(false)

    @Volatile
    private var pending: Pair<List<Provider>, (() -> Unit)?>? = null

    var refreshing: Boolean = false
        private set

    fun results(): Map<String, Result> = HashMap(results)

    fun resultOf(id: String): Result? = results[id]

    fun lastUpdateAt(): Long = results.values.maxOfOrNull { it.at } ?: 0L

    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    private fun notifyUi() {
        main.post {
            listeners.toList().forEach { runCatching { it() } }
        }
    }

    /** 并发刷新全部启用的渠道。正在刷新时排队一次。 */
    fun refreshAll(providers: List<Provider>, done: (() -> Unit)? = null) {
        if (!busy.compareAndSet(false, true)) {
            pending = providers to done
            return
        }

        val enabled = providers.filter { it.enabled && (it.apiKey.isNotBlank() || it.auth == "none") }
        val alive = providers.map { it.id }.toSet()
        results.keys.retainAll(alive)

        if (enabled.isEmpty()) {
            refreshing = false
            busy.set(false)
            notifyUi()
            done?.let { main.post(it) }
            drainPending()
            return
        }

        refreshing = true
        notifyUi()
        val left = AtomicInteger(enabled.size)
        for (p in enabled) {
            pool.execute {
                val r = fetch(p)
                results[p.id] = r
                if (left.decrementAndGet() == 0) {
                    refreshing = false
                    busy.set(false)
                    notifyUi()
                    done?.let { cb -> main.post(cb) }
                    drainPending()
                }
            }
        }
    }

    private fun drainPending() {
        val next = pending ?: return
        pending = null
        refreshAll(next.first, next.second)
    }

    /** 单个渠道测试（编辑器用）。 */
    fun test(p: Provider, cb: (Result) -> Unit) {
        pool.execute {
            main.post { cb(fetch(p)) }
        }
    }

    // ---------- 网络 ----------

    private fun fetch(p: Provider): Result = try {
        if (p.isQuota()) fetchQuota(p) else fetchBalance(p)
    } catch (e: SocketTimeoutException) {
        fail(p, "请求超时")
    } catch (e: UnknownHostException) {
        fail(p, "域名解析失败")
    } catch (e: SSLException) {
        fail(p, "TLS 证书校验失败")
    } catch (e: Exception) {
        fail(p, e.message?.take(80) ?: e.javaClass.simpleName)
    }

    private fun httpGet(url: String, p: Provider, extra: Map<String, String> = emptyMap()): HttpResult {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.requestMethod = "GET"
            c.connectTimeout = 12_000
            c.readTimeout = 15_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", "BalanceFloat/0.2 (Android)")
            when (p.auth) {
                "none" -> Unit
                "cookie" -> c.setRequestProperty(
                    "Cookie",
                    p.cookieName.ifBlank { "sessionKey" } + "=" + p.apiKey
                )
                "x-api-key" -> c.setRequestProperty("x-api-key", p.apiKey)
                else -> c.setRequestProperty("Authorization", "Bearer " + p.apiKey)
            }
            extra.forEach { (k, v) -> if (v.isNotBlank()) c.setRequestProperty(k, v) }
            val code = c.responseCode
            val body = runCatching {
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }.getOrElse { "" }
            HttpResult(code, body)
        } finally {
            c.disconnect()
        }
    }

    // ---------- 余额 ----------

    private fun fetchBalance(p: Provider): Result {
        val url = p.resolvedUrl()
        if (url.isBlank()) return fail(p, "未填写接口地址")
        val r = httpGet(url, p)
        if (r.code !in 200..299) return fail(p, "HTTP ${r.code}" + errorDetail(r.body))
        val json = runCatching { JSONObject(r.body) }.getOrElse {
            return fail(p, "返回不是 JSON" + snippet(r.body))
        }

        // DeepSeek 的 balance_infos 可能同时有多种币种，顺序也不保证；
        // 固定取 [0] 会读到另一个币种的 0 余额。
        if (p.kind == Kind.DEEPSEEK) {
            parseDeepSeek(p, json)?.let { return it }
        }

        val raw = dig(json, p.jsonPath)
            ?: return fail(p, "返回里没有 " + p.jsonPath + snippet(r.body))
        var amount = toDouble(raw)
            ?: return fail(p, "字段 " + p.jsonPath + " 不是数字" + snippet(r.body))

        if (p.subtractPath.isNotBlank()) {
            val sub = dig(json, p.subtractPath)
            if (sub != null) {
                val s = toDouble(sub) ?: return fail(p, "字段 " + p.subtractPath + " 不是数字")
                amount -= s
            }
        }

        val unit = if (p.unit.isBlank()) guessUnit(json) else p.unit
        return Result(
            p.id, true, amount, unit, "", System.currentTimeMillis(),
            amounts = listOf(Money(amount, unit))
        )
    }

    private data class DeepSeekEntry(val currency: String, val total: Double, val parts: Double)

    /**
     * DeepSeek /user/balance 的 balance_infos 是数组，可能包含 CNY 和 USD。
     * 优先选用户配置的币种，否则选余额最大的正数条目；同时把各币种都放进
     * amounts，界面上可以并列显示。
     */
    private fun parseDeepSeek(p: Provider, json: JSONObject): Result? {
        val arr = json.optJSONArray("balance_infos") ?: return null
        if (arr.length() == 0) return fail(p, "balance_infos 为空")
        val entries = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            DeepSeekEntry(
                currency = o.optString("currency"),
                total = jsonNumber(o, "total_balance"),
                parts = jsonNumber(o, "granted_balance") + jsonNumber(o, "topped_up_balance")
            )
        }
        if (entries.isEmpty()) return fail(p, "balance_infos 没有可用条目")

        // 同币种合并；total 为 0 时用赠金 + 充值余额兜底。
        val byUnit = LinkedHashMap<String, Double>()
        entries.forEach { e ->
            val value = if (e.total == 0.0 && e.parts > 0) e.parts else e.total
            byUnit[e.currency] = (byUnit[e.currency] ?: 0.0) + value
        }
        val want = p.unit.trim()
        val amounts = byUnit.map { Money(it.value, it.key) }
            .sortedBy {
                when {
                    it.unit.equals(want, true) -> 0
                    it.unit.equals("CNY", true) -> 1
                    else -> 2
                }
            }
        val chosen = amounts.firstOrNull { it.unit.equals(want, true) }
            ?: amounts.firstOrNull { it.value > 0 }
            ?: amounts.first()

        val notes = ArrayList<String>()
        if (!json.optBoolean("is_available", true)) notes.add("账户不可用")
        return Result(
            p.id, true, chosen.value, chosen.unit.ifBlank { p.unit }, "",
            System.currentTimeMillis(),
            amounts = amounts,
            note = notes.joinToString(" · ")
        )
    }

    private fun jsonNumber(o: JSONObject, key: String): Double =
        o.optString(key).trim().toDoubleOrNull() ?: o.optDouble(key, 0.0)

    // ---------- 订阅额度 ----------

    private fun fetchQuota(p: Provider): Result {
        var url = p.resolvedUrl()
        if (p.kind == Kind.CLAUDE_QUOTA && p.orgId.isBlank()) {
            val org = discoverClaudeOrg(p)
                ?: return fail(p, "拿不到 Claude 组织 ID，请在高级选项里填 orgId")
            url = p.resolvedUrl(org)
        }
        if (url.isBlank()) return fail(p, "未填写接口地址")

        val extra = if (p.kind == Kind.CODEX_QUOTA) {
            mapOf("ChatGPT-Account-Id" to p.accountId)
        } else emptyMap()

        val r = httpGet(url, p, extra)
        if (r.code == 401) return fail(p, "凭据失效（401），重新取一次 token")
        if (r.code == 403) return fail(p, "无权限（403）")
        if (r.code == 429) return fail(p, "被限流（429），稍后再试")
        if (r.code !in 200..299) return fail(p, "HTTP ${r.code}" + errorDetail(r.body))

        if (p.kind == Kind.OPENCODE_GO) return parseOpenCodeHtml(p, r.body)

        val json = runCatching { JSONObject(r.body) }.getOrElse {
            return fail(p, "返回不是 JSON" + snippet(r.body))
        }
        return when (p.kind) {
            Kind.CODEX_QUOTA -> parseCodex(p, json)
            Kind.CLAUDE_QUOTA -> parseClaude(p, json)
            else -> parseCustomQuota(p, json)
        }
    }

    /** Claude 网页版：用 sessionKey 列一次组织，拿 uuid。 */
    private fun discoverClaudeOrg(p: Provider): String? {
        val r = runCatching { httpGet("https://claude.ai/api/organizations", p) }.getOrNull() ?: return null
        if (r.code !in 200..299) return null
        runCatching { JSONArray(r.body) }.getOrNull()?.let { arr ->
            if (arr.length() > 0) {
                val o = arr.optJSONObject(0) ?: return null
                return o.optString("uuid").takeIf { it.isNotBlank() }
                    ?: o.optString("id").takeIf { it.isNotBlank() }
            }
        }
        runCatching { JSONObject(r.body) }.getOrNull()?.let { obj ->
            val arr = obj.optJSONArray("organizations") ?: return null
            if (arr.length() > 0) {
                val o = arr.optJSONObject(0) ?: return null
                return o.optString("uuid").takeIf { it.isNotBlank() }
                    ?: o.optString("id").takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    private fun parseCodex(p: Provider, json: JSONObject): Result {
        val rl = json.optJSONObject("rate_limit")
        val windows = ArrayList<QuotaWindow>()

        fun add(w: JSONObject?) {
            if (w == null) return
            val pct = w.optDouble("used_percent", Double.NaN)
            if (pct.isNaN()) return
            val secs = w.optLong("limit_window_seconds", 0L)
            val label = when (secs) {
                18000L -> "5h"
                86400L -> "1d"
                604800L -> "7d"
                2592000L -> "1M"
                else -> if (secs > 0) "${secs / 3600}h" else "额度"
            }
            val reset = w.optLong("reset_at", 0L).takeIf { it > 0 }?.times(1000)
            windows.add(QuotaWindow(label, pct.coerceIn(0.0, 100.0), reset, "", secs))
        }

        add(rl?.optJSONObject("primary_window"))
        add(rl?.optJSONObject("secondary_window"))
        add(rl?.optJSONObject("tertiary_window"))
        add(rl?.optJSONObject("monthly_window"))
        windows.sortBy { if (it.windowSeconds == 0L) Long.MAX_VALUE else it.windowSeconds }

        if (windows.isEmpty()) return fail(p, "返回里没有额度窗口")
        val notes = ArrayList<String>()
        json.optString("plan_type").takeIf { it.isNotBlank() }?.let { notes.add(it) }
        if (rl != null && !rl.optBoolean("allowed", true)) notes.add("已限流")
        return Result(
            p.id, true, null, "", "", System.currentTimeMillis(),
            windows, notes.joinToString(" · ")
        )
    }

    private fun parseClaude(p: Provider, json: JSONObject): Result {
        val windows = ArrayList<QuotaWindow>()
        val known = mapOf(
            "five_hour" to ("5h" to 18000L),
            "seven_day" to ("7d" to 604800L),
            "seven_day_sonnet" to ("7d·Sonnet" to 604800L),
            "seven_day_opus" to ("7d·Opus" to 604800L),
            "seven_day_oauth_apps" to ("7d·OAuth" to 604800L),
            "monthly" to ("1M" to 2592000L),
            "thirty_day" to ("1M" to 2592000L)
        )
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val o = json.optJSONObject(key) ?: continue
            val pct = o.optDouble("utilization", Double.NaN)
            if (pct.isNaN()) continue
            val meta = known[key] ?: (key to 0L)
            windows.add(
                QuotaWindow(
                    meta.first,
                    pct.coerceIn(0.0, 100.0),
                    parseReset(o.opt("resets_at")),
                    "",
                    meta.second
                )
            )
        }
        if (windows.isEmpty()) return fail(p, "返回里没有额度窗口")
        return Result(p.id, true, null, "", "", System.currentTimeMillis(), windows, "")
    }

    /**
     * OpenCode Go 没有公开额度 API，网页 dashboard 把数据写成：
     * rollingUsage:$R[3]={usagePercent:12,resetInSec:8000,...}
     * 这里按同样的结构抓 HTML（与社区 CLI 的做法一致）。
     */
    private fun parseOpenCodeHtml(p: Provider, html: String): Result {
        val entryRe = Regex(
            """(rollingUsage|weeklyUsage|monthlyUsage)\s*:\s*\${'$'}R\[\d+]\s*=\s*"""
        )
        val pctRe = Regex("""usagePercent\s*:\s*(-?[0-9.]+)""")
        val resetRe = Regex("""resetInSec\s*:\s*(-?[0-9.]+)""")
        val labels = mapOf(
            "rollingUsage" to ("5h" to 18000L),
            "weeklyUsage" to ("7d" to 604800L),
            "monthlyUsage" to ("1M" to 2592000L)
        )
        val windows = ArrayList<QuotaWindow>()
        entryRe.findAll(html).forEach { m ->
            val key = m.groupValues[1]
            val start = m.range.last + 1
            val end = html.indexOf('}', start).takeIf { it > start } ?: return@forEach
            val body = html.substring(start, end)
            val pct = pctRe.find(body)?.groupValues?.get(1)?.toDoubleOrNull() ?: return@forEach
            val resetSec = resetRe.find(body)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            val meta = labels[key] ?: (key to 0L)
            windows.add(
                QuotaWindow(
                    meta.first,
                    pct.coerceIn(0.0, 100.0),
                    if (resetSec > 0) System.currentTimeMillis() + (resetSec * 1000).toLong() else null,
                    "",
                    meta.second
                )
            )
        }
        if (windows.isEmpty()) {
            return fail(p, "网页里没找到额度数据（结构可能变了）")
        }
        windows.sortBy { it.windowSeconds }
        return Result(p.id, true, null, "", "", System.currentTimeMillis(), windows, "网页")
    }

    private fun parseCustomQuota(p: Provider, json: JSONObject): Result {
        val windows = ArrayList<QuotaWindow>()
        p.windowsSpec.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val parts = line.split('|')
            if (parts.size < 2) return@forEach
            val label = parts[0].trim().ifBlank { "额度" }
            val pctPath = parts[1].trim()
            if (pctPath.isEmpty()) return@forEach
            val pct = toDouble(dig(json, pctPath)) ?: return@forEach
            val resetPath = parts.getOrNull(2)?.trim().orEmpty()
            val reset = if (resetPath.isEmpty()) null else parseReset(dig(json, resetPath))
            windows.add(QuotaWindow(label, pct.coerceIn(0.0, 100.0), reset, "", 0))
        }

        // 兼容旧版单窗口配置
        if (windows.isEmpty() && p.jsonPath.isNotBlank()) {
            val pct = toDouble(dig(json, p.jsonPath))
                ?: return fail(p, "字段 " + p.jsonPath + " 不是数字")
            windows.add(QuotaWindow("额度", pct.coerceIn(0.0, 100.0), null, "", 0))
        }

        if (windows.isEmpty()) return fail(p, "没找到额度字段，检查窗口配置")
        return Result(p.id, true, null, "", "", System.currentTimeMillis(), windows, "")
    }

    // ---------- 工具 ----------

    private fun fail(p: Provider, msg: String) =
        Result(p.id, false, null, p.unit, msg.ifBlank { "查询失败" }, System.currentTimeMillis())

    private fun errorDetail(body: String): String {
        val msg = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message")
        }.getOrNull()
        return if (msg.isNullOrBlank()) snippet(body) else " · " + msg.take(60)
    }

    private fun snippet(body: String): String {
        val s = body.trim().replace(Regex("\\s+"), " ")
        return if (s.isEmpty()) "" else " · " + s.take(60)
    }

    private fun toDouble(v: Any?): Double? = when (v) {
        is Number -> v.toDouble()
        is String -> v.trim().trimEnd('%').toDoubleOrNull()
        else -> null
    }

    private fun dig(root: Any?, path: String): Any? {
        if (path.isBlank()) return null
        var cur: Any? = root
        for (seg in path.split('.')) {
            if (seg.isEmpty()) continue
            cur = when (cur) {
                is JSONObject -> if (cur.has(seg)) cur.get(seg) else return null
                is JSONArray -> {
                    val i = seg.toIntOrNull() ?: return null
                    if (i in 0 until cur.length()) cur.get(i) else return null
                }
                else -> return null
            }
        }
        return cur
    }

    private fun parseReset(v: Any?): Long? = when (v) {
        is Number -> {
            val n = v.toLong()
            if (n > 10_000_000_000L) n else n * 1000
        }
        is String -> {
            val s = v.trim()
            runCatching { Instant.parse(s).toEpochMilli() }.getOrNull()
                ?: runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()
                ?: s.toLongOrNull()?.let { if (it > 10_000_000_000L) it else it * 1000 }
        }
        else -> null
    }

    private fun guessUnit(json: JSONObject): String {
        val direct = json.optString("unit").ifBlank { json.optString("currency") }
        if (direct.isNotBlank()) return direct
        val data = json.optJSONObject("data")
        val nested = data?.optString("unit").orEmpty().ifBlank { data?.optString("currency").orEmpty() }
        return nested.ifBlank { "CNY" }
    }

    // ---------- 汇总 ----------

    /**
     * 余额告警级别：2 = 极低（< 预警值），1 = 低（< 3×预警值），0 = 充足。
     * 余额为 0 或负数按极低处理；未设预警线时只有 0 会变红。
     */
    fun warnLevel(p: Provider, r: Result?): Int {
        if (r == null || !r.ok || p.isQuota()) return 0
        val amount = r.amount ?: return 0
        if (amount <= 0.0) return 2
        if (p.warnBelow <= 0.0) return 0
        return when {
            amount < p.warnBelow -> 2
            amount < p.warnBelow * 3 -> 1
            else -> 0
        }
    }

    /** 单个渠道返回里的最高占用。 */
    fun peakOf(r: Result?): Double? {
        if (r == null || !r.ok) return null
        return r.windows.maxOfOrNull { it.usedPercent }
    }

    fun statusOf(providers: List<Provider>): Pair<Int, Int> {
        var ok = 0
        var bad = 0
        providers.filter { it.enabled }.forEach { p ->
            val r = results[p.id] ?: return@forEach
            if (r.ok) ok++ else bad++
        }
        return ok to bad
    }
}
