package com.albustech.porter

/**
 * A line typed in Porter's command box, read the way desktop adb reads it. The leading "adb" is
 * optional, and anything that isn't an adb command runs as `adb shell <line>`.
 */
sealed interface AdbCommand {
    /** Runs on the watch through an ADB service (shell:, reboot:), streaming its output. */
    data class Service(val service: String) : AdbCommand
    /** `install [-r -d -t -g …]`: pick APKs on the phone, then install them with these options. */
    data class Install(val options: List<String>) : AdbCommand
    /** `push [remote dir]`: pick files on the phone, then copy them there. */
    data class Push(val remoteDir: String) : AdbCommand
    data object Devices : AdbCommand
    data object Help : AdbCommand
    data class Invalid(val message: String) : AdbCommand

    companion object {
        const val DEFAULT_PUSH_DIR = "/sdcard/Download"

        val HELP = """
            shell <cmd>        run a command on the watch (or just type it)
            install [-r -d -t -g]   pick APKs and install them
            push [dir]         pick files and copy them (default $DEFAULT_PUSH_DIR)
            uninstall [-k] <package>
            reboot [bootloader|recovery]
            logcat [args]      live log (Stop ends it)
            devices            the connected watch
        """.trimIndent()

        fun parse(line: String): AdbCommand {
            var words = split(line)
            if (words.firstOrNull() == "adb") words = words.drop(1)
            if (words.isEmpty()) return Invalid("Type a command, for example: shell getprop ro.product.model")
            val rest = words.drop(1)
            // Shell commands go to the watch's shell as typed, so pipes, quotes and > work as there.
            val shellText = line.trim().replaceFirst(Regex("^adb\\s+"), "").let {
                if (words[0] == "shell") it.replaceFirst(Regex("^shell\\s*"), "") else it
            }
            return when (words[0]) {
                "shell" -> if (rest.isEmpty()) Invalid("An interactive shell isn't supported: add a command, e.g. shell ls /sdcard")
                    else Service("shell:$shellText")
                "install" -> {
                    val bad = rest.filterNot { it in INSTALL_FLAGS }
                    if (bad.isNotEmpty()) Invalid("install takes only options (${INSTALL_FLAGS.joinToString(" ")}); you pick the APKs next")
                    else Install(rest)
                }
                "push" -> when (rest.size) {
                    0 -> Push(DEFAULT_PUSH_DIR)
                    1 -> Push(rest[0].trimEnd('/').ifEmpty { "/" })
                    else -> Invalid("push takes one folder on the watch; you pick the files next")
                }
                "uninstall" -> if (rest.none { !it.startsWith("-") }) Invalid("uninstall needs a package name")
                    else Service("shell:pm uninstall " + rest.joinToString(" "))
                "reboot" -> if (rest.size > 1) Invalid("reboot takes at most one target") else Service("reboot:" + rest.joinToString(""))
                "logcat" -> Service("shell:$shellText")
                "devices", "get-state" -> Devices
                "help", "--help", "-h" -> Help
                "connect", "disconnect", "pair", "tcpip", "usb", "kill-server", "start-server", "forward", "reverse", "sideload" ->
                    Invalid("adb ${words[0]} isn't available here; use the buttons above for connecting and pairing")
                // Anything else is a shell command, as if typed after `adb shell`.
                else -> Service("shell:$shellText")
            }
        }

        private val INSTALL_FLAGS = listOf("-r", "-d", "-t", "-g")

        /** Splits on spaces, keeping 'single' and "double" quoted parts together. */
        fun split(line: String): List<String> {
            val words = mutableListOf<String>()
            val cur = StringBuilder()
            var quote: Char? = null
            var inWord = false
            for (c in line.trim()) {
                when {
                    quote != null && c == quote -> quote = null
                    quote != null -> cur.append(c)
                    c == '\'' || c == '"' -> { quote = c; inWord = true }
                    c.isWhitespace() -> if (inWord) { words += cur.toString(); cur.clear(); inWord = false }
                    else -> { cur.append(c); inWord = true }
                }
            }
            if (inWord) words += cur.toString()
            return words
        }
    }
}
