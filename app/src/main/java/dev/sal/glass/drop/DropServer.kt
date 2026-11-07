package dev.sal.glass.drop

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * What last arrived over the drop endpoint. The home screen watches [serial] and opens
 * [incoming]: a PDF or an image in the viewer, a link in the browser.
 */
object Drop {
    const val PORT = 8765
    var incoming by mutableStateOf<File?>(null)
        internal set
    var serial by mutableLongStateOf(0L)
        internal set

    /** The address to type on a laptop or phone, or null without a network. */
    fun address(): String? = localIp()?.let { "http://$it:$PORT" }

    fun localIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it.isSiteLocalAddress && it.hostAddress?.contains('.') == true }
            ?.hostAddress
    }.getOrNull()
}

/**
 * A very small HTTP server on the TV, for the home network only (no authentication, so do not
 * expose the port). GET / serves a page with a drop zone; PUT or POST /drop/<name> (or `curl -T
 * file http://tv:8765/`) stores the body under files/drop and hands it to the home screen. No
 * multipart: the page sends the raw file as the body, which is what curl -T does too.
 */
class DropServer(private val dir: File) {
    private var socket: ServerSocket? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        dir.mkdirs()
        Thread({ serve() }, "GlassDrop").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
    }

    private fun serve() {
        val server = runCatching { ServerSocket(Drop.PORT) }
            .onFailure { Log.w("Glass", "drop: cannot listen on ${Drop.PORT}", it) }
            .getOrNull() ?: return
        socket = server
        Log.i("Glass", "drop: listening at ${Drop.address()}")
        while (running) {
            val client = runCatching { server.accept() }.getOrNull() ?: break
            Thread({
                runCatching { handle(client) }.onFailure { Log.w("Glass", "drop: request failed", it) }
                runCatching { client.close() }
            }, "GlassDropClient").start()
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = 60_000
        val input = BufferedInputStream(client.getInputStream(), 64 * 1024)
        val out = client.getOutputStream()
        val request = readLine(input) ?: return
        val parts = request.split(' ')
        if (parts.size < 2) return
        val method = parts[0].uppercase()
        val target = parts[1]
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        when {
            method == "GET" && (target == "/" || target.startsWith("/?") || target == "/index.html") ->
                respond(out, 200, "text/html; charset=utf-8", PAGE.toByteArray())
            method == "PUT" || method == "POST" -> receive(target, headers, input, out)
            else -> respond(out, 404, "text/plain", "not found\n".toByteArray())
        }
    }

    private fun receive(target: String, headers: Map<String, String>, input: InputStream, out: OutputStream) {
        val length = headers["content-length"]?.toLongOrNull() ?: -1L
        if (length <= 0) { respond(out, 411, "text/plain", "length required\n".toByteArray()); return }
        if (length > MAX_BYTES) { respond(out, 413, "text/plain", "too large\n".toByteArray()); return }
        // curl waits for this before it sends a large body.
        if (headers["expect"]?.lowercase()?.contains("100-continue") == true) {
            out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray()); out.flush()
        }
        val raw = URLDecoder.decode(target.substringAfterLast('/').substringBefore('?'), "UTF-8")
        val name = raw.replace(Regex("[^A-Za-z0-9._ ()-]"), "_").trim().ifBlank { "drop-${System.currentTimeMillis()}" }
        val file = File(dir, name)
        val tmp = File(dir, "$name.part")
        tmp.outputStream().use { fo ->
            val buf = ByteArray(64 * 1024)
            var left = length
            while (left > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                fo.write(buf, 0, n)
                left -= n
            }
            if (left > 0) { tmp.delete(); respond(out, 400, "text/plain", "short body\n".toByteArray()); return }
        }
        if (file.exists()) file.delete()
        tmp.renameTo(file)
        respond(out, 200, "text/plain", "ok: ${file.name}\n".toByteArray())
        Log.i("Glass", "drop: ${file.name} (${file.length()} bytes)")
        Handler(Looper.getMainLooper()).post { Drop.incoming = file; Drop.serial++ }
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val reason = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 404 -> "Not Found"; 411 -> "Length Required"; 413 -> "Payload Too Large"; else -> "Error" }
        out.write("HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(body)
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
            if (sb.length > 8192) return null
        }
        return sb.toString()
    }

    companion object {
        const val MAX_BYTES = 512L * 1024 * 1024

        private val PAGE = """<!doctype html>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Drop to the TV</title>
<style>
body{margin:0;min-height:100vh;display:grid;place-items:center;background:#101318;color:#eef;font:16px/1.4 -apple-system,Inter,system-ui,sans-serif}
#z{width:min(640px,88vw);padding:56px 28px;border:2px dashed #55607a;border-radius:28px;text-align:center;transition:.2s;box-sizing:border-box}
#z.over{border-color:#9ec1ff;background:#182033}
h1{font-weight:600;font-size:22px;margin:0 0 6px}p{margin:6px 0;color:#9aa3b8}
input[type=file]{display:none}
label.b,button{display:inline-block;margin-top:14px;padding:10px 18px;border-radius:999px;background:#2a3550;color:#fff;border:0;font:inherit;cursor:pointer}
form{margin-top:22px;display:flex;gap:8px;justify-content:center;flex-wrap:wrap}
input[type=url]{flex:1;min-width:220px;max-width:380px;padding:10px 14px;border-radius:999px;border:1px solid #3a4560;background:#0c0f14;color:#fff;font:inherit}
#s{margin-top:18px;min-height:1.4em;color:#cfe0ff}
</style>
<div id="z">
  <h1>Drop it on the TV</h1>
  <p>A PDF, an image, or a link. Drag a file here, or</p>
  <label class="b">Choose a file<input type="file" id="f" multiple></label>
  <form id="u"><input type="url" id="l" placeholder="https://…" required><button>Open on the TV</button></form>
  <div id="s"></div>
</div>
<script>
const z=document.getElementById('z'),s=document.getElementById('s');
async function send(name,body){s.textContent='Sending '+name+'…';try{const r=await fetch('/drop/'+encodeURIComponent(name),{method:'PUT',body});s.textContent=r.ok?'On the TV: '+name:'Failed ('+r.status+')'}catch(e){s.textContent='Failed: '+e}}
z.addEventListener('dragover',e=>{e.preventDefault();z.classList.add('over')});
z.addEventListener('dragleave',()=>z.classList.remove('over'));
z.addEventListener('drop',e=>{e.preventDefault();z.classList.remove('over');for(const f of e.dataTransfer.files)send(f.name,f)});
document.getElementById('f').addEventListener('change',e=>{for(const f of e.target.files)send(f.name,f)});
document.getElementById('u').addEventListener('submit',e=>{e.preventDefault();send('link.url',document.getElementById('l').value)});
</script>
"""
    }
}
