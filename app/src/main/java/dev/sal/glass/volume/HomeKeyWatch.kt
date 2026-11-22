package dev.sal.glass.volume

import android.os.SystemClock
import android.util.Log

/**
 * Tells the moment Fire OS starts going Home. It hands that key to no app or accessibility filter,
 * but ActivityTaskManager logs the start of Amazon's launcher before anything has left the screen,
 * so this follows that one tag from that one process. Filtering at the log daemon matters: an HDMI
 * input logs its signal state about 30 times a second, and reading past that fell up to 3 s behind
 * (measured on the Hisense, 2026-11-21). Needs READ_LOGS, granted once over adb:
 *   adb shell pm grant dev.sal.glass android.permission.READ_LOGS
 * Without it logcat only shows this app's own lines and nothing ever fires.
 */
class HomeKeyWatch(private val onHome: () -> Unit) {
    @Volatile private var running = false
    @Volatile private var process: Process? = null

    fun start() {
        if (running) return
        running = true
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
            while (running) {
                runCatching { follow() }.onFailure { Log.w("Glass", "home watch stopped", it) }
                if (running) SystemClock.sleep(2_000)
            }
        }, "home-watch").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        process?.destroy()
    }

    private fun follow() {
        val only = systemServerPid()?.let { listOf("--pid=$it") }.orEmpty()
        val p = ProcessBuilder(listOf("logcat", "-b", "system", "-v", "brief") + only + listOf("-T", "1", "ActivityTaskManager:I", "*:S"))
            .redirectErrorStream(true).start()
        process = p
        val started = SystemClock.uptimeMillis()
        try {
            // Raw reads, not a buffered reader: this has to act on a line the moment it lands.
            val input = p.inputStream
            val chunk = ByteArray(2048)
            val line = StringBuilder()
            var onHdmi = false
            while (running) {
                val n = input.read(chunk)
                if (n < 0) break
                for (i in 0 until n) {
                    val c = chunk[i].toInt().toChar()
                    if (c != '\n') { if (line.length < 1024) line.append(c); continue }
                    // -T 1 can replay the last line from before this started.
                    val fresh = SystemClock.uptimeMillis() - started > 600
                    if (line.contains("START u0")) {
                        when {
                            line.contains("tvinput://") -> onHdmi = true
                            isHome(line) -> { if (onHdmi && fresh) onHome(); onHdmi = false }
                            else -> onHdmi = false
                        }
                    }
                    line.setLength(0)
                }
            }
        } finally {
            p.destroy()
        }
    }

    /**
     * The process that writes ActivityTaskManager's lines. With --pid the log daemon itself leaves
     * out everything else, so an HDMI input's flood of signal lines never reaches this reader.
     */
    private fun systemServerPid(): String? = runCatching {
        val p = ProcessBuilder("logcat", "-d", "-b", "system", "-v", "brief", "-t", "200", "ActivityTaskManager:I", "*:S")
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        p.destroy()
        Regex("""\(\s*(\d+)\)""").findAll(out).lastOrNull()?.groupValues?.get(1)
    }.getOrNull()

    private fun isHome(line: CharSequence) =
        line.contains("category.HOME") && line.contains("cmp=com.amazon.tv.launcher")
}
