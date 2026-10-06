package com.albustech.orbit.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.graphics.createBitmap
import com.albustech.orbit.data.db.TabDao
import com.albustech.orbit.data.db.TabRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Up to [MAX_TABS] tabs on one WebView. A background tab is not a live WebView: it's its URL,
 * title and a small snapshot on disk. Its reading position is in the positions table like any
 * page's, so switching back reopens the page where it was left.
 */
class TabManager(
    context: Context,
    private val dao: TabDao,
    private val scope: CoroutineScope,
    private val controller: BrowserController,
) {
    private val dir = File(context.filesDir, "tabs").apply { mkdirs() }

    var tabs by mutableStateOf<List<TabRecord>>(emptyList())
        private set
    var currentId by mutableLongStateOf(0L)
        private set

    val canAdd: Boolean get() = tabs.size < MAX_TABS

    init {
        scope.launch {
            val existing = dao.all()
            if (existing.isEmpty()) {
                val first = TabRecord(id = System.currentTimeMillis(), url = null, title = null, stateFile = null, snapshotFile = null, lastUsed = System.currentTimeMillis())
                dao.put(first)
                currentId = first.id
            } else {
                currentId = existing.first().id
            }
            dao.observeAll().collect { tabs = it }
        }
    }

    /** Keeps the current tab's record pointing at the page on screen. */
    fun onPageCommitted(url: String, title: String?) {
        val id = currentId.takeIf { it != 0L } ?: return
        scope.launch {
            val old = tabs.firstOrNull { it.id == id }
            dao.put(TabRecord(id, url, title, null, old?.snapshotFile, System.currentTimeMillis()))
        }
    }

    fun switchTo(id: Long) {
        if (id == currentId) {
            controller.resumePage()
            return
        }
        val target = tabs.firstOrNull { it.id == id } ?: return
        saveCurrent {
            currentId = id
            scope.launch { dao.put(target.copy(lastUsed = System.currentTimeMillis())) }
            controller.loadFresh(target.url)
        }
    }

    fun newTab(): Boolean {
        if (!canAdd) return false
        saveCurrent {
            val tab = TabRecord(id = System.currentTimeMillis(), url = null, title = null, stateFile = null, snapshotFile = null, lastUsed = System.currentTimeMillis())
            currentId = tab.id
            scope.launch { dao.put(tab) }
            controller.loadFresh(null)
        }
        return true
    }

    fun close(id: Long) {
        val tab = tabs.firstOrNull { it.id == id } ?: return
        scope.launch {
            dao.delete(id)
            tab.snapshotFile?.let { File(it).delete() }
        }
        if (id != currentId) return
        val next = tabs.filter { it.id != id }.maxByOrNull { it.lastUsed }
        if (next != null) {
            currentId = next.id
            controller.loadFresh(next.url)
        } else {
            newTabAfterClose()
        }
    }

    private fun newTabAfterClose() {
        val tab = TabRecord(id = System.currentTimeMillis(), url = null, title = null, stateFile = null, snapshotFile = null, lastUsed = System.currentTimeMillis())
        currentId = tab.id
        scope.launch { dao.put(tab) }
        controller.loadFresh(null)
    }

    fun snapshotFile(tab: TabRecord): File? = tab.snapshotFile?.let(::File)?.takeIf { it.exists() }

    /** Snapshot and record the current tab, then continue with [then]. */
    private fun saveCurrent(then: () -> Unit) {
        val id = currentId
        val url = controller.readerUrl ?: controller.url
        val title = controller.title
        val bitmap = if (controller.hasPage) capture() else null
        scope.launch {
            val file = bitmap?.let { bmp ->
                withContext(Dispatchers.IO) {
                    File(dir, "$id.jpg").also { f -> f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 70, it) } }
                }
            }
            bitmap?.recycle()
            val old = tabs.firstOrNull { it.id == id }
            dao.put(TabRecord(id, url ?: old?.url, title ?: old?.title, null, file?.absolutePath ?: old?.snapshotFile, System.currentTimeMillis() - 1))
        }
        then()
    }

    /** A third-size snapshot of the page, drawn on the main thread. */
    private fun capture(): Bitmap? {
        val view = controller.webView
        if (view.width == 0 || view.height == 0) return null
        val scale = SNAPSHOT_SCALE
        val bmp = createBitmap((view.width * scale).toInt(), (view.height * scale).toInt(), Bitmap.Config.RGB_565)
        val canvas = Canvas(bmp)
        canvas.scale(scale, scale)
        canvas.translate(-view.scrollX.toFloat(), -view.scrollY.toFloat())
        view.draw(canvas)
        return bmp
    }

    companion object {
        const val MAX_TABS = 3
        private const val SNAPSHOT_SCALE = 0.35f
    }
}
