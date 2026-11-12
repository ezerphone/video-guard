package android.ezerphone.videoguard

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** עיצוב משותף לכל המסכים (בלי קבצי layout). צבעים לפי ההדמיות. */
object St {
    const val BG = 0xFFF4F6F8.toInt()
    const val SURFACE = 0xFFFFFFFF.toInt()
    const val TEXT = 0xFF1A1F24.toInt()
    const val MUTED = 0xFF5D6670.toInt()
    const val LINE = 0xFFE3E7EB.toInt()
    const val PRIMARY = 0xFF1F5F8B.toInt()
    const val PRIMARY_SOFT = 0xFFE3EEF6.toInt()
    const val OK = 0xFF1D7F4A.toInt()
    const val OK_SOFT = 0xFFE2F3E9.toInt()
    const val OFF = 0xFF8A939C.toInt()
    const val OFF_SOFT = 0xFFECEFF2.toInt()
    const val BAD = 0xFFB3261E.toInt()
    const val BAD_SOFT = 0xFFFBE9E7.toInt()

    val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun dp(c: Context, v: Number) = (v.toFloat() * c.resources.displayMetrics.density).toInt()

    fun round(c: Context, color: Int, radiusDp: Number, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color); cornerRadius = dp(c, radiusDp).toFloat()
            if (stroke != null) setStroke(dp(c, 1), stroke)
        }

    private fun ripple(bg: Drawable?, mask: Drawable) = RippleDrawable(ColorStateList.valueOf(0x22000000), bg, mask)

    fun text(c: Context, t: CharSequence, sizeSp: Float, color: Int = TEXT, medium: Boolean = false) = TextView(c).apply {
        text = t; textSize = sizeSp; setTextColor(color)
        if (medium) typeface = MEDIUM
        setLineSpacing(0f, 1.15f)
    }

    fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight)

    fun col(c: Context) = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    fun row(c: Context) = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    /** כפתור: p = מלא, o = מתאר, t = טקסט. small = נמוך וצר. */
    fun button(c: Context, t: String, kind: Char = 'p', small: Boolean = false, color: Int = PRIMARY, f: () -> Unit) =
        TextView(c).apply {
            text = t; gravity = Gravity.CENTER; typeface = MEDIUM
            textSize = if (small) 14f else 16f
            val h = if (small) 38 else 48
            minHeight = dp(c, h); minimumHeight = dp(c, h)
            setPadding(dp(c, if (small) 16 else 22), 0, dp(c, if (small) 16 else 22), 0)
            val shape = when (kind) {
                'p' -> round(c, color, 24)
                'o' -> round(c, SURFACE, 24, LINE)
                else -> null
            }
            background = ripple(shape, round(c, Color.BLACK, 24))
            setTextColor(if (kind == 'p') Color.WHITE else color)
            isClickable = true
            setOnClickListener { f() }
        }

    fun chip(c: Context, t: String, fg: Int, bg: Int) = text(c, t, 13f, fg, true).apply {
        background = round(c, bg, 12)
        setPadding(dp(c, 12), dp(c, 5), dp(c, 12), dp(c, 5))
    }

    fun icon(c: Context, res: Int, sizeDp: Int = 24, tint: Int? = null) = ImageView(c).apply {
        setImageResource(res)
        if (tint != null) imageTintList = ColorStateList.valueOf(tint)
        layoutParams = LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp))
    }

    /** ריבוע מעוגל עם אייקון במרכז. */
    fun iconBox(c: Context, res: Int, bg: Int = PRIMARY_SOFT, box: Int = 44, size: Int = 24, radius: Int = 14, tint: Int? = null) =
        FrameLayout(c).apply {
            background = round(c, bg, radius)
            addView(icon(c, res, size, tint), FrameLayout.LayoutParams(dp(c, size), dp(c, size), Gravity.CENTER))
            layoutParams = LinearLayout.LayoutParams(dp(c, box), dp(c, box))
        }

    /** קבוצת שורות על רקע לבן מעוגל. */
    fun list(c: Context) = col(c).apply { background = round(c, SURFACE, 20); clipToOutline = true }

    /** שורה ברשימה: כותרת, תיאור (אופציונלי), ורכיב בצד. */
    fun listRow(c: Context, title: String, sub: String? = null, titleColor: Int = TEXT, lead: View? = null,
                end: View? = null, onClick: (() -> Unit)? = null): LinearLayout {
        val r = row(c)
        r.setPadding(dp(c, 18), dp(c, 14), dp(c, 18), dp(c, 14))
        r.minimumHeight = dp(c, 56)
        if (lead != null) { r.addView(lead); r.addView(View(c), lp(dp(c, 14), 1)) }
        val t = col(c)
        t.addView(text(c, title, 16f, titleColor))
        if (sub != null) t.addView(text(c, sub, 13f, MUTED))
        r.addView(t, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (end != null) { r.addView(View(c), lp(dp(c, 10), 1)); r.addView(end) }
        if (onClick != null) {
            r.background = ripple(null, ColorDrawable(Color.WHITE))
            r.setOnClickListener { onClick() }
        }
        return r
    }

    fun divider(c: Context) = View(c).apply { setBackgroundColor(LINE); layoutParams = lp(h = dp(c, 1)) }

    fun addRows(list: LinearLayout, rows: List<View>) {
        rows.forEachIndexed { i, v -> if (i > 0) list.addView(divider(list.context)); list.addView(v) }
    }

    fun group(c: Context, t: String) = text(c, t, 13f, PRIMARY, true).apply { setPadding(dp(c, 6), dp(c, 12), dp(c, 6), dp(c, 6)) }

    @Suppress("UseSwitchCompatOrMaterialCode")
    fun toggle(c: Context, on: Boolean, f: (Boolean) -> Unit) = Switch(c).apply {
        isChecked = on
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        thumbTintList = ColorStateList(states, intArrayOf(Color.WHITE, OFF))
        trackTintList = ColorStateList(states, intArrayOf(PRIMARY, OFF_SOFT))
        setOnCheckedChangeListener { _, v -> f(v) }
    }

    /** מסך עם פס עליון (כותרת, חץ חזרה, כפתור הגדרות) ותוכן נגלל. מחזיר את עמודת התוכן. */
    fun screen(a: Activity, title: String, back: Boolean = false, gear: (() -> Unit)? = null): LinearLayout {
        val root = col(a)
        val top = row(a)
        top.setPadding(dp(a, 8), 0, dp(a, 8), 0)
        top.minimumHeight = dp(a, 60)
        if (back) top.addView(touch(a, R.drawable.ic_back) { a.finish() })
        else top.addView(View(a), lp(dp(a, 12), 1))
        top.addView(text(a, title, 22f, TEXT, true), lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (gear != null) top.addView(touch(a, R.drawable.ic_gear, gear))
        root.addView(top)
        val body = col(a)
        body.setPadding(dp(a, 16), dp(a, 4), dp(a, 16), dp(a, 24))
        root.addView(scroll(a, body), lp(h = 0, weight = 1f))
        a.setContentView(root)
        return body
    }

    /** רוחב מקסימלי לתוכן (טאבלט, מסך לרוחב): התוכן ממורכז ולא נמתח לכל הרוחב. */
    const val MAX_WIDTH_DP = 560

    /**
     * עוטף תוכן בגלילה שממלאת את המסך: במסך קטן, בתצוגה לרוחב או עם גופן מוגדל שום דבר לא נחתך,
     * ובמסך רחב התוכן ממורכז ברוחב של עד [MAX_WIDTH_DP].
     */
    fun scroll(c: Context, content: View): ScrollView {
        val wide = c.resources.configuration.screenWidthDp > MAX_WIDTH_DP
        val frame = FrameLayout(c)
        frame.addView(content, FrameLayout.LayoutParams(
            if (wide) dp(c, MAX_WIDTH_DP) else ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL))
        return ScrollView(c).apply { isFillViewport = true; addView(frame) }
    }

    private fun touch(c: Context, res: Int, f: () -> Unit) = FrameLayout(c).apply {
        addView(icon(c, res), FrameLayout.LayoutParams(dp(c, 24), dp(c, 24), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(dp(c, 48), dp(c, 48))
        background = RippleDrawable(ColorStateList.valueOf(0x22000000), null, null)
        isClickable = true
        setOnClickListener { f() }
    }

    fun gap(c: Context, h: Int) = View(c).apply { layoutParams = lp(h = dp(c, h)) }

    /** חלון אישור שעולה מלמטה: כותרת, הסבר, כפתור ראשי ו"ביטול". */
    fun sheet(a: Activity, title: String, body: String, action: String, danger: Boolean = false, f: () -> Unit) {
        val d = Dialog(a)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val v = col(a)
        v.background = GradientDrawable().apply {
            setColor(SURFACE)
            val r = dp(a, 28).toFloat()
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        v.setPadding(dp(a, 24), dp(a, 14), dp(a, 24), dp(a, 24))
        v.addView(View(a).apply { background = round(a, LINE, 2) }, LinearLayout.LayoutParams(dp(a, 36), dp(a, 4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(a, 18)
        })
        v.addView(text(a, title, 22f, TEXT, true))
        v.addView(text(a, body, 15f, MUTED).apply { setPadding(0, dp(a, 8), 0, dp(a, 22)) })
        v.addView(button(a, action, 'p', color = if (danger) BAD else PRIMARY) { d.dismiss(); f() })
        v.addView(button(a, "ביטול", 't') { d.dismiss() })
        v.layoutDirection = View.LAYOUT_DIRECTION_RTL
        // גלילה: במסך נמוך (לרוחב) החלון לא נחתך
        d.setContentView(ScrollView(a).apply { addView(v) })
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val wide = a.resources.configuration.screenWidthDp > MAX_WIDTH_DP
            setLayout(if (wide) dp(a, MAX_WIDTH_DP) else ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
        d.show()
    }
}
