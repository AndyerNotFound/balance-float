package com.balancefloat.data

import android.util.Base64
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Codex / ChatGPT 订阅的 OAuth 登录。
 *
 * 授权页在系统浏览器打开，登录完成后浏览器被重定向到 http://localhost:1455/auth/callback，
 * App 在本机监听该端口接住 code，再换 access_token / refresh_token。
 * client_id 与 redirect_uri 与 Codex CLI 一致，这样能复用同一套 OAuth 客户端。
 *
 * 阻塞式：调用方必须放在后台线程。
 */
object CodexOAuth {

    const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    private const val AUTH_URL = "https://auth.openai.com/oauth/authorize"
    private const val TOKEN_URL = "https://auth.openai.com/oauth/token"
    private const val REDIRECT_URI = "http://localhost:1455/auth/callback"
    private const val SCOPE = "openid profile email offline_access"
    private const val PORT = 1455

    data class Tokens(
        val access: String,
        val refresh: String,
        val expiresAt: Long,
        /** 空表示这次响应里没带 id_token，调用方保留旧值 */
        val accountId: String,
        val plan: String
    )

    /** 起监听 → 打开浏览器 → 等回调。失败抛异常，由调用方捕获。 */
    fun login(timeoutSec: Int = 300, onUrl: (String) -> Unit): Result<Tokens> {
        val verifier = randomB64(48)
        val challenge = b64(sha256(verifier.toByteArray(Charsets.US_ASCII)))
        val state = randomB64(16)
        val url = buildString {
            append(AUTH_URL)
            append("?response_type=code")
            append("&client_id=").append(enc(CLIENT_ID))
            append("&redirect_uri=").append(enc(REDIRECT_URI))
            append("&scope=").append(enc(SCOPE))
            append("&code_challenge=").append(enc(challenge))
            append("&code_challenge_method=S256")
            append("&state=").append(enc(state))
            append("&id_token_add_organizations=true")
            append("&codex_cli_simplified_flow=true")
            append("&originator=codex_cli_rs")
        }

        val server = runCatching { openServer() }.getOrElse {
            return Result.failure(IllegalStateException("本机 $PORT 端口被占用，登录回调收不到"))
        }
        return try {
            onUrl(url)
            val code = waitForCode(server, state, timeoutSec)
                ?: return Result.failure(IllegalStateException("等待登录超时（${timeoutSec}s）"))
            val t = postToken(
                JSONObject().apply {
                    put("client_id", CLIENT_ID)
                    put("grant_type", "authorization_code")
                    put("code", code)
                    put("redirect_uri", REDIRECT_URI)
                    put("code_verifier", verifier)
                },
                null
            )
            if (t == null) Result.failure(IllegalStateException("换取 token 失败（可能 code 已用过）"))
            else Result.success(t)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            runCatching { server.close() }
        }
    }

    /** 用 refresh_token 换新的 access_token。阻塞式。 */
    fun refresh(refreshToken: String): Tokens? {
        if (refreshToken.isBlank()) return null
        return runCatching {
            postToken(
                JSONObject().apply {
                    put("client_id", CLIENT_ID)
                    put("grant_type", "refresh_token")
                    put("refresh_token", refreshToken)
                    put("scope", "openid profile email")
                },
                refreshToken
            )
        }.getOrNull()
    }

    // ---------- 内部 ----------

    private fun postToken(body: JSONObject, fallbackRefresh: String?): Tokens? {
        val c = URL(TOKEN_URL).openConnection() as HttpURLConnection
        return try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", "BalanceFloat/0.7 (Android)")
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val ok = c.responseCode in 200..299
            val text = runCatching {
                val s = if (ok) c.inputStream else c.errorStream
                s?.bufferedReader()?.use { r -> r.readText() }.orEmpty()
            }.getOrDefault("")
            if (!ok) return null
            val j = runCatching { JSONObject(text) }.getOrNull() ?: return null
            val access = j.optString("access_token")
            if (access.isBlank()) return null

            val claim = authClaim(j.optString("id_token"))
            Tokens(
                access = access,
                refresh = j.optString("refresh_token").ifBlank { fallbackRefresh ?: "" },
                expiresAt = System.currentTimeMillis() + j.optLong("expires_in", 3600L) * 1000L,
                accountId = claim?.optString("chatgpt_account_id").orEmpty(),
                plan = claim?.optString("chatgpt_plan_type").orEmpty()
            )
        } finally {
            c.disconnect()
        }
    }

    /** id_token 里 `https://api.openai.com/auth` 这一段。 */
    private fun authClaim(idToken: String): JSONObject? {
        val parts = idToken.split('.')
        if (parts.size < 2) return null
        val raw = runCatching {
            Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP)
        }.getOrNull() ?: return null
        val j = runCatching { JSONObject(String(raw, Charsets.UTF_8)) }.getOrNull() ?: return null
        return j.optJSONObject("https://api.openai.com/auth") ?: j
    }

    private fun openServer(): ServerSocket {
        // 先试 IPv6 双栈（浏览器把 localhost 解析成 ::1 时也能连上），失败退回 IPv4
        for (host in listOf("::", "0.0.0.0")) {
            runCatching {
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(InetSocketAddress(InetAddress.getByName(host), PORT))
                s.soTimeout = 1000
                return s
            }
        }
        throw IllegalStateException("端口 $PORT 无法监听")
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
            val k = kv.substring(0, i)
            val v = runCatching { java.net.URLDecoder.decode(kv.substring(i + 1), "UTF-8") }.getOrDefault("")
            out[k] = v
        }
        return out
    }

    private fun randomB64(n: Int): String {
        val b = ByteArray(n)
        SecureRandom().nextBytes(b)
        return b64(b)
    }

    private fun b64(b: ByteArray): String =
        Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun sha256(b: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(b)

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
