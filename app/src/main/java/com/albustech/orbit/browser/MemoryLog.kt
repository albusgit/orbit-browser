package com.albustech.orbit.browser

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Logs memory after each page load (tag `OrbitMem`) for the "10 page loads" acceptance check:
 *   adb logcat -s OrbitMem
 * App PSS excludes the WebView renderer, which runs in its own sandboxed process; the
 * system-wide availMem line covers that.
 */
class MemoryLog(context: Context, private val scope: CoroutineScope) {

    private val activityManager = context.getSystemService(ActivityManager::class.java)
    private var loads = 0

    fun onPageLoaded(url: String?) {
        // Diagnostics only: release builds skip the smaps walk entirely.
        if (!com.albustech.orbit.BuildConfig.DEBUG) return
        val n = ++loads
        // Debug.getMemoryInfo walks smaps and can take tens of ms: keep it off the main thread.
        scope.launch(Dispatchers.Default) {
            val info = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
            val sys = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
            val rt = Runtime.getRuntime()
            Log.i(
                TAG,
                "load=$n pssKb=${info.totalPss} javaHeapKb=${(rt.totalMemory() - rt.freeMemory()) / 1024} " +
                    "nativeHeapKb=${Debug.getNativeHeapAllocatedSize() / 1024} " +
                    "sysAvailMb=${sys.availMem / (1024 * 1024)} lowMemory=${sys.lowMemory} url=$url",
            )
        }
    }

    private companion object {
        const val TAG = "OrbitMem"
    }
}
