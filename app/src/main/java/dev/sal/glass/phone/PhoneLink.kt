package dev.sal.glass.phone

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import dev.sal.glass.BuildConfig
import dev.sal.glass.data.Place
import dev.sal.glass.data.PhotoInfo
import dev.sal.glass.data.PhotoInfos
import dev.sal.glass.data.Wallpapers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.File
import java.io.IOException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.random.Random

/**
 * The launcher's end of "send a wallpaper from your phone". It keeps one WebSocket open to the
 * relay (cloud/, a Cloudflare Worker with one Durable Object per TV), which announces each photo
 * the moment a phone has sent it, and acknowledges each step so the phone can say where it is.
 *
 * Two kinds of photo. A test is a look: fetched into the cache and put up as a preview ([Screen.
 * preview]), not saved, gone after a while unless the phone says Use this, which saves the copy
 * already here ([Screen.keepPreview]). A keep is a wallpaper: saved with the others and put up
 * ([Screen.keep]); a keep with the same draft as an earlier one (the same photo, reframed) takes
 * the earlier one's place. A keep sent while the TV is off waits at the relay and is collected on
 * the next connection; a test never waits.
 *
 * The connection pings every 25 s (the relay answers without waking up), gives up on a silent
 * one after 70 s, and comes back with a backoff of 1 s doubling to a minute, cut short when the
 * network returns or the home screen comes back ([nudge]). Protocol: cloud/README.md.
 */
class PhoneLink(private val context: Context, private val screen: Screen) {
    /** What the phone link asks of the wallpaper engine; every call is made on the main thread. */
    interface Screen {
        /** Put a test up for a look, not saved; false if it will not decode. */
        suspend fun preview(file: File): Boolean
        /** A saved photo: put it up (or, [show] false, only add it), then take [replaces] off the TV. */
        suspend fun keep(file: File, show: Boolean, replaces: List<File>): Boolean
        /** Use this: [saved] (a copy of [preview]) takes the preview's place on screen, unchanged. */
        suspend fun keepPreview(preview: File, saved: File, replaces: List<File>)
    }

    private sealed interface Work
    private class Offer(
        val id: String, val type: String, val from: String?, val show: Boolean, val test: Boolean, val draft: String?,
        /** When the relay got it, epoch ms. */
        val at: Long?,
        /** What the phone read from the photo before rendering it. */
        val meta: PhotoInfo?,
    ) : Work {
        /** The info card's start for this photo: who sent it, when, and what the phone read from it. */
        fun info() = (meta ?: PhotoInfo()).copy(from = from, sentAt = at)
    }

    /** A place lookup through the relay. Unavailable: no relay, no maps key there, or no network; try again later. */
    sealed interface PlaceAnswer {
        class Found(val place: Place) : PlaceAnswer
        object Nowhere : PlaceAnswer
        object Unavailable : PlaceAnswer
    }
    private class Keep(val id: String, val draft: String?) : Work
    private class End(val code: Int, val problem: String?, val openFor: Long)

    private val keysFile = File(context.filesDir, "phone.json")
    @Volatile private var keys: PhoneKeys = PhoneKeys.load(keysFile) ?: PhoneKeys.fresh().also { PhoneKeys.save(keysFile, it) }
    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val network = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = nudge()
    }
    private var scope: CoroutineScope? = null
    private var server: String? = null
    @Volatile private var socket: WebSocket? = null
    @Volatile private var lastHeard = 0L
    /** When the last check went to the relay, and when its ok came back (see the heartbeat). */
    @Volatile private var checkSentAt = 0L
    @Volatile private var checkOkAt = 0L
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val work = Channel<Work>(Channel.UNLIMITED)
    private val previews = File(context.cacheDir, "preview")
    /** The draft of each test fetched this run, for Use this when the relay's keep omits it. Only the work loop touches it. */
    private val drafts = HashMap<String, String?>()

    /**
     * Connects to the relay at [server] (its base URL) and keeps the connection up. Blank or null
     * turns the link off. Calling it again with the same server changes nothing.
     */
    fun start(server: String?) {
        val base = server?.trim()?.trimEnd('/')?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        if (base == this.server && (scope != null || base == null)) return
        stop()
        this.server = base
        if (base == null) {
            post { Phone.status = Phone.Status.Off; Phone.url = null; Phone.problem = null }
            return
        }
        post { Phone.url = phoneUrl(base); Phone.status = Phone.Status.Connecting }
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        s.launch { connectLoop(base) }
        s.launch {
            for (w in work) when (w) {
                is Offer -> if (w.test) receiveTest(base, w) else receiveKeep(base, w)
                is Keep -> keepTest(w)
            }
        }
        runCatching { connectivity?.registerDefaultNetworkCallback(network) }
    }

    fun stop() {
        scope?.cancel()
        scope = null
        socket?.close(1001, "going away")
        socket = null
        runCatching { connectivity?.unregisterNetworkCallback(network) }
        server = null
    }

    /** Try now instead of waiting out the backoff: the network or the home screen came back. */
    fun nudge() {
        wake.trySend(Unit)
    }

    /**
     * A new send key, so phones that scanned the old QR code can no longer send. The relay hears
     * of it at once over the socket, or on the next connection.
     */
    fun newLink() {
        keys = keys.copy(sendKey = PhoneKeys.newSendKey())
        PhoneKeys.save(keysFile, keys)
        server?.let { base -> post { Phone.url = phoneUrl(base) } }
        send(message("key", "sendKey" to keys.sendKey))
    }

    private fun phoneUrl(base: String) = "$base/#${keys.tvId}.${keys.sendKey}"

    private suspend fun connectLoop(base: String) {
        var failures = 0
        while (true) {
            while (wake.tryReceive().isSuccess) Unit // nudges from before this attempt are spent
            post { Phone.status = Phone.Status.Connecting }
            val end = connectOnce(base)
            if (end.openFor > 60_000) failures = 0
            failures++
            val wait = when (end.code) {
                // Another connection with these keys took over; it is probably us, restarted.
                4000 -> 30_000L
                // The id belongs to someone else at this relay (or the relay lost it): start over
                // with a new identity. The QR code changes with it.
                401 -> {
                    keys = PhoneKeys.fresh().also { PhoneKeys.save(keysFile, it) }
                    post { Phone.url = phoneUrl(base) }
                    1_000L
                }
                else -> (1_000L shl (failures - 1).coerceAtMost(6)).coerceAtMost(60_000L) + Random.nextLong(1_000L)
            }
            Log.i("Glass", "phone link: closed (${end.code} ${end.problem ?: ""}), again in ${wait / 1000} s")
            post { Phone.status = Phone.Status.Offline; Phone.problem = end.problem }
            withTimeoutOrNull(wait) { wake.receive() }
        }
    }

    /** One connection, from the upgrade to whatever ends it. */
    private suspend fun connectOnce(base: String): End = suspendCancellableCoroutine { cont ->
        val k = keys
        val request = Request.Builder()
            .url("$base/api/tv/${k.tvId}/socket")
            .header("Authorization", "Bearer ${k.secret}")
            .header("X-Send-Key", k.sendKey)
            .header("X-TV-Name", Uri.encode(deviceName()))
            .header("User-Agent", "Glass/${BuildConfig.VERSION_NAME}")
            .build()
        var openedAt = 0L
        var heartbeat: Job? = null
        val ended = AtomicBoolean(false)
        fun end(code: Int, problem: String?) {
            if (!ended.compareAndSet(false, true)) return
            heartbeat?.cancel()
            socket = null
            cont.resume(End(code, problem, if (openedAt == 0L) 0L else SystemClock.elapsedRealtime() - openedAt))
        }
        val ws = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                openedAt = SystemClock.elapsedRealtime()
                lastHeard = openedAt
                checkSentAt = 0L
                checkOkAt = 0L
                socket = webSocket
                // If a newer send key was made while offline, the X-Send-Key above already carried it.
                post { Phone.status = Phone.Status.Online; Phone.problem = null }
                Log.i("Glass", "phone link: connected to $base as ${k.tvId}")
                heartbeat = scope?.launch {
                    var beats = 0
                    while (true) {
                        delay(25_000)
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastHeard > 70_000) {
                            Log.w("Glass", "phone link: nothing heard for 70 s, reconnecting")
                            webSocket.cancel()
                            break
                        }
                        // The runtime answers pings even on a socket a relay deploy left behind, so
                        // every couple of minutes ask the relay's own code; silence means reconnect.
                        if (checkSentAt > checkOkAt && now - checkSentAt > 20_000) {
                            Log.w("Glass", "phone link: the relay stopped answering, reconnecting")
                            webSocket.cancel()
                            break
                        }
                        webSocket.send("ping")
                        if (++beats % 5 == 0 && checkSentAt <= checkOkAt) {
                            checkSentAt = now
                            webSocket.send(message("check").toString())
                        }
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                lastHeard = SystemClock.elapsedRealtime()
                if (text != "pong") handle(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                end(code, reason.ifBlank { null })
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = end(code, reason.ifBlank { null })

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                end(response?.code ?: -1, response?.let { "the relay answered ${it.code}" } ?: describe(t))
            }
        })
        cont.invokeOnCancellation { ws.cancel() }
    }

    private fun handle(text: String) {
        val m = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        when (m.string("t")) {
            "wallpaper" -> {
                val id = m.string("id")?.takeIf { ID.matches(it) } ?: return
                work.trySend(
                    Offer(
                        id, m.string("type") ?: "image/jpeg", m.string("from"),
                        show = m["show"]?.jsonPrimitive?.booleanOrNull ?: true,
                        test = m.string("mode") == "test",
                        draft = m.string("draft")?.takeIf { DRAFT.matches(it) },
                        at = (m["at"] as? JsonPrimitive)?.longOrNull,
                        meta = (m["meta"] as? JsonObject)?.let { meta ->
                            PhotoInfo(
                                takenAt = meta.string("takenAt"),
                                lat = (meta["lat"] as? JsonPrimitive)?.doubleOrNull,
                                lon = (meta["lon"] as? JsonPrimitive)?.doubleOrNull,
                                camera = meta.string("camera"),
                            )
                        },
                    ),
                )
            }
            "keep" -> {
                val id = m.string("id")?.takeIf { ID.matches(it) } ?: return
                work.trySend(Keep(id, m.string("draft")?.takeIf { DRAFT.matches(it) }))
            }
            "replaced" -> Log.i("Glass", "phone link: replaced by a newer connection")
            "ok" -> checkOkAt = SystemClock.elapsedRealtime()
        }
    }

    /** A keep: fetch, save, put up, acknowledge; one photo at a time, in the order the relay offered them. */
    private suspend fun receiveKeep(base: String, offer: Offer) {
        // A photo offered again (an acknowledgement lost to a dropped connection) is already here:
        // say so again and leave the screen alone.
        Wallpapers.fromPhone(offer.id)?.let {
            send(message("got", "id" to offer.id))
            if (offer.show) send(message("shown", "id" to offer.id, "visible" to Phone.foreground))
            return
        }
        if (offer.show) notice("Photo from ${offer.from ?: "a phone"}", clears = true)
        val t0 = SystemClock.elapsedRealtime()
        val tmp = File(context.cacheDir, "incoming/${offer.id}.part")
        val file = try {
            download(base, offer, tmp)
            Wallpapers.save(photoName(offer.id, offer.draft, offer.type), tmp)
        } catch (e: Exception) {
            Log.w("Glass", "phone photo ${offer.id}: ${e.message}")
            send(message("failed", "id" to offer.id, "error" to (e.message ?: "download failed")))
            return
        } finally {
            tmp.delete()
        }
        Log.i("Glass", "phone photo ${file.name} (${file.length()} bytes) from ${offer.from ?: "a phone"} in ${SystemClock.elapsedRealtime() - t0} ms")
        PhotoInfos.put(file.name, offer.info())
        send(message("got", "id" to offer.id))
        val ok = onScreen(offer.id) {
            val earlier = earlierVersions(offer.draft, file)
            if (earlier.isNotEmpty()) Log.i("Glass", "phone photo ${file.name} replaces ${earlier.joinToString { it.name }}")
            screen.keep(file, offer.show, earlier)
        }
        when {
            !ok -> send(message("failed", "id" to offer.id, "error" to "the TV could not open that photo"))
            offer.show -> send(message("shown", "id" to offer.id, "visible" to Phone.foreground))
        }
    }

    /** A test: fetch into the cache and put it up for a look. Nothing is saved. */
    private suspend fun receiveTest(base: String, offer: Offer) {
        notice("Test from ${offer.from ?: "a phone"} · not saved", clears = true)
        previews.mkdirs()
        val file = File(previews, "${offer.id}.${extension(offer.type)}")
        val t0 = SystemClock.elapsedRealtime()
        try {
            download(base, offer, file)
        } catch (e: Exception) {
            file.delete()
            Log.w("Glass", "phone test ${offer.id}: ${e.message}")
            send(message("failed", "id" to offer.id, "error" to (e.message ?: "download failed")))
            return
        }
        Log.i("Glass", "phone test ${offer.id} (${file.length()} bytes) in ${SystemClock.elapsedRealtime() - t0} ms")
        drafts[offer.id] = offer.draft
        PhotoInfos.put(file.name, offer.info())
        send(message("got", "id" to offer.id))
        val ok = onScreen(offer.id) { screen.preview(file) }
        if (ok) send(message("shown", "id" to offer.id, "visible" to Phone.foreground))
        else send(message("failed", "id" to offer.id, "error" to "the TV could not open that photo"))
    }

    /** Use this on a test: save the copy already here, in the preview's place. */
    private suspend fun keepTest(keep: Keep) {
        val preview = previews.listFiles { f -> f.nameWithoutExtension == keep.id }?.firstOrNull()
        if (preview == null) {
            // Replaced by a newer test, timed out, or the launcher restarted: the phone sends it again.
            send(message("failed", "id" to keep.id, "error" to "that test is no longer on the TV"))
            return
        }
        val draft = keep.draft ?: drafts[keep.id]
        val saved = try {
            Wallpapers.save(photoName(keep.id, draft, "image/" + preview.extension), preview)
        } catch (e: Exception) {
            send(message("failed", "id" to keep.id, "error" to (e.message ?: "could not save it")))
            return
        }
        PhotoInfos.rename(preview.name, saved.name)
        notice("Saved to your wallpapers", clears = false)
        onScreen(keep.id) { screen.keepPreview(preview, saved, earlierVersions(draft, saved)); true }
        send(message("kept", "id" to keep.id))
    }

    /** Runs a [Screen] call on the main thread; false if it throws. Cancellation goes through. */
    private suspend fun onScreen(id: String, block: suspend () -> Boolean): Boolean = try {
        withContext(Dispatchers.Main) { block() }
    } catch (e: CancellationException) {
        // The link is stopping (the activity went). A keep is saved and will be offered again on
        // the next connection, which finds it here and only acknowledges it.
        throw e
    } catch (e: Exception) {
        Log.w("Glass", "phone photo $id: could not put it up", e)
        false
    }

    /** The earlier keeps of the same photo (same draft), which a new keep replaces. */
    private fun earlierVersions(draft: String?, except: File): List<File> =
        if (draft == null) emptyList() else Wallpapers.ofDraft(draft).filter { it != except }

    /** "phone-<time>[-<draft>]-<id>.jpg": sorts by time, finds by id (re-offers) and by draft (replacing). */
    private fun photoName(id: String, draft: String?, type: String): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return "${Wallpapers.PHONE_PREFIX}$stamp-${draft?.let { "$it-" } ?: ""}$id.${extension(type)}"
    }

    private fun extension(type: String) = when (type) { "image/png" -> "png"; "image/webp" -> "webp"; else -> "jpg" }

    private fun download(base: String, offer: Offer, target: File) {
        val request = Request.Builder()
            .url("$base/api/tv/${keys.tvId}/wallpapers/${offer.id}")
            .header("Authorization", "Bearer ${keys.secret}")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("the relay answered ${response.code}")
            target.parentFile?.mkdirs()
            response.body.byteStream().use { input -> target.outputStream().use { input.copyTo(it, 64 * 1024) } }
        }
    }

    /** Where [lat], [lon] is, through the relay. */
    suspend fun place(lat: Double, lon: Double): PlaceAnswer = lookup("lat=$lat&lon=$lon")

    /** Where a place named [query] is ("aoraki mount cook"), through the relay. */
    suspend fun place(query: String): PlaceAnswer = lookup("q=" + java.net.URLEncoder.encode(query, "UTF-8"))

    private suspend fun lookup(params: String): PlaceAnswer = withContext(Dispatchers.IO) {
        val base = server ?: return@withContext PlaceAnswer.Unavailable
        runCatching {
            val request = Request.Builder().url("$base/api/tv/${keys.tvId}/place?$params").header("Authorization", "Bearer ${keys.secret}").build()
            http.newCall(request).execute().use { r ->
                when (r.code) {
                    200 -> PlaceAnswer.Found(lenient.decodeFromString<Place>(r.body.string()))
                    404 -> PlaceAnswer.Nowhere
                    else -> PlaceAnswer.Unavailable.also { Log.w("Glass", "place lookup: the relay answered ${r.code}") }
                }
            }
        }.getOrElse { Log.w("Glass", "place lookup failed: ${it.message}"); PlaceAnswer.Unavailable }
    }

    /** A small dark map centred on [lat], [lon] (a 1280x720 PNG), through the relay; null if there is none to be had. */
    suspend fun map(lat: Double, lon: Double, zoom: Int = 4): ByteArray? = withContext(Dispatchers.IO) {
        val base = server ?: return@withContext null
        runCatching {
            val request = Request.Builder().url("$base/api/tv/${keys.tvId}/map?lat=$lat&lon=$lon&zoom=$zoom").header("Authorization", "Bearer ${keys.secret}").build()
            http.newCall(request).execute().use { r -> if (r.isSuccessful) r.body.bytes() else null }
        }.getOrNull()
    }

    private fun notice(text: String, clears: Boolean) = post {
        Phone.notice = text
        Phone.noticeClearsScreen = clears
        Phone.notices++
    }

    private fun send(message: JsonObject) {
        socket?.send(message.toString())
    }

    private fun message(type: String, vararg fields: Pair<String, Any?>) = JsonObject(
        mapOf("t" to JsonPrimitive(type)) + fields.associate { (k, v) ->
            k to when (v) {
                is Boolean -> JsonPrimitive(v)
                is Number -> JsonPrimitive(v)
                null -> JsonPrimitive(null as String?)
                else -> JsonPrimitive(v.toString())
            }
        },
    )

    private fun deviceName(): String =
        runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL

    private fun describe(t: Throwable): String = when (t) {
        is UnknownHostException -> "no internet (cannot find the relay)"
        is java.net.SocketTimeoutException -> "the relay did not answer"
        is java.net.ConnectException -> "cannot reach the relay"
        is javax.net.ssl.SSLException -> "secure connection failed"
        else -> t.message ?: t.javaClass.simpleName
    }

    private fun post(block: () -> Unit) {
        main.post(block)
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private companion object {
        /** The relay's upload ids: time then random, lower-case base 36. */
        val ID = Regex("[a-z0-9]{8,40}")
        /** One per photo chosen on the phone. */
        val DRAFT = Regex("[a-z0-9]{8,32}")
        val lenient = Json { ignoreUnknownKeys = true }
    }
}
