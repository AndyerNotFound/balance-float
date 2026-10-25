package com.balancefloat.data

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom

/**
 * Gemini 订阅（Google Code Assist）的登录与额度查询。
 *
 * 参数取自官方开源实现 google-gemini/gemini-cli：
 *  - client_id / client_secret 是 "已安装应用" 的公开值（Google 官方文档说明这类
 *    secret 不视为机密，就嵌在源码里）；
 *  - redirect 必须用 loopback 字面量 127.0.0.1，端口可随机构造；
 *  - 额度走 cloudcode-pa 的两个内部端点，请求必须带 Client-Metadata 头。
 *
 * 阻塞式：调用方负责放到后台线程。
 */
object GeminiOAuth {

    private const val CLIENT_ID =
        "681255809395-oo8ft2oprdrnp9e3aqf6av3hmdib135j.apps.googleusercontent.com"
    private const val CLIENT_SECRET = "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"

    private const val AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    private const val LOAD_ASSIST = "https://cloudcode-pa.googleapis.com/v1internal:loadCodeAssist"
    private const val USER_QUOTA = "https://cloudcode-pa.googleapis.com/v1internal:retrieveUserQuota"

    private const val SCOPE = "https://www.googleapis.com/auth/cloud-platform " +
        "https://www.googleapis.com/auth/userinfo.email " +
        "https://www.googleapis.com/auth/userinfo.profile"

    private const val CLIENT_METADATA =
        "ideType=IDE_UNSPECIFIED,platform=PLATFORM_UNSPECIFIED,pluginType=GEMINI"

    data class Tokens(val access: String, val refresh: String, val expiresAt: Long)

    data class Quota(val project: String, val tier: String, val windows: List<QuotaWindow>)

    /** 起随机端口监听 → 打开浏览器 → 收回调换 token。 */
    fun login(timeoutSec: Int = 300, onUrl: (String) -> Unit): Result<Tokens> {
        val state = hex(16)
        val server = runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
                soTimeout = 1000
            }
        }.getOrElse {
            return Result.failure(IllegalStateException("本机端口监听失败"))
        }
        val port = server.localPort
        val redirect = "http://127.0.0.1:$port/oauth2callback"

        val url = buildString {
            append(AUTH_URL)
            append("?client_id=").append(enc(CLIENT_ID))
            append("&redirect_uri=").append(enc(redirect))
            append("&response_type=code")
            append("&scope=").append(enc(SCOPE))
            append("&access_type=offline")
            append("&prompt=consent")
            append("&state=").append(state)
        }

        return try {
            onUrl(url)
            val code = waitForCode(server, state, timeoutSec)
                ?: return Result.failure(IllegalStateException("等待登录超时（${timeoutSec}s）"))
            val t = exchange(code, redirect)
            if (t == null) Result.failure(IllegalStateException("换取 token 失败"))
            else Result.success(t)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            runCatching { server.close() }
        }
    }

    /** 用 refresh_token 换 access_token。 */
    fun refresh(refreshToken: String): Tokens? {
        if (refreshToken.isBlank()) return null
        val body = form(
            "client_id" to CLIENT_ID,
            "client_secret" to CLIENT_SECRET,
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken
        )
        val j = post(TOKEN_URL, body, formUrlEncoded = true) ?: return null
        if (j.has("error")) return null
        val access = j.optString("access_token")
        if (access.isBlank()) return null
        return Tokens(
            access,
            j.optString("refresh_token").ifBlank { refreshToken },
            System.currentTimeMillis() + j.optLong("expires_in", 3600L) * 1000L
        )
    }

    /** 查项目 ID + 各模型额度。失败时 Result 里带可读原因。 */
    fun fetchQuota(accessToken: String, knownProject: String = ""): Result<Quota> {
        return runCatching {
            var project = knownProject
            var tier = ""

            val assist = postJson(
                LOAD_ASSIST,
                JSONObject().put(
                    "metadata",
                    JSONObject()
                        .put("ideType", "IDE_UNSPECIFIED")
                        .put("platform", "PLATFORM_UNSPECIFIED")
                        .put("pluginType", "GEMINI")
                ),
                accessToken
            ) ?: throw IllegalStateException("loadCodeAssist 失败")

            assist.optJSONObject("error")?.let {
                val code = it.optInt("code")
                val msg = it.optString("message")
                throw IllegalStateException(
                    if (code == 403) "该 Google 账号没有 Code Assist 权限（403）"
                    else "loadCodeAssist $code: ${msg.take(60)}"
                )
            }

            val cp = assist.opt("cloudaicompanionProject")
            val pid = when (cp) {
                is String -> cp
                is JSONObject -> cp.optString("id")
                else -> ""
            }
            if (pid.isNotBlank()) project = pid
            tier = assist.optJSONObject("currentTier")?.optString("name").orEmpty()

            val q = postJson(
                USER_QUOTA,
                JSONObject().put("project", project),
                accessToken
            ) ?: throw IllegalStateException("retrieveUserQuota 失败")

            q.optJSONObject("error")?.let {
                throw IllegalStateException(
                    "retrieveUserQuota ${it.optInt("code")}: ${it.optString("message").take(60)}"
                )
            }

            val buckets = q.optJSONArray("buckets")
                ?: throw IllegalStateException("返回里没有 buckets")

            val list = ArrayList<QuotaWindow>()
            for (i in 0 until buckets.length()) {
                val b = buckets.optJSONObject(i) ?: continue
                val remain = b.optDouble("remainingFraction", Double.NaN)
                if (remain.isNaN()) continue
                val used = ((1.0 - remain.coerceIn(0.0, 1.0)) * 100.0)
                val model = b.optString("modelId")
                val reset = b.optString("resetTime")
                    .takeIf { it.isNotBlank() }
                    ?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
                list.add(
                    QuotaWindow(
                        label = shortModel(model),
                        usedPercent = used.coerceIn(0.0, 100.0),
                        resetAt = reset,
                        detail = model,
                        windowSeconds = 0
                    )
                )
            }
            if (list.isEmpty()) throw IllegalStateException("额度列表为空")
            list.sortByDescending { it.usedPercent }
            Quota(project, tier, list.take(4))
        }
    }

    // ---------- 内部 ----------

    private fun exchange(code: String, redirect: String): Tokens? {
        val body = form(
            "client_id" to CLIENT_ID,
            "client_secret" to CLIENT_SECRET,
            "code" to code,
            "grant_type" to "authorization_code",
            "redirect_uri" to redirect
        )
        val j = post(TOKEN_URL, body, formUrlEncoded = true) ?: return null
        if (j.has("error")) return null
        val access = j.optString("access_token")
        if (access.isBlank()) return null
        return Tokens(
            access,
            j.optString("refresh_token"),
            System.currentTimeMillis() + j.optLong("expires_in", 3600L) * 1000L
        )
    }

    private fun postJson(url: String, body: JSONObject, token: String): JSONObject? =
        post(
            url,
            body.toString(),
            formUrlEncoded = false,
            headers = mapOf(
                "Authorization" to "Bearer $token",
                "Client-Metadata" to CLIENT_METADATA,
                "User-Agent" to "GeminiCLI/0.35.3/gemini-3.1-pro-preview (linux; arm64; terminal)"
            )
        )

    private fun post(
        url: String,
        body: String,
        formUrlEncoded: Boolean,
        headers: Map<String, String> = emptyMap()
    ): JSONObject? {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            c.setRequestProperty(
                "Content-Type",
                if (formUrlEncoded) "application/x-www-form-urlencoded" else "application/json"
            )
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", "BalanceFloat/0.8 (Android)")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val text = runCatching {
                val s = if (c.responseCode in 200..299) c.inputStream else c.errorStream
                s?.bufferedReader()?.use { r -> r.readText() }.orEmpty()
            }.getOrDefault("")
            runCatching { JSONObject(text) }.getOrNull()
        } finally {
            c.disconnect()
        }
    }

    private fun waitForCode(server: ServerSocket, state: String, timeoutSec: Int): String? {
        val deadline = System.currentTimeMillis() + timeoutSec * 1000L
        while (System.currentTimeMillis() < deadline) {
            val sock = try {
                server.accept()
            } catch (e: java.net.SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                return null
            }
            runCatching {
                sock.use { s ->
                    val line = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                        .readLine().orEmpty()
                    val target = line.split(' ').getOrNull(1).orEmpty()
                    val q = parseQuery(target.substringAfter('?', ""))
                    val code = q["code"]
                    val good = !code.isNullOrBlank() && q["state"] == state
                    val html = if (good) {
                        "<!doctype html><meta name=viewport content='width=device-width'>" +
                            "<body style='font-family:system-ui;padding:48px 24px;text-align:center'>" +
                            "<h2>登录完成</h2><p>回到 Balance Float 即可</p></body>"
                    } else {
                        "<!doctype html><meta name=viewport content='width=device-width'>" +
                            "<body style='font-family:system-ui;padding:48px 24px;text-align:center'>" +
                            "<h2>回调无效</h2><p>回到 App 重试</p></body>"
                    }
                    val bytes = html.toByteArray(Charsets.UTF_8)
                    val head = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    s.getOutputStream().apply {
                        write(head.toByteArray(Charsets.US_ASCII))
                        write(bytes)
                        flush()
                    }
                    if (good) return code
                }
            }
        }
        return null
    }

    private fun parseQuery(q: String): Map<String, String> {
        if (q.isEmpty()) return emptyMap()
        val out = HashMap<String, String>()
        q.split('&').forEach { kv ->
            val i = kv.indexOf('=')
            if (i <= 0) return@forEach
            out[kv.substring(0, i)] = runCatching {
                java.net.URLDecoder.decode(kv.substring(i + 1), "UTF-8")
            }.getOrDefault("")
        }
        return out
    }

    private fun shortModel(id: String): String {
        if (id.isBlank()) return "Gemini"
        return id.removePrefix("gemini-")
            .removeSuffix("-preview")
            .removeSuffix("-latest")
            .ifBlank { id }
    }

    private fun form(vararg kv: Pair<String, String>): String =
        kv.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun hex(n: Int): String {
        val b = ByteArray(n)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }
}
