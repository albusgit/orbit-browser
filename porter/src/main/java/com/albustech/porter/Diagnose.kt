package com.albustech.porter

import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbPairingRequiredException
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Turns connection failures into what to do about them. */
object Diagnose {
    /** The watch can't be reached at that address and port; the message says why and what to check. */
    class Unreachable(message: String, cause: Throwable) : IOException(message, cause)

    /**
     * A plain TCP connect, before any ADB, so a wrong port or an unreachable watch gets a clear
     * answer instead of a TLS or protocol error. Throws [Unreachable] with the explanation.
     */
    fun probe(host: String, port: Int, what: String) {
        try {
            Socket().use { it.connect(InetSocketAddress(host, port), 5_000) }
        } catch (e: ConnectException) {
            throw Unreachable(
                "$host answered, but nothing is listening on port $port. $what " +
                    "changes port each time it's turned on: check the number on the watch.", e,
            )
        } catch (e: SocketTimeoutException) {
            throw Unreachable(unreachable(host), e)
        } catch (e: NoRouteToHostException) {
            throw Unreachable(unreachable(host), e)
        } catch (e: UnknownHostException) {
            throw Unreachable("\"$host\" isn't an IP address. Use the one shown under Wireless debugging.", e)
        }
    }

    private fun unreachable(host: String) =
        "Can't reach $host. Check the phone and watch are on the same Wi-Fi, and that the watch's Wi-Fi " +
            "is on (on Galaxy Watches it turns off when the screen sleeps; keep the screen on, or set Wi-Fi to Always on)."

    /** The real reason libadb gave up, which it otherwise reports only as "Connection failed". */
    fun hiddenCause(manager: AbsAdbConnectionManager): Throwable? = runCatching {
        val conn = manager.adbConnection ?: return null
        val f = conn.javaClass.getDeclaredField("mConnectionException")
        f.isAccessible = true
        f.get(conn) as Throwable?
    }.getOrNull()

    fun connectFailure(e: Throwable, hidden: Throwable?): String {
        if (e is AdbPairingRequiredException) {
            return "The watch doesn't know this phone yet: pair it first (Pair this phone, below)."
        }
        if (e is Unreachable) return e.message!!
        val root = rootOf(hidden ?: e)
        return when {
            root is SSLException && root.message.orEmpty().contains("protocol error", true) ->
                "The watch doesn't know this phone yet: pair it first (Pair this phone, below)."
            root is SSLException || root is EOFException ->
                "The watch closed the secure connection (${chain(hidden ?: e)}). If you just paired, try Start " +
                    "again; otherwise pair again."
            else -> chain(hidden ?: e)
        }
    }

    fun pairFailure(e: Throwable): String {
        if (e is Unreachable) return e.message!!
        val root = rootOf(e)
        return when {
            root is EOFException || root is SSLException ||
                e.message.orEmpty().let { "Exchanging message" in it || "peer info" in it } ->
                "The watch ended the pairing (${chain(e)}). The code was probably wrong or expired: open " +
                    "Pair new device again and use the new code and port."
            else -> "Pairing failed: ${chain(e)}"
        }
    }

    private fun rootOf(e: Throwable) = generateSequence(e) { it.cause }.last()

    /** Every message in the cause chain, so the log says what really happened. */
    fun chain(e: Throwable): String = generateSequence(e) { it.cause }.take(6)
        .map { t -> t.message?.takeIf { it.isNotBlank() }?.let { "${t.javaClass.simpleName}: $it" } ?: t.javaClass.simpleName }
        .distinct()
        .joinToString(" ← ")
}
