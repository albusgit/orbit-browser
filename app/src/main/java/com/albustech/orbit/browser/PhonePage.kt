package com.albustech.orbit.browser

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.albustech.orbit.link.Box
import com.albustech.orbit.link.Tile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.TreeMap
import kotlin.math.roundToInt

/**
 * A page the phone rendered: picture strips at document positions, the link map, and where the
 * watch is looking. Scrolling and zooming only move [offsetY], [offsetX] and [zoom]; the strips
 * stay as sent. Strips are kept encoded (WebP, ~40 KB each) and only the ones near the screen
 * are decoded, so a long page stays small in memory. Main thread.
 */
class PhonePage(val rev: Int, val url: String, private val screenW: Int, private val screenH: Int) {

    private val strips = TreeMap<Int, Tile>()
    private val decoded = LruCache<Int, ImageBitmap>(DECODED)
    private val decoding = HashSet<Int>()

    /** Bumped when strips arrive or decode: the page redraws. */
    var version by mutableIntStateOf(0)
        private set

    var docWidth by mutableIntStateOf(0)
    var docHeight by mutableIntStateOf(0)
    var boxes by mutableStateOf<List<Box>>(emptyList())

    /** Document px at the top-left of the screen. */
    var offsetY by mutableFloatStateOf(0f)
        private set
    var offsetX by mutableFloatStateOf(0f)
        private set

    /** 1 = the page's width fills the screen. */
    var zoom by mutableFloatStateOf(1f)
        private set

    val hasStrips: Boolean get() = strips.isNotEmpty()

    private val stripWidth: Int get() = strips.firstEntry()?.value?.width ?: docWidth.takeIf { it > 0 } ?: screenW

    /** Screen px per document px. */
    val scale: Float get() = screenW.toFloat() / stripWidth * zoom

    private val maxY: Float get() = (maxOf(docHeight, lastStripEnd()) - screenH / scale).coerceAtLeast(0f)
    private val maxX: Float get() = (stripWidth - screenW / scale).coerceAtLeast(0f)

    val fraction: Float get() = if (maxY > 0f) (offsetY / maxY).coerceIn(0f, 1f) else 0f

    fun put(tile: Tile) {
        strips[tile.y] = tile
        decoded.remove(tile.y)
        version++
    }

    /** Takes [old]'s strips as placeholders (Back shows the page at once while it reloads). */
    fun prefill(old: PhonePage) {
        strips.putAll(old.strips)
        docWidth = old.docWidth
        docHeight = old.docHeight
        boxes = old.boxes
        offsetY = old.offsetY
        zoom = old.zoom
        version++
    }

    /** Scrolls by [dy] screen px; false if already at that end. */
    fun scrollBy(dy: Float): Boolean {
        val target = (offsetY + dy / scale).coerceIn(0f, maxY)
        if (target == offsetY) return false
        offsetY = target
        return true
    }

    fun panBy(dx: Float) {
        offsetX = (offsetX + dx / scale).coerceIn(0f, maxX)
    }

    fun scrollToDoc(y: Float) {
        offsetY = y.coerceIn(0f, maxY)
    }

    fun zoomBy(factor: Float) {
        val cx = offsetX + screenW / 2f / scale
        val cy = offsetY + screenH / 2f / scale
        zoom = (zoom * factor).coerceIn(1f, MAX_ZOOM)
        offsetX = (cx - screenW / 2f / scale).coerceIn(0f, maxX)
        offsetY = (cy - screenH / 2f / scale).coerceIn(0f, maxY)
    }

    /** Screen px → document px. */
    fun toDoc(x: Float, y: Float): Pair<Int, Int> = (offsetX + x / scale).roundToInt() to (offsetY + y / scale).roundToInt()

    /** The smallest link, button or field under document point ([x], [y]), with a finger's slop. */
    fun boxAt(x: Int, y: Int): Box? {
        val slop = (FINGER_SLOP_PX / scale).roundToInt()
        return boxes.filter { it.contains(x, y) }.minByOrNull { it.w * it.h }
            ?: boxes.filter { it.contains(x, y, slop) }.minByOrNull { it.w * it.h }
    }

    /** Strips on screen with their pictures if decoded (null: not yet), top to bottom. */
    fun visible(): List<Pair<Tile, ImageBitmap?>> {
        val top = offsetY
        val bottom = offsetY + screenH / scale
        val from = strips.floorKey(top.toInt()) ?: strips.firstKeyOrNull() ?: return emptyList()
        return strips.subMap(from, true, bottom.toInt(), true).values.map { it to decoded.get(it.y) }
    }

    /** Decodes the strips on and near the screen, off the main thread. */
    fun decodeAround(scope: CoroutineScope) {
        val screen = screenH / scale
        val from = strips.floorKey((offsetY - screen).toInt()) ?: strips.firstKeyOrNull() ?: return
        for (t in strips.subMap(from, true, (offsetY + 2 * screen).toInt(), true).values) {
            if (decoded.get(t.y) != null || !decoding.add(t.y)) continue
            scope.launch {
                val bmp = withContext(Dispatchers.Default) {
                    // RGB_565: half the memory, and strips are opaque.
                    val opts = BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.RGB_565 }
                    BitmapFactory.decodeByteArray(t.image, 0, t.image.size, opts)?.asImageBitmap()
                }
                decoding.remove(t.y)
                if (bmp != null && strips[t.y] === t) {
                    decoded.put(t.y, bmp)
                    version++
                }
            }
        }
    }

    private fun lastStripEnd(): Int = strips.lastEntry()?.value?.let { it.y + it.height } ?: 0

    private fun TreeMap<Int, Tile>.firstKeyOrNull(): Int? = if (isEmpty()) null else firstKey()

    private companion object {
        const val DECODED = 12
        const val MAX_ZOOM = 4f
        const val FINGER_SLOP_PX = 14f
    }
}
