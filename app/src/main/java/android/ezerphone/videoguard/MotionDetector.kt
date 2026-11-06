package android.ezerphone.videoguard

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * מזהה "תנועה אוטומטית" שמאפיינת וידאו: אזור גדול ורציף במסך שמשתנה שוב ושוב
 * בין פריימים עוקבים, ובאותו מקום.
 *
 * מקבל רשת בהירות קטנה (ש-ScreenGrabber מחשב ישירות מהלכידה, בלי Bitmap);
 * משווה לפריים הקודם ומחפש אזור שמשתנה בצפיפות גבוהה. רק אם זה קורה ברצף של
 * פריימים ובאותו אזור – זה וידאו. בסצנות כהות הסף יורד, כדי לתפוס גם וידאו חשוך.
 *
 * גלילה/החלקה: בלי נגישות אין אירועי גלילה, אז מזהים אותה מהתמונה — אם הפריים
 * החדש הוא בערך הפריים הקודם מוזז אנכית/אופקית, זו גלילה של המשתמש ולא וידאו.
 * במקרה כזה הזיהוי מושתק לרגע (כמו QUIET_MS ב-VideoStop).
 *
 * מנוע הזיהוי מבוסס על VideoStop (hershaleh/video-stop).
 */
class MotionDetector {

    private class Region(val l: Int, val t: Int, val r: Int, val b: Int) {
        val area get() = (r - l + 1) * (b - t + 1)

        fun iou(o: Region): Float {
            val il = max(l, o.l); val it = max(t, o.t)
            val ir = min(r, o.r); val ib = min(b, o.b)
            if (ir < il || ib < it) return 0f
            val inter = (ir - il + 1) * (ib - it + 1)
            return inter.toFloat() / (area + o.area - inter)
        }
    }

    private var prev: IntArray? = null
    private val history = ArrayDeque<Region?>()
    private var quiet = 0

    /** תיאור קצר של ההחלטה האחרונה — ללוג אבחון. */
    var lastInfo = ""
        private set

    fun reset() {
        prev = null
        history.clear()
        quiet = 0
    }

    /** אין פריים חדש (המסך לא השתנה) = פריים בלי תנועה. */
    fun idle() {
        lastInfo = "no-frame"
        if (prev == null) return
        if (quiet > 0) quiet--
        push(null)
    }

    /**
     * מקבל רשת בהירות w×h (0..255). לא שומר עותק — הרשת נשמרת כפריים הקודם. מחזיר true כשזוהה וידאו.
     * [touchRecent] — המשתמש נגע במסך לאחרונה. רק אז שינוי שנראה כמו הזזה נחשב גלילה;
     * בלי נגיעה, תזוזת מצלמה בסרטון היא וידאו ולא גלילה.
     */
    fun analyze(luma: IntArray, w: Int, h: Int, touchRecent: Boolean): Boolean {
        if (w < 8 || h < 8 || luma.size != w * h) return false

        var sum = 0L
        for (v in luma) sum += v
        val threshold = if (sum / luma.size < DARK_MEAN) DARK_DIFF else DIFF_THRESHOLD

        val old = prev
        prev = luma
        if (old == null || old.size != luma.size) {
            history.clear()
            lastInfo = "first"
            return false
        }

        if (touchRecent && isScroll(luma, old, w, h, threshold)) {
            quiet = QUIET_FRAMES
            push(null)
            lastInfo = "scroll"
            return false
        }
        if (quiet > 0) {
            quiet--
            push(null)
            lastInfo = "quiet"
            return false
        }

        val changed = BooleanArray(luma.size)
        val rows = IntArray(h)
        val cols = IntArray(w)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (abs(luma[i] - old[i]) > threshold) {
                changed[i] = true
                rows[y]++
                cols[x]++
            }
        }

        val region = boundingRegion(rows, cols)?.takeIf { r ->
            if (r.area.toFloat() / (w * h) < MIN_AREA) return@takeIf false
            var inside = 0
            for (y in r.t..r.b) for (x in r.l..r.r) if (changed[y * w + x]) inside++
            inside.toFloat() / r.area >= MIN_DENSITY
        }

        push(region)
        val regions = history.filterNotNull()
        lastInfo = (if (region == null) "none" else "region") + " hits=${regions.size}/${history.size}"
        if (history.size < WINDOW) return false
        if (regions.size < MIN_HITS) return false
        val ref = regions.last()
        if (regions.any { it.iou(ref) < MIN_IOU }) { lastInfo += " iou-miss"; return false }

        history.clear()
        lastInfo = "VIDEO"
        return true
    }

    private fun push(r: Region?) {
        history.addLast(r)
        if (history.size > WINDOW) history.removeFirst()
    }

    private fun boundingRegion(rows: IntArray, cols: IntArray): Region? {
        val t = rows.indexOfFirst { it >= MIN_LINE }
        val b = rows.indexOfLast { it >= MIN_LINE }
        val l = cols.indexOfFirst { it >= MIN_LINE }
        val r = cols.indexOfLast { it >= MIN_LINE }
        if (t < 0 || l < 0 || b < t || r < l) return null
        return Region(l, t, r, b)
    }

    // ───────────────────────── זיהוי גלילה ─────────────────────────

    /**
     * גלילה = הפריים החדש מתאים לפריים הקודם מוזז. בודקים רק שורות שהשתנו (כך כותרת
     * או סרגל קבועים לא מפריעים), ומשווים את השינוי בלי הזזה לשינוי בהזזה הטובה ביותר.
     */
    private fun isScroll(cur: IntArray, old: IntArray, w: Int, h: Int, threshold: Int): Boolean {
        val rowOn = BooleanArray(h)
        var active = 0
        var changedCells = 0
        for (y in 0 until h) {
            var s = 0
            val o = y * w
            for (x in 0 until w) {
                val d = abs(cur[o + x] - old[o + x])
                s += d
                if (d > threshold) changedCells++
            }
            if (s > w * threshold / 2) { rowOn[y] = true; active++ }
        }
        if (active < MIN_SCROLL_ROWS) return false
        // גלילה מזיזה את רוב המסך. דמות שזזה על רקע קבוע (וידאו "ראש מדבר") — לא גלילה.
        if (changedCells < w * h * MIN_SCROLL_CHANGED) return false

        val base = shiftDiff(cur, old, w, h, rowOn, active, 0, 0)
        if (base < threshold) return false

        var best = Float.MAX_VALUE
        val maxV = (h * 0.6f).toInt()
        for (d in 1..maxV) {
            best = min(best, shiftDiff(cur, old, w, h, rowOn, active, 0, d))
            best = min(best, shiftDiff(cur, old, w, h, rowOn, active, 0, -d))
        }
        val maxH = (w * 0.5f).toInt()
        for (d in 1..maxH) {
            best = min(best, shiftDiff(cur, old, w, h, rowOn, active, d, 0))
            best = min(best, shiftDiff(cur, old, w, h, rowOn, active, -d, 0))
        }
        return best < base * SCROLL_RATIO
    }

    /** הפרש ממוצע בין cur(x,y) ל-old(x+dx, y+dy), רק בשורות הפעילות. */
    private fun shiftDiff(
        cur: IntArray, old: IntArray, w: Int, h: Int,
        rowOn: BooleanArray, active: Int, dx: Int, dy: Int
    ): Float {
        val x0 = max(0, -dx); val x1 = min(w, w - dx)
        var s = 0L
        var n = 0
        for (y in 0 until h) {
            if (!rowOn[y]) continue
            val oy = y + dy
            if (oy < 0 || oy >= h) continue
            val rc = y * w
            val ro = oy * w + dx
            for (x in x0 until x1) s += abs(cur[rc + x] - old[ro + x])
            n += x1 - x0
        }
        // נדרשת חפיפה מספקת, אחרת הזזה גדולה "מנצחת" רק כי נשאר מעט להשוות
        if (n < active * w * MIN_OVERLAP) return Float.MAX_VALUE
        return s.toFloat() / n
    }

    companion object {
        const val GRID_W = 40                  // רוחב הרשת
        private const val DIFF_THRESHOLD = 12  // שינוי בהירות שנחשב תנועה
        private const val DARK_DIFF = 5        // סף מופחת לסצנות כהות
        private const val DARK_MEAN = 45       // בהירות ממוצעת מתחתיה הסצנה נחשבת כהה
        private const val MIN_LINE = 3         // מינימום תאים משתנים בשורה/עמודה
        private const val MIN_AREA = 0.08f     // האזור צריך לכסות לפחות 8% מהחלון
        private const val MIN_DENSITY = 0.25f  // וחלק גדול ממנו חייב להשתנות
        private const val WINDOW = 6           // בודקים את 6 הפריימים האחרונים
        private const val MIN_HITS = 5         // ב-5 מהם צריכה להיות תנועה
        private const val MIN_IOU = 0.4f       // ובאותו אזור פחות או יותר

        private const val MIN_SCROLL_ROWS = 4  // פחות שורות שהשתנו — לא בודקים גלילה
        private const val MIN_SCROLL_CHANGED = 0.35f // גלילה משנה לפחות 35% מהתאים
        private const val SCROLL_RATIO = 0.5f  // הזזה שמקטינה את השינוי לפחות מחצי = גלילה
        private const val MIN_OVERLAP = 0.35f  // חפיפה מינימלית בהשוואה מוזזת
        private const val QUIET_FRAMES = 3     // ~1 שנייה של השתקה אחרי גלילה
    }
}
