package com.albustech.porter

import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.security.PrivateKey
import java.security.cert.Certificate
import java.util.concurrent.TimeUnit

/** One ADB connection to the watch, with the few commands Porter needs. */
class WatchAdb(private val keys: AdbKeys) : AbsAdbConnectionManager() {
    init {
        // Wireless debugging is Android 11+ and always TLS.
        api = maxOf(Build.VERSION.SDK_INT, Build.VERSION_CODES.R)
        setTimeout(10, TimeUnit.SECONDS)
    }

    override fun getPrivateKey(): PrivateKey = keys.privateKey
    override fun getCertificate(): Certificate = keys.certificate
    override fun getDeviceName(): String = "Porter"

    /** Runs a command on the watch and returns its output (stdout and stderr). */
    fun exec(command: String): String = openStream("exec:$command").use { readAll(it) }

    /**
     * Streams an APK into `pm install` (no copy left on the watch). [onProgress] gets bytes sent.
     * Returns pm's output; it starts with "Success" when the install worked.
     */
    fun install(size: Long, input: InputStream, onProgress: (Long) -> Unit): String =
        openStream("exec:cmd package install -r -S $size").use { stream ->
            copy(input, stream, onProgress)
            stream.flush()
            readAll(stream).trim()
        }

    /** Copies a file to [remotePath] with the sync protocol (what `adb push` does). */
    fun push(remotePath: String, mtimeSec: Int, input: InputStream, onProgress: (Long) -> Unit) {
        openStream("sync:").use { stream ->
            val path = "$remotePath,${SyncProtocol.FILE_MODE}".toByteArray()
            stream.writeFully(SyncProtocol.header("SEND", path.size) + path)
            val buf = ByteArray(SyncProtocol.MAX_DATA)
            var sent = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) continue
                stream.writeFully(SyncProtocol.header("DATA", n) + buf.copyOf(n))
                sent += n
                onProgress(sent)
            }
            stream.writeFully(SyncProtocol.header("DONE", mtimeSec))
            val reply = ByteArray(8).also { stream.readFully(it) }
            when (SyncProtocol.id(reply)) {
                "OKAY" -> Unit
                "FAIL" -> {
                    val msg = ByteArray(SyncProtocol.length(reply)).also { stream.readFully(it) }
                    throw IOException(String(msg))
                }
                else -> throw IOException("Unexpected reply from the watch")
            }
            stream.writeFully(SyncProtocol.header("QUIT", 0))
        }
    }

    private fun copy(input: InputStream, stream: AdbStream, onProgress: (Long) -> Unit) {
        val buf = ByteArray(64 * 1024)
        var sent = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            stream.write(buf, 0, n)
            sent += n
            onProgress(sent)
        }
    }

    private fun readAll(stream: AdbStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = try { stream.read(buf, 0, buf.size) } catch (e: IOException) { if (stream.isClosed) -1 else throw e }
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun AdbStream.writeFully(bytes: ByteArray) = write(bytes, 0, bytes.size)

    private fun AdbStream.readFully(into: ByteArray) {
        var off = 0
        while (off < into.size) {
            val n = read(into, off, into.size - off)
            if (n < 0) throw EOFException("The watch closed the transfer")
            off += n
        }
    }
}

/** The framing of ADB's file sync service: a 4-byte id and a little-endian 32-bit argument. */
object SyncProtocol {
    const val MAX_DATA = 64 * 1024
    /** Regular file, rw-r--r--. */
    const val FILE_MODE = 33188

    fun header(id: String, arg: Int): ByteArray {
        require(id.length == 4)
        return id.toByteArray(Charsets.US_ASCII) + byteArrayOf(
            arg.toByte(), (arg shr 8).toByte(), (arg shr 16).toByte(), (arg shr 24).toByte(),
        )
    }

    fun id(header: ByteArray): String = String(header, 0, 4, Charsets.US_ASCII)

    fun length(header: ByteArray): Int =
        (header[4].toInt() and 0xff) or ((header[5].toInt() and 0xff) shl 8) or
            ((header[6].toInt() and 0xff) shl 16) or ((header[7].toInt() and 0xff) shl 24)
}
