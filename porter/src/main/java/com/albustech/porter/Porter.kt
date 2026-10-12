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
    /** pm install options for an APK. */
    val installOptions: List<String> = listOf("-r"),
    /** Where a non-APK file goes on the watch. */
    val remoteDir: String = AdbCommand.DEFAULT_PUSH_DIR,
)

/** The command box: what's been run and printed, and whether a command is still going. */
data class Console(val output: String = "", val running: Boolean = false)

data class PorterState(
    val phase: Phase = Phase.Off,
    val message: String = "",
    val host: String = "",
    val port: Int = 0,
    val transfers: List<Transfer> = emptyList(),
    val log: List<String> = emptyList(),
    val console: Console = Console(),
    /** The last pairing attempt's result, shown under the Pair button. */
    val pairStatus: String = "",
    val pairing: Boolean = false,
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

    fun pair(host: String, pairingPort: Int, code: String) {
        if (_state.value.pairing) return
        setPair(true, "Pairing with $host:$pairingPort…")
        // Off the main thread: the handshake is blocking network I/O.
        scope.launch {
            val result = try {
                val ok = WatchAdb(keys).use { it.pair(host, pairingPort, code) }
                if (ok) prefs.paired = true
                if (ok) "Paired. Now tap Start port (it uses the other port, on the main Wireless debugging screen)."
                else "The watch turned the pairing down. Check the code and try again."
            } catch (e: Throwable) {
                pairingError(e, host, pairingPort)
            }
            log(result)
            setPair(false, result)
        }
    }

    private fun setPair(running: Boolean, status: String) = _state.update { it.copy(pairing = running, pairStatus = status) }

    private fun pairingError(e: Throwable, host: String, port: Int): String {
        val root = generateSequence(e) { it.cause }.last()
        return when (root) {
            is java.net.ConnectException, is java.net.NoRouteToHostException ->
                "Couldn't reach $host:$port. Keep the watch's Pair new device screen open, and use the " +
                    "pairing port shown there (not the main Wireless debugging port)."
            is java.net.SocketTimeoutException -> "$host:$port didn't answer. Are the phone and watch on the same Wi-Fi?"
            is java.io.EOFException, is javax.net.ssl.SSLException ->
                "The watch ended the pairing. The code is probably wrong or expired: open Pair new device again for a new one."
            else -> "Pairing failed: " + generateSequence(e) { it.cause }
                .joinToString(" ← ") { it.message ?: it.javaClass.simpleName }
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

    /**
     * Queues files for the watch. APKs are installed unless [push] (then everything is copied);
     * [installOptions] and [remoteDir] come from typed `install` and `push` commands.
     */
    fun enqueue(
        uris: List<Uri>,
        installOptions: List<String> = listOf("-r"),
        remoteDir: String = AdbCommand.DEFAULT_PUSH_DIR,
        push: Boolean = false,
    ) {
        for (uri in uris) {
            val (name, size) = describe(uri)
            val isApk = !push && (name.endsWith(".apk", ignoreCase = true) ||
                context.contentResolver.getType(uri) == "application/vnd.android.package-archive")
            val t = Transfer(nextId++, name, isApk, size, installOptions = installOptions, remoteDir = remoteDir)
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
            val remote = "${t.remoteDir.trimEnd('/')}/${t.name.replace('/', '_')}"
            open(uri).use { conn.push(remote, (System.currentTimeMillis() / 1000).toInt(), it, progress) }
            return "saved to $remote"
        }
        if (t.size >= 0) return open(uri).use { conn.install(t.size, t.installOptions, it, progress) }
        // pm needs the size up front: copy to a temp file when the provider doesn't say.
        val tmp = File.createTempFile("install", ".apk", context.cacheDir)
        try {
            open(uri).use { input -> tmp.outputStream().use { input.copyTo(it) } }
            setTransfer(t.id) { it.copy(size = tmp.length()) }
            return tmp.inputStream().buffered(256 * 1024).use { conn.install(tmp.length(), t.installOptions, it, progress) }
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

    // --- Command box ---

    private var command: Job? = null

    /** Runs a typed command (see [AdbCommand]) and streams what it prints into the console. */
    fun runCommand(line: String, cmd: AdbCommand) {
        if (command?.isActive == true) return
        echo("> $line\n")
        when (cmd) {
            is AdbCommand.Help -> echo(AdbCommand.HELP + "\n")
            is AdbCommand.Invalid -> echo(cmd.message + "\n")
            is AdbCommand.Devices -> {
                val s = _state.value
                echo(if (s.phase == Phase.Connected) "${s.host}:${s.port}\tdevice\n" else "No watch connected. Tap Start port first.\n")
            }
            // The screen opens the file picker; the picked files join the queue.
            is AdbCommand.Install -> echo("Pick the APKs to install…\n")
            is AdbCommand.Push -> echo("Pick the files to copy to ${cmd.remoteDir}…\n")
            is AdbCommand.Service -> {
                val conn = adb
                if (conn == null || _state.value.phase != Phase.Connected) {
                    echo("No watch connected. Tap Start port first.\n")
                    return
                }
                _state.update { it.copy(console = it.console.copy(running = true)) }
                command = scope.launch {
                    try {
                        runInterruptible { conn.stream(cmd.service) { echo(it.replace("\r\n", "\n")) } }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        echo("\n[stopped]\n")
                    } catch (e: Throwable) {
                        echo("\nerror: ${e.message ?: e.javaClass.simpleName}\n")
                    } finally {
                        _state.update { it.copy(console = it.console.copy(running = false)) }
                    }
                }
            }
        }
    }

    fun stopCommand() {
        command?.cancel()
    }

    fun clearConsole() = _state.update { it.copy(console = it.console.copy(output = "")) }

    /** Appends to the console, keeping the last 64k characters (logcat can run all day). */
    fun echo(text: String) = _state.update {
        val out = (it.console.output + text).let { o -> if (o.length > 65_536) o.substring(o.length - 65_536) else o }
        it.copy(console = it.console.copy(output = out))
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
