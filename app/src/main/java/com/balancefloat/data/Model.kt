package com.balancefloat.data

import java.util.UUID

enum class Mode { BALANCE, QUOTA }

/**
 * 渠道类型。BALANCE 查账户余额，QUOTA 查订阅额度（百分比窗口）。
 * OpenCode Go 官方没有额度接口，保留类型仅作说明，不可保存。
 */
enum class Kind(
    val id: String,
    val label: String,
    val hint: String,
    val mode: Mode,
    val supported: Boolean = true
) {
    DEEPSEEK("deepseek", "DeepSeek", "官方 /user/balance", Mode.BALANCE),
    SILICONFLOW("siliconflow", "硅基流动", "国内站 /v1/user/info", Mode.BALANCE),
    SILICONFLOW_US("siliconflow_us", "硅基流动 · 国际", "国际站 /v1/user/info", Mode.BALANCE),
    BIGMODEL("bigmodel", "智谱 BigModel", "/api/paas/v4/user/info", Mode.BALANCE),
    OPENROUTER("openrouter", "OpenRouter", "/api/v1/credits", Mode.BALANCE),
    GATEWAY("gateway", "本机 ai-gateway", "本机聚合余额 /v1/balance", Mode.BALANCE),
    CUSTOM("custom", "自定义中转站", "任意 URL + JSON 路径", Mode.BALANCE),

    CODEX_QUOTA("codex_quota", "Codex / ChatGPT 订阅", "5h + 周额度（OAuth token）", Mode.QUOTA),
    CLAUDE_QUOTA("claude_quota", "Claude 订阅", "网页 sessionKey", Mode.QUOTA),
    CUSTOM_QUOTA("custom_quota", "自定义额度", "任意 URL + JSON 路径", Mode.QUOTA),
    OPENCODE_GO("opencode_go", "OpenCode Go", "网页 workspace + auth cookie", Mode.QUOTA),
    GEMINI_QUOTA("gemini_quota", "Gemini 订阅", "Google 账号登录 / Code Assist 配额", Mode.QUOTA);

    companion object {
        fun of(id: String?): Kind = values().firstOrNull { it.id == id } ?: CUSTOM
    }
}

/** 一种币种的金额；DeepSeek 可能同时返回 CNY 和 USD。 */
data class Money(val value: Double, val unit: String)

/** 一个额度窗口，例如 5 小时 / 周。usedPercent 为「已用百分比」。 */
data class QuotaWindow(
    val label: String,
    val usedPercent: Double,
    val resetAt: Long? = null,
    val detail: String = "",
    val windowSeconds: Long = 0
)

data class Provider(
    val id: String = UUID.randomUUID().toString(),
    val kind: Kind = Kind.CUSTOM,
    val name: String = "",
    val apiKey: String = "",
    /** Codex OAuth 的 refresh_token（登录后自动填，1 小时换一次 access_token） */
    val refreshToken: String = "",
    /** access_token 过期时间（epoch 毫秒）；0 = 未知 */
    val tokenExpiresAt: Long = 0L,
    /** 完整 URL，或相对路径（用 baseUrl 拼接）；可用 {org} 占位 */
    val endpoint: String = "",
    val baseUrl: String = "",
    /** 余额 JSON 点路径 / 自定义额度的已用百分比路径 */
    val jsonPath: String = "data.balance",
    /** 可选：从余额里再减去的 JSON 点路径（OpenRouter 的 total_usage） */
    val subtractPath: String = "",
    /** CNY / USD / ""（跟随接口返回的 unit|currency 字段） */
    val unit: String = "CNY",
    /** bearer / x-api-key / cookie / none */
    val auth: String = "bearer",
    val enabled: Boolean = true,
    /** 余额低于该值（按渠道自己的币种）时指示灯变黄；0 = 关闭预警 */
    val warnBelow: Double = 0.0,
    // ---- 额度渠道专用 ----
    val accountId: String = "",
    val orgId: String = "",
    /** OpenCode Go 的 workspace id（wrk_…） */
    val workspaceId: String = "",
    /** Cookie 认证时的 cookie 名（Claude=sessionKey，OpenCode=auth） */
    val cookieName: String = "",
    /** 自定义额度窗口，每行：标签|百分比路径|重置路径（后两项可留空） */
    val windowsSpec: String = ""
) {
    fun resolvedUrl(org: String = orgId): String {
        var e = endpoint.trim().replace("{org}", org).replace("{ws}", workspaceId)
        if (e.startsWith("http://", true) || e.startsWith("https://", true)) return e
        var b = baseUrl.trim().trimEnd('/').replace("{org}", org).replace("{ws}", workspaceId)
        if (b.isEmpty()) return e
        return if (e.isEmpty()) b else b + "/" + e.trimStart('/')
    }

    fun isQuota(): Boolean = kind.mode == Mode.QUOTA

    fun isCleartextRemote(): Boolean {
        val u = resolvedUrl()
        if (!u.startsWith("http://", true)) return false
        val host = u.removePrefix("http://").substringBefore('/').substringBefore(':')
        return host != "127.0.0.1" && host != "localhost" && host != "10.0.2.2"
    }

    companion object {
        fun preset(kind: Kind): Provider {
            return when (kind) {
                Kind.DEEPSEEK -> Provider(
                    kind = kind, name = "DeepSeek",
                    endpoint = "https://api.deepseek.com/user/balance",
                    jsonPath = "balance_infos.0.total_balance",
                    unit = "CNY", auth = "bearer"
                )
                Kind.SILICONFLOW -> Provider(
                    kind = kind, name = "硅基流动",
                    endpoint = "https://api.siliconflow.cn/v1/user/info",
                    jsonPath = "data.balance",
                    unit = "CNY", auth = "bearer"
                )
                Kind.SILICONFLOW_US -> Provider(
                    kind = kind, name = "硅基流动 · 国际",
                    endpoint = "https://api.siliconflow.com/v1/user/info",
                    jsonPath = "data.balance",
                    unit = "USD", auth = "bearer"
                )
                Kind.BIGMODEL -> Provider(
                    kind = kind, name = "智谱",
                    endpoint = "https://open.bigmodel.cn/api/paas/v4/user/info",
                    jsonPath = "data.balance",
                    unit = "CNY", auth = "bearer"
                )
                Kind.OPENROUTER -> Provider(
                    kind = kind, name = "OpenRouter",
                    endpoint = "https://openrouter.ai/api/v1/credits",
                    jsonPath = "data.total_credits",
                    subtractPath = "data.total_usage",
                    unit = "USD", auth = "bearer"
                )
                Kind.GATEWAY -> Provider(
                    kind = kind, name = "ai-gateway",
                    endpoint = "http://127.0.0.1:16384/v1/balance",
                    jsonPath = "total_remain",
                    unit = "", auth = "bearer"
                )
                Kind.CODEX_QUOTA -> Provider(
                    kind = kind, name = "Codex",
                    endpoint = "https://chatgpt.com/backend-api/wham/usage",
                    jsonPath = "", unit = "", auth = "bearer"
                )
                Kind.CLAUDE_QUOTA -> Provider(
                    kind = kind, name = "Claude",
                    endpoint = "https://claude.ai/api/organizations/{org}/usage",
                    jsonPath = "", unit = "", auth = "cookie", cookieName = "sessionKey"
                )
                Kind.OPENCODE_GO -> Provider(
                    kind = kind, name = "OpenCode Go",
                    endpoint = "https://opencode.ai/workspace/{ws}/go",
                    jsonPath = "", unit = "", auth = "cookie", cookieName = "auth"
                )
                Kind.GEMINI_QUOTA -> Provider(
                    kind = kind, name = "Gemini",
                    endpoint = "https://cloudcode-pa.googleapis.com/v1internal:retrieveUserQuota",
                    jsonPath = "", unit = "", auth = "bearer"
                )
                Kind.CUSTOM_QUOTA -> Provider(
                    kind = kind, name = "额度",
                    endpoint = "", jsonPath = "", unit = "", auth = "bearer",
                    windowsSpec = "5h|data.used_5h|data.reset_5h\n" +
                        "7d|data.used_7d|data.reset_7d\n" +
                        "1M|data.used_1m|data.reset_1m"
                )
                Kind.CUSTOM -> Provider(
                    kind = Kind.CUSTOM, name = "中转站",
                    endpoint = "", jsonPath = "data.balance",
                    unit = "CNY", auth = "bearer"
                )

            }
        }
    }
}
