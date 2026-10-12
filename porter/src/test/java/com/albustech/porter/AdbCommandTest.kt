package com.albustech.porter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbCommandTest {
    private fun parse(line: String) = AdbCommand.parse(line)

    @Test fun shellTextGoesThroughAsTyped() {
        assertEquals(AdbCommand.Service("shell:ls /sdcard | wc -l"), parse("adb shell ls /sdcard | wc -l"))
        assertEquals(AdbCommand.Service("shell:echo \"a  b\" > /sdcard/x"), parse("shell echo \"a  b\" > /sdcard/x"))
    }

    @Test fun anythingElseRunsAsShell() {
        assertEquals(AdbCommand.Service("shell:pm list packages -3"), parse("pm list packages -3"))
        assertEquals(AdbCommand.Service("shell:logcat -d -s OrbitMem"), parse("adb logcat -d -s OrbitMem"))
    }

    @Test fun installAndPushPickFiles() {
        assertEquals(AdbCommand.Install(emptyList()), parse("adb install"))
        assertEquals(AdbCommand.Install(listOf("-r", "-d")), parse("install -r -d"))
        assertTrue(parse("install app.apk") is AdbCommand.Invalid)
        assertEquals(AdbCommand.Push("/sdcard/Download"), parse("adb push"))
        assertEquals(AdbCommand.Push("/sdcard/Music"), parse("push /sdcard/Music/"))
    }

    @Test fun otherAdbCommands() {
        assertEquals(AdbCommand.Service("shell:pm uninstall -k com.example"), parse("adb uninstall -k com.example"))
        assertTrue(parse("uninstall") is AdbCommand.Invalid)
        assertEquals(AdbCommand.Service("reboot:"), parse("adb reboot"))
        assertEquals(AdbCommand.Service("reboot:recovery"), parse("reboot recovery"))
        assertEquals(AdbCommand.Devices, parse("adb devices"))
        assertEquals(AdbCommand.Help, parse("help"))
        assertTrue(parse("adb connect 1.2.3.4:5555") is AdbCommand.Invalid)
        assertTrue(parse("adb shell") is AdbCommand.Invalid)
        assertTrue(parse("  ") is AdbCommand.Invalid)
    }

    @Test fun splitKeepsQuotedWords() {
        assertEquals(listOf("a", "b c", "d"), AdbCommand.split("a 'b c'  \"d\""))
    }
}
