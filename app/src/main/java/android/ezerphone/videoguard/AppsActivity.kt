package android.ezerphone.videoguard

import android.app.AlertDialog
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView

/**
 * אפליקציות מושעות (עם הסיבה) ואפליקציות פטורות. שחרור מוסיף לפטורות, כדי שהסריקה הבאה לא תשעה שוב.
 * ביטול פטור סורק את האפליקציה מחדש.
 */
class AppsActivity : GuardedActivity() {
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = Kit.page(this, "אפליקציות")
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
    }

    override fun onShown() = render()

    private fun render() {
        list.removeAllViews()
        val suspended = Prefs.suspended(this).map { it to Scanner.label(this, it) }.sortedBy { it.second }
        list.addView(Kit.section(this, "מושעות (${suspended.size})"))
        if (suspended.isEmpty()) list.addView(Kit.note(this, "אין"))
        for ((p, label) in suspended) {
            list.addView(row(label, p, Prefs.reason(this, p), "שחרר ופטור") {
                ask("לשחרר את $label ולפטור אותה מסריקה?") {
                    Prefs.setExempt(this, p, true)
                    work { Scanner.unsuspend(applicationContext, listOf(p)) }
                }
            })
        }
        val exempt = Prefs.exempt(this).map { it to Scanner.label(this, it) }.sortedBy { it.second }
        list.addView(Kit.section(this, "פטורות (${exempt.size})"))
        if (exempt.isEmpty()) list.addView(Kit.note(this, "אין"))
        for ((p, label) in exempt) {
            list.addView(row(label, p, null, "בטל פטור") {
                ask("לבטל את הפטור של $label? היא תיסרק מיד, ותושעה אם היא פותחת וידאו.") {
                    Prefs.setExempt(this, p, false)
                    work { Scanner.scanOne(applicationContext, p) }
                }
            })
        }
    }

    private fun row(label: String, pkg: String, why: String?, action: String, f: () -> Unit): LinearLayout {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, Kit.dp(this@AppsActivity, 8), 0, Kit.dp(this@AppsActivity, 8))
        }
        r.addView(TextView(this).apply { text = label; textSize = 16f })
        r.addView(Kit.note(this, pkg + (if (why != null) "\n$why" else "")))
        r.addView(Kit.button(this, action, f))
        return r
    }

    private fun ask(msg: String, f: () -> Unit) {
        AlertDialog.Builder(this).setMessage(msg)
            .setPositiveButton("אשר") { _, _ -> f() }
            .setNegativeButton("ביטול", null)
            .show()
    }

    private fun work(f: () -> Unit) = Root.bg("vg-apps") {
        try { f() } catch (t: Throwable) { VgLog.add("שגיאה: ${t.message}") }
        runOnUiThread { if (!isFinishing) render() }
    }
}
