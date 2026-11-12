package android.ezerphone.videoguard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * מסך הקוד — הכניסה היחידה לאפליקציה.
 *  - אין קוד עדיין: בחירת קוד + אימות.
 *  - יש קוד: הזנת קוד. נכון = פותח את המסך הראשי.
 *  - "change" (רק מתוך ההגדרות, כשהאפליקציה פתוחה): קוד חדש + אימות.
 */
class LockActivity : Activity() {
    private enum class Step { NEW, CONFIRM, ENTER }

    private var step = Step.ENTER
    private var keyH = 68
    private var changing = false
    private var first = ""
    private val typed = StringBuilder()
    private lateinit var lock: View
    private lateinit var title: TextView
    private lateinit var sub: TextView
    private lateinit var dots: LinearLayout
    private lateinit var action: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // שינוי קוד רק כשהאפליקציה כבר פתוחה; מבחוץ אי אפשר לבקש אותו
        changing = intent.getBooleanExtra("change", false) && Lock.unlocked
        step = if (changing || !Prefs.hasPin(this)) Step.NEW else Step.ENTER

        // גדלים לפי גובה המסך: במסך נמוך (קטן, לרוחב, גופן מוגדל) הכול מתכווץ, ומעבר לזה נגלל
        val h = resources.configuration.screenHeightDp
        keyH = ((h - 330) / 4 - 12).coerceIn(48, 68)
        val root = St.col(this)
        root.gravity = Gravity.CENTER_HORIZONTAL
        root.setPadding(0, (h * 0.08).toInt().coerceIn(16, 72).let { St.dp(this, it) }, 0, St.dp(this, if (h < 600) 16 else 40))
        lock = St.iconBox(this, R.drawable.ic_lock, bg = St.BG, box = if (h < 600) 40 else 56, size = if (h < 600) 32 else 48)
        root.addView(lock)
        title = St.text(this, "", 24f, St.TEXT, true).apply { gravity = Gravity.CENTER; setPadding(0, St.dp(this@LockActivity, 18), 0, St.dp(this@LockActivity, 8)) }
        sub = St.text(this, "", 16f, St.MUTED).apply { gravity = Gravity.CENTER; setPadding(St.dp(this@LockActivity, 32), 0, St.dp(this@LockActivity, 32), 0) }
        root.addView(title); root.addView(sub)
        dots = St.row(this).apply { gravity = Gravity.CENTER; setPadding(0, St.dp(this@LockActivity, 26), 0, 0) }
        root.addView(dots)
        root.addView(View(this), St.lp(h = St.dp(this, 16), weight = 1f))
        root.addView(keypad(), LinearLayout.LayoutParams(
            minOf(resources.displayMetrics.widthPixels, St.dp(this, 420)), LinearLayout.LayoutParams.WRAP_CONTENT))
        setContentView(St.scroll(this, root))
        render()
    }

    private fun keypad(): LinearLayout {
        val pad = St.col(this)
        pad.layoutDirection = View.LAYOUT_DIRECTION_LTR
        pad.setPadding(St.dp(this, 36), 0, St.dp(this, 36), 0)
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "ok")
        for (r in 0 until 4) {
            val row = St.row(this)
            for (k in keys.subList(r * 3, r * 3 + 3)) {
                val v: TextView = when (k) {
                    "⌫" -> St.button(this, "⌫", 't', color = St.TEXT) { if (typed.isNotEmpty()) { typed.setLength(typed.length - 1); render() } }
                    "ok" -> St.button(this, "", 't') { submit() }.also { action = it }
                    else -> St.button(this, k, 'o', color = St.TEXT) { if (typed.length < 8) { typed.append(k); render() } }
                        .apply { textSize = if (keyH < 60) 24f else 28f; typeface = android.graphics.Typeface.DEFAULT; background = St.round(this@LockActivity, St.SURFACE, 34) .let { android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22000000), it, St.round(this@LockActivity, 0xFF000000.toInt(), 34)) } }
                }
                if (k == "⌫") v.textSize = 26f
                row.addView(v, LinearLayout.LayoutParams(0, St.dp(this, keyH), 1f).apply {
                    setMargins(St.dp(this@LockActivity, 6), St.dp(this@LockActivity, 6), St.dp(this@LockActivity, 6), St.dp(this@LockActivity, 6))
                })
            }
            pad.addView(row, St.lp())
        }
        return pad
    }

    private fun render(error: String? = null) {
        title.text = when (step) {
            Step.NEW -> if (changing) "בחר קוד חדש" else "בחר קוד"
            Step.CONFIRM -> "הזן שוב את הקוד"
            Step.ENTER -> "חוסם וידאו"
        }
        sub.text = error ?: when (step) {
            Step.NEW -> "4 עד 8 ספרות.\nהקוד יידרש בכל פתיחה של האפליקציה."
            Step.CONFIRM -> "כדי לוודא שלא הייתה טעות"
            Step.ENTER -> "הזן קוד"
        }
        sub.setTextColor(if (error != null) St.BAD else St.MUTED)
        (lock as ViewGroup).getChildAt(0).let { (it as android.widget.ImageView).imageTintList =
            android.content.res.ColorStateList.valueOf(if (error != null) St.BAD else St.PRIMARY) }
        action.text = if (step == Step.ENTER) "כניסה" else "המשך"
        action.alpha = if (typed.length >= 4) 1f else 0.35f
        dots.removeAllViews()
        for (i in 0 until maxOf(4, typed.length)) {
            val filled = i < typed.length
            dots.addView(View(this).apply {
                background = St.round(this@LockActivity, if (filled) St.PRIMARY else St.BG, 8).apply {
                    setStroke(St.dp(this@LockActivity, 2), St.PRIMARY)
                }
            }, LinearLayout.LayoutParams(St.dp(this, 16), St.dp(this, 16)).apply {
                setMargins(St.dp(this@LockActivity, 8), 0, St.dp(this@LockActivity, 8), 0)
            })
        }
    }

    private fun submit() {
        val code = typed.toString()
        if (code.length < 4) return
        typed.setLength(0)
        when (step) {
            Step.NEW -> { first = code; step = Step.CONFIRM; render() }
            Step.CONFIRM -> {
                if (code != first) { step = Step.NEW; render("הקודים לא תואמים. נסה שוב."); return }
                Prefs.setPin(this, code)
                if (changing) {
                    Toast.makeText(this, "הקוד שונה", Toast.LENGTH_SHORT).show()
                    finish()
                } else openApp()
            }
            Step.ENTER -> {
                if (Prefs.checkPin(this, code)) openApp() else render("קוד שגוי")
            }
        }
    }

    private fun openApp() {
        Lock.open()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
