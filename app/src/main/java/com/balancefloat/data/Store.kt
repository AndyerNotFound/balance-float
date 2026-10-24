package com.balancefloat.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 渠道列表与偏好设置（SharedPreferences，Key 加密后存储）。 */
class Store(context: Context) {

    private val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- 渠道 ----------

    fun providers(): List<Provider> {
        val raw = sp.getString(KEY_PROVIDERS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { fromJson(arr.getJSONObject(i)) }.getOrNull()
            }
        }.getOrElse { emptyList() }
    }

    fun saveProviders(list: List<Provider>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        sp.edit().putString(KEY_PROVIDERS, arr.toString()).apply()
    }

    fun upsert(p: Provider) {
        val list = providers().toMutableList()
        val idx = list.indexOfFirst { it.id == p.id }
        if (idx >= 0) list[idx] = p else list.add(p)
        saveProviders(list)
    }

    fun remove(id: String) {
        saveProviders(providers().filterNot { it.id == id })
    }

    private fun toJson(p: Provider) = JSONObject().apply {
        put("id", p.id)
        put("kind", p.kind.id)
        put("name", p.name)
        put("key", Crypto.encrypt(p.apiKey))
        put("endpoint", p.endpoint)
        put("baseUrl", p.baseUrl)
        put("jsonPath", p.jsonPath)
        put("subtractPath", p.subtractPath)
        put("unit", p.unit)
        put("auth", p.auth)
        put("enabled", p.enabled)
        put("warnBelow", p.warnBelow)
        put("accountId", p.accountId)
        put("orgId", p.orgId)
        put("workspaceId", p.workspaceId)
        put("cookieName", p.cookieName)
        put("windowsSpec", p.windowsSpec)
    }

    private fun fromJson(o: JSONObject) = Provider(
        id = o.optString("id"),
        kind = Kind.of(o.optString("kind")),
        name = o.optString("name"),
        apiKey = Crypto.decrypt(o.optString("key")),
        endpoint = o.optString("endpoint"),
        baseUrl = o.optString("baseUrl"),
        jsonPath = o.optString("jsonPath"),
        subtractPath = o.optString("subtractPath"),
        unit = o.optString("unit"),
        auth = o.optString("auth", "bearer"),
        enabled = o.optBoolean("enabled", true),
        warnBelow = o.optDouble("warnBelow", 0.0),
        accountId = o.optString("accountId"),
        orgId = o.optString("orgId"),
        workspaceId = o.optString("workspaceId"),
        cookieName = o.optString("cookieName"),
        windowsSpec = o.optString("windowsSpec")
    )

    // ---------- 设置 ----------

    var intervalSec: Int
        get() = sp.getInt(KEY_INTERVAL, 300).coerceIn(0, 6 * 3600)
        set(v) = sp.edit().putInt(KEY_INTERVAL, v.coerceIn(0, 6 * 3600)).apply()

    /** 胶囊轮播间隔（秒） */
    var pillIntervalSec: Int
        get() = sp.getInt(KEY_PILL_INTERVAL, 4).coerceIn(1, 30)
        set(v) = sp.edit().putInt(KEY_PILL_INTERVAL, v.coerceIn(1, 30)).apply()

    /** 悬浮窗窗口级背景模糊（Android 12+，实验性：部分 ROM 会糊整屏） */
    var overlayBlur: Boolean
        get() = sp.getBoolean(KEY_OVERLAY_BLUR, false)
        set(v) = sp.edit().putBoolean(KEY_OVERLAY_BLUR, v).apply()

    /** 胶囊整体缩放（0.8–1.5） */
    var pillScale: Float
        get() = sp.getFloat(KEY_PILL_SCALE, 1f).coerceIn(0.8f, 1.5f)
        set(v) = sp.edit().putFloat(KEY_PILL_SCALE, v.coerceIn(0.8f, 1.5f)).apply()

    /** 明细内容缩放（0.8–1.4） */
    var detailScale: Float
        get() = sp.getFloat(KEY_DETAIL_SCALE, 1f).coerceIn(0.8f, 1.4f)
        set(v) = sp.edit().putFloat(KEY_DETAIL_SCALE, v.coerceIn(0.8f, 1.4f)).apply()

    /** 胶囊自动轮播；关掉后单击手动切下一条 */
    var pillAutoScroll: Boolean
        get() = sp.getBoolean(KEY_PILL_AUTO_SCROLL, true)
        set(v) = sp.edit().putBoolean(KEY_PILL_AUTO_SCROLL, v).apply()

    /** 锁定位置：拖不动，防误触 */
    var lockPosition: Boolean
        get() = sp.getBoolean(KEY_LOCK_POSITION, false)
        set(v) = sp.edit().putBoolean(KEY_LOCK_POSITION, v).apply()

    /** 常驻展开：不允许收起成胶囊 */
    var pinExpanded: Boolean
        get() = sp.getBoolean(KEY_PIN_EXPANDED, false)
        set(v) = sp.edit().putBoolean(KEY_PIN_EXPANDED, v).apply()

    /** 自定义背景色（ARGB，0 = 用内置的 bg_pill / bg_detail） */
    var pillBg: Int
        get() = sp.getInt(KEY_PILL_BG, 0)
        set(v) = sp.edit().putInt(KEY_PILL_BG, v).apply()

    var detailBg: Int
        get() = sp.getInt(KEY_DETAIL_BG, 0)
        set(v) = sp.edit().putInt(KEY_DETAIL_BG, v).apply()

    /** 固定尺寸（dp，0 = 自适应） */
    var pillWidthDp: Int
        get() = sp.getInt(KEY_PILL_W, 0).coerceIn(0, 320)
        set(v) = sp.edit().putInt(KEY_PILL_W, v.coerceIn(0, 320)).apply()

    var pillHeightDp: Int
        get() = sp.getInt(KEY_PILL_H, 0).coerceIn(0, 120)
        set(v) = sp.edit().putInt(KEY_PILL_H, v.coerceIn(0, 120)).apply()

    var detailWidthDp: Int
        get() = sp.getInt(KEY_DETAIL_W, 0).coerceIn(0, 380)
        set(v) = sp.edit().putInt(KEY_DETAIL_W, v.coerceIn(0, 380)).apply()

    /** 打开 App 时自动把悬浮窗挂上 */
    var autoShow: Boolean
        get() = sp.getBoolean(KEY_AUTO_SHOW, true)
        set(v) = sp.edit().putBoolean(KEY_AUTO_SHOW, v).apply()

    /** 用户意图：悬浮窗应该是开着的（系统杀掉后下次启动可恢复） */
    var overlayOn: Boolean
        get() = sp.getBoolean(KEY_OVERLAY_ON, false)
        set(v) = sp.edit().putBoolean(KEY_OVERLAY_ON, v).apply()

    val hasOverlayPosition: Boolean
        get() = sp.contains(KEY_X) || sp.contains(KEY_Y)

    var overlayX: Int
        get() = sp.getInt(KEY_X, 24)
        set(v) = sp.edit().putInt(KEY_X, v).apply()

    var overlayY: Int
        get() = sp.getInt(KEY_Y, 240)
        set(v) = sp.edit().putInt(KEY_Y, v).apply()

    var expanded: Boolean
        get() = sp.getBoolean(KEY_EXPANDED, false)
        set(v) = sp.edit().putBoolean(KEY_EXPANDED, v).apply()

    var lastRefreshAt: Long
        get() = sp.getLong(KEY_LAST_REFRESH, 0L)
        set(v) = sp.edit().putLong(KEY_LAST_REFRESH, v).apply()

    private companion object {
        const val PREFS = "balance_float"
        const val KEY_PROVIDERS = "providers"
        const val KEY_INTERVAL = "interval_sec"
        const val KEY_PILL_INTERVAL = "pill_interval_sec"
        const val KEY_OVERLAY_BLUR = "overlay_blur"
        const val KEY_PILL_SCALE = "pill_scale"
        const val KEY_DETAIL_SCALE = "detail_scale"
        const val KEY_PILL_AUTO_SCROLL = "pill_auto_scroll"
        const val KEY_LOCK_POSITION = "lock_position"
        const val KEY_PIN_EXPANDED = "pin_expanded"
        const val KEY_PILL_BG = "pill_bg"
        const val KEY_DETAIL_BG = "detail_bg"
        const val KEY_PILL_W = "pill_w_dp"
        const val KEY_PILL_H = "pill_h_dp"
        const val KEY_DETAIL_W = "detail_w_dp"
        const val KEY_AUTO_SHOW = "auto_show"
        const val KEY_OVERLAY_ON = "overlay_on"
        const val KEY_X = "overlay_x"
        const val KEY_Y = "overlay_y"
        const val KEY_EXPANDED = "overlay_expanded"
        const val KEY_LAST_REFRESH = "last_refresh"
    }
}
