package com.albustech.porter

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Phase { Off, Connecting, Connected, Reconnecting }

enum class Status { Waiting, Sending, Done, Failed }

data class Transfer(
    val id: Long,
    val name: String,
    val isApk: Boolean,
    val size: Long,
    val sent: Long = 0,
    val status: Status = Status.Waiting,
)

data class PorterState(
    val phase: Phase = Phase.Off,
    val message: String = "",
    val host: String = "",
    val port: Int = 0,
    val transfers: List<Transfer> = emptyList(),
    val log: List<String> = emptyList(),
)

/**
 * Everything Porter does, kept for the life of the process. A "port" is a session: connect, raise
 * the watch's screen timeout, keep the connection alive (reconnecting when it drops), and on stop
 * put the timeout back and disconnect. [PortService] keeps the process and Wi-Fi up meanwhile.
 */
class Porter(private val context: Context) {
    val prefs = Prefs(context)
    // Internal so screenshot tests can show a busy session.
    internal val _state = MutableStateFlow(PorterState(host = prefs.host, port = prefs.port))
    val state: StateFlow<PorterState> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val keys by lazy { AdbKeys.loadOrCreate(context.filesDir) }
    @Volatile private var adb: WatchAdb? = null
    private var session: Job? = null
    private var mdns: AdbMdns? = null
    private val jobs = Channel<Pair<Transfer, Uri>>(Channel.UNLIMITED)
    private var nextId = 1L

    init {
        scope.launch { runTransfers() }
    }

    // --- Port session ---

    fun start(host: String, port: Int) {
        if (session?.isActive == true) return
        prefs.host = host
        prefs.port = port
        _state.update { it.copy(host = host, port = port) }
        ContextCompat.startForegroundService(context, Intent(context, PortService::class.java))
        startMdns(host)
        session = scope.launch { keepConnected() }
    }

    fun stop() {
        val running = session ?: return
        session = null
        stopMdns()
        scope.launch {
            setPhase(Phase.Off, "Stopping…")
            running.cancelAndJoin()
            val conn = adb
            if (conn != null) restoreTimeout(conn)
            else if (prefs.savedTimeoutMs > 0) log("Watch not connected: its screen timeout goes back on the next stop")
            closeAdb()
            setPhase(Phase.Off, "")
            context.stopService(Intent(context, PortService::class.java))
        }
    }

    private suspend fun keepConnected() {
        var wait = 2_000L
        var first = true
        while (scope.isActive) {
            val s = _state.value
            setPhase(if (first) Phase.Connecting else Phase.Reconnecting, "Connecting to ${s.host}:${s.port}…")
            val conn = WatchAdb(keys)
            try {
                if (!conn.connect(s.host, s.port)) throw IllegalStateException("The watch didn't accept the connection")
                adb = conn
                prefs.paired = true
                log("Connected to ${s.host}:${s.port}")
                applyTimeout(conn)
                setPhase(Phase.Connected, "")
                wait = 2_000L
                first = false
                // Keep-alive: a cheap command every 15 s. Wi-Fi sleep and NAT drop idle sockets.
                while (scope.isActive) {
                    delay(15_000)
                    if (_state.value.transfers.any { it.status == Status.Sending }) continue
                    withTimeout(10_000) { runInterruptible { conn.exec("echo") } }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: AdbPairingRequiredException) {
                closeAdb()
                runCatching { conn.close() }
                setPhase(Phase.Reconnecting, "Pair this phone first (see Pair below)")
                log("Not paired with ${s.host}")
            } catch (e: Throwable) {
                closeAdb()
                runCatching { conn.close() }
                val why = e.message ?: e.javaClass.simpleName
                setPhase(Phase.Reconnecting, "Lost the watch: $why. Retrying…")
                log("Connection: $why")
            }
            delay(wait)
            wait = (wait * 2).coerceAtMost(15_000L)
        }
    }

    private fun closeAdb() {
        runCatching { adb?.close() }
        adb = null
    }

    // --- Screen timeout ---

    private fun applyTimeout(conn: WatchAdb) {
        val wanted = prefs.timeoutMs
        if (wanted <= 0) return
        // Keep the watch's own value from the first connect only: a reconnect sees ours.
        if (prefs.savedTimeoutMs <= 0) {
            val current = conn.exec("settings get system screen_off_timeout").trim().toIntOrNull()
            if (current == null) {
                log("Couldn't read the watch's screen timeout")
                return
            }
            prefs.savedTimeoutMs = current
        }
        conn.exec("settings put system screen_off_timeout $wanted")
        log("Screen timeout ${formatMs(wanted)} (was ${formatMs(prefs.savedTimeoutMs)})")
    }

    private fun restoreTimeout(conn: WatchAdb) {
        val saved = prefs.savedTimeoutMs
        if (saved <= 0) return
        runCatching { conn.exec("settings put system screen_off_timeout $saved") }
            .onSuccess {
                prefs.savedTimeoutMs = 0
                log("Screen timeout back to ${formatMs(saved)}")
            }
            .onFailure { log("Couldn't restore the screen timeout; it will be on the next stop") }
    }

    // --- Pairing and discovery ---

    suspend fun pair(host: String, pairingPort: Int, code: String): Boolean {
        log("Pairing with $host:$pairingPort…")
        return try {
            val ok = WatchAdb(keys).use { it.pair(host, pairingPort, code) }
            if (ok) prefs.paired = true
            log(if (ok) "Paired. Now start the port." else "Pairing failed")
            ok
        } catch (e: Throwable) {
            log("Pairing failed: ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    /** Fills in host and port from the watch's Wireless debugging announcement (same Wi-Fi). */
    fun discover() {
        log("Looking for the watch…")
        val finder = AdbMdns(context, AdbMdns.SERVICE_TYPE_TLS_CONNECT) { addr, port ->
            val host = addr?.hostAddress
            if (host != null && port > 0) {
                _state.update { it.copy(host = host, port = port) }
                log("Found $host:$port")
            }
        }
        finder.start()
        scope.launch { delay(10_000); finder.stop() }
    }

    /** While porting, follow the watch if Wireless debugging restarts on a new port. */
    private fun startMdns(host: String) {
        stopMdns()
        mdns = AdbMdns(context, AdbMdns.SERVICE_TYPE_TLS_CONNECT) { addr, port ->
            if (addr?.hostAddress == host && port > 0 && port != _state.value.port) {
                prefs.port = port
                _state.update { it.copy(port = port) }
                log("The watch moved to port $port")
            }
        }.also { it.start() }
    }

    private fun stopMdns() {
        runCatching { mdns?.stop() }
        mdns = null
    }

    // --- Files ---

    fun enqueue(uris: List<Uri>) {
        for (uri in uris) {
            val (name, size) = describe(uri)
            val isApk = name.endsWith(".apk", ignoreCase = true) ||
                context.contentResolver.getType(uri) == "application/vnd.android.package-archive"
            val t = Transfer(nextId++, name, isApk, size)
            _state.update { it.copy(transfers = it.transfers + t) }
            jobs.trySend(t to uri)
        }
    }

    fun clearFinished() = _state.update { s ->
        s.copy(transfers = s.transfers.filter { it.status == Status.Waiting || it.status == Status.Sending })
    }

    private suspend fun runTransfers() {
        for ((t, uri) in jobs) {
            var retried = false
            while (true) {
                // Files picked before the port is up wait for the connection.
                state.first { it.phase == Phase.Connected }
                val conn = adb
                if (conn == null) {
                    delay(500)
                    continue
                }
                setTransfer(t.id) { it.copy(status = Status.Sending, sent = 0) }
                try {
                    val result = runInterruptible { send(conn, t, uri) }
                    val ok = !t.isApk || result.startsWith("Success")
                    setTransfer(t.id) { it.copy(status = if (ok) Status.Done else Status.Failed) }
                    log("${t.name}: ${if (t.isApk && ok) "installed" else result}")
                    break
                } catch (e: Throwable) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    val dropped = adb !== conn || !conn.isConnected
                    if (dropped && !retried) {
                        // The connection went mid-transfer: start over once it's back.
                        retried = true
                        setTransfer(t.id) { it.copy(status = Status.Waiting, sent = 0) }
                        log("${t.name}: connection lost, will retry")
                        delay(1_000)
                        continue
                    }
                    setTransfer(t.id) { it.copy(status = Status.Failed) }
                    log("${t.name}: ${e.message ?: e.javaClass.simpleName}")
                    break
                }
            }
        }
    }

    private fun send(conn: WatchAdb, t: Transfer, uri: Uri): String {
        val progress = { sent: Long -> setTransfer(t.id) { it.copy(sent = sent) } }
        if (!t.isApk) {
            val remote = "/sdcard/Download/${t.name.replace('/', '_')}"
            open(uri).use { conn.push(remote, (System.currentTimeMillis() / 1000).toInt(), it, progress) }
            return "saved to $remote"
        }
        if (t.size >= 0) return open(uri).use { conn.install(t.size, it, progress) }
        // pm needs the size up front: copy to a temp file when the provider doesn't say.
        val tmp = File.createTempFile("install", ".apk", context.cacheDir)
        try {
            open(uri).use { input -> tmp.outputStream().use { input.copyTo(it) } }
            setTransfer(t.id) { it.copy(size = tmp.length()) }
            return tmp.inputStream().buffered(256 * 1024).use { conn.install(tmp.length(), it, progress) }
        } finally {
            tmp.delete()
        }
    }

    private fun open(uri: Uri) =
        (context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("Can't open the file")).buffered(256 * 1024)

    private fun describe(uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment ?: "file"
        var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        if (size < 0) {
            size = runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")!!.use { it.length } }.getOrDefault(-1L)
        }
        return name.substringAfterLast('/') to size
    }

    // --- State helpers ---

    private fun setTransfer(id: Long, change: (Transfer) -> Transfer) =
        _state.update { s -> s.copy(transfers = s.transfers.map { if (it.id == id) change(it) else it }) }

    private fun setPhase(phase: Phase, message: String) = _state.update { it.copy(phase = phase, message = message) }

    fun log(line: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        _state.update { it.copy(log = (it.log + "$stamp  $line").takeLast(50)) }
    }

    companion object {
        fun formatMs(ms: Int): String = when {
            ms >= 60_000 && ms % 60_000 == 0 -> "${ms / 60_000} min"
            else -> "${ms / 1000} s"
        }
    }
}
