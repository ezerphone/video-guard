package android.ezerphone.videoguard

import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * לוכד את המסך ברזולוציה זעירה דרך MediaProjection ומחזיר ישירות רשת בהירות
 * לזיהוי — בלי Bitmap, בלי העתקות כפולות ובלי זבל לזיכרון (המאגר ממוחזר).
 *
 * חיסכון:
 *  • הלכידה היא GRID_W×SUB פיקסלים ברוחב (160), לא רבע מסך — זה כל מה שהמנוע צריך.
 *  • pause() מנתק את המשטח מהתצוגה הווירטואלית, ואז המערכת מפסיקה לצייר אליה
 *    לגמרי (במסך הבית, באפליקציות פטורות, כשמוצגת חסימה, במסך נעול).
 *  • שורת המצב וסרגל הניווט נחתכים — שעון/התראות לא נחשבים "תנועה".
 */
class ScreenGrabber(
    projection: MediaProjection,
    screenW: Int, screenH: Int, dpi: Int,
    cropTopPx: Int, cropBottomPx: Int
) {
    val gridW = MotionDetector.GRID_W
    private val fullH = max(16, (gridW * screenH / screenW.toFloat()).roundToInt())
    private val top: Int
    val gridH: Int

    init {
        var t = ceil(cropTopPx * fullH / screenH.toFloat()).toInt()
        var b = ceil(cropBottomPx * fullH / screenH.toFloat()).toInt()
        if (fullH - t - b < 8) { t = 0; b = 0 }
        top = t
        gridH = fullH - t - b
    }

    private val capW = gridW * SUB
    private val capH = fullH * SUB
    private val reader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
    private val display: VirtualDisplay? = projection.createVirtualDisplay(
        "videoguard", capW, capH, max(1, dpi * capW / screenW),
        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, null
    )
    private var bytes: ByteArray? = null
    private var paused = false

    /** עוצר את הציור לתצוגה הווירטואלית (כמעט אפס צריכה). */
    @Synchronized
    fun pause() {
        if (paused) return
        paused = true
        try { display?.surface = null } catch (_: Exception) {}
        try { reader.acquireLatestImage()?.close() } catch (_: Exception) {}
    }

    /** מחזיר true אם הלכידה הייתה מושהית (הפריים הקודם כבר לא רלוונטי). */
    @Synchronized
    fun resume(): Boolean {
        if (!paused) return false
        paused = false
        try { display?.surface = reader.surface } catch (_: Exception) {}
        return true
    }

    /** רשת בהירות gridW×gridH של הפריים החדש, או null אם אין פריים חדש מאז הקריאה הקודמת. */
    @Synchronized
    fun grabLuma(): IntArray? {
        if (paused) return null
        val img = try { reader.acquireLatestImage() } catch (_: Exception) { null } ?: return null
        try {
            val plane = img.planes[0]
            val rs = plane.rowStride
            val ps = plane.pixelStride
            val buf = plane.buffer
            buf.rewind()
            val n = buf.remaining()
            var b = bytes
            if (b == null || b.size < n) { b = ByteArray(n); bytes = b }
            buf.get(b, 0, n)

            val out = IntArray(gridW * gridH)
            var i = 0
            for (gy in top until top + gridH) {
                for (gx in 0 until gridW) {
                    var s = 0
                    for (dy in 0 until SUB) {
                        val row = (gy * SUB + dy) * rs + gx * SUB * ps
                        for (dx in 0 until SUB) {
                            val o = row + dx * ps
                            if (o + 2 < n) {
                                s += ((b[o].toInt() and 255) * 77 +
                                    (b[o + 1].toInt() and 255) * 150 +
                                    (b[o + 2].toInt() and 255) * 29) shr 8
                            }
                        }
                    }
                    out[i++] = s / (SUB * SUB)
                }
            }
            return out
        } catch (_: Exception) {
            return null
        } finally {
            img.close()
        }
    }

    @Synchronized
    fun release() {
        try { display?.release() } catch (_: Exception) {}
        try { reader.close() } catch (_: Exception) {}
        bytes = null
    }

    private companion object {
        const val SUB = 4 // כל תא ברשת = ממוצע של 4×4 פיקסלים מהלכידה
    }
}
