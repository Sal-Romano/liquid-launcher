package dev.sal.glass.ui

import org.json.JSONObject
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * Calls a hook tile's URL and says how it went: null when it worked, otherwise one short line to
 * show on the TV. Blocking; call it off the main thread.
 *
 * The other end answers when it is done, and says whether it worked in a JSON body,
 * `{"ok": false, "error": "..."}`, so a 200 is not enough. The Wii's end has the Wii up within
 * 15 s and the TV switched by 22 s or answers with an error, never switching the TV after giving
 * up; the 30 s read timeout here is only for an end that hangs.
 */
fun callHook(url: String, name: String): String? {
    val c = try {
        URL(url).openConnection(Proxy.NO_PROXY) as HttpURLConnection
    } catch (e: Exception) {
        return "$name has a bad address"
    }
    return try {
        c.connectTimeout = 3_000
        c.readTimeout = 30_000
        val code = c.responseCode
        val body = runCatching {
            (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }
        }.getOrNull().orEmpty()
        val json = runCatching { JSONObject(body) }.getOrNull()
        val error = json?.optString("error")?.takeIf { it.isNotBlank() }
        when {
            code !in 200..299 -> error?.let { "$name: $it" } ?: "$name said no ($code)"
            json != null && !json.optBoolean("ok", true) -> "$name: ${error ?: "it did not start"}"
            else -> null
        }
    } catch (e: SocketTimeoutException) {
        if (e.message.orEmpty().contains("connect", ignoreCase = true)) "Couldn't reach $name"
        else "$name took too long to start"
    } catch (e: ConnectException) {
        "Couldn't reach $name"
    } catch (e: NoRouteToHostException) {
        "Couldn't reach $name"
    } catch (e: UnknownHostException) {
        "Couldn't reach $name"
    } catch (e: Exception) {
        "$name: ${e.message ?: "something went wrong"}"
    } finally {
        c.disconnect()
    }
}
