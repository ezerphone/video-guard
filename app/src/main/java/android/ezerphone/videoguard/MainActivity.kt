package android.ezerphone.videoguard

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** רכיבי מסך משותפים (בלי קבצי layout). */
object Kit {
    fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    fun page(a: Activity, title: String): LinearLayout {
        val root = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(a, 16), dp(a, 16), dp(a, 16), dp(a, 24))
        }
        root.addView(TextView(a).apply { text = title; textSize = 24f })
        a.setContentView(ScrollView(a).apply { addView(root) })
        return root
    }

    fun section(c: Context, t: String) = TextView(c).apply {
        text = t; textSize = 18f; setPadding(0, dp(c, 20), 0, dp(c, 4))
    }

    fun note(c: Context, t: String) = TextView(c).apply { text = t; textSize = 13f; alpha = 0.75f }

    fun button(c: Context, t: String, f: () -> Unit) = Button(c).apply { text = t; setOnClickListener { f() } }

    @Suppress("UseSwitchCompatOrMaterialCode")
    fun switch(c: Context, t: String, on: Boolean, f: (Boolean) -> Unit) = Switch(c).apply {
        text = t; isChecked = on; textSize = 15f
        setPadding(0, dp(c, 6), 0, dp(c, 6))
        setOnCheckedChangeListener { _, v -> f(v) }
    }

    fun pinField(c: Context, hint: String) = EditText(c).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }
}

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var protectBtn: Button
    private lateinit var serviceSw: Switch
    private var dialogOpen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = Kit.page(this, getString(R.string.app_name))
        status = TextView(this).apply { textSize = 15f; setPadding(0, Kit.dp(this@MainActivity, 8), 0, 0) }
        root.addView(status)

        root.addView(Kit.section(this, "מפענחי וידאו"))
        root.addView(Kit.note(this, "מוחק את מפענחי הווידאו מקבצי המערכת (גיבוי נשמר). נכנס לתוקף אחרי אתחול."))
        root.addView(Kit.button(this, "מחק מפענחי וידאו") { task("remove") })
        root.addView(Kit.button(this, "שחזר מפענחים") { task("restore") })

        root.addView(Kit.section(this, "סריקת אפליקציות"))
        root.addView(Kit.note(this, "אפליקציה שפותחת קבצי וידאו או מצרפת ספריית פענוח משלה — מושעית. אפליקציות מערכת לא נסרקות."))
        serviceSw = Kit.switch(this, "שירות סריקה בכל התקנה ועדכון", Prefs.serviceOn(this)) { on ->
            Prefs.setServiceOn(this, on)
            if (on) ScanService.start(applicationContext) else ScanService.stop(applicationContext)
            refresh()
        }
        root.addView(serviceSw)
        root.addView(Kit.button(this, "סרוק הכל עכשיו") { task("scan") })
        root.addView(Kit.button(this, "אפליקציות מושעות ופטורות") { startActivity(Intent(this, AppsActivity::class.java)) })

        root.addView(Kit.section(this, "הגנה"))
        root.addView(Kit.note(this, "Device Owner: חוסם את הסרת האפליקציה, וההשעיות לא ניתנות לביטול מבחוץ. דורש להסיר קודם את כל החשבונות מהמכשיר."))
        protectBtn = Kit.button(this, "") { task(if (Admin.isOwner(this)) "unprotect" else "protect") }
        root.addView(protectBtn)
        root.addView(lockSwitch("חסום מצב בטוח", Prefs.lockSafeBoot(this)) { Prefs.setLockSafeBoot(this, it) })
        root.addView(lockSwitch("חסום איפוס יצרן מההגדרות", Prefs.lockFactoryReset(this)) { Prefs.setLockFactoryReset(this, it) })
        root.addView(lockSwitch("חסום ADB ואפשרויות מפתח", Prefs.lockAdb(this)) { Prefs.setLockAdb(this, it) })
        root.addView(Kit.button(this, "שנה PIN") { setPin(mandatory = false) })

        root.addView(Kit.section(this, "יומן"))
        root.addView(Kit.button(this, "יומן פעולות") { startActivity(Intent(this, LogActivity::class.java)) })
    }

    private fun lockSwitch(t: String, on: Boolean, set: (Boolean) -> Unit) = Kit.switch(this, t, on) { v ->
        set(v)
        val app = applicationContext
        if (Admin.isOwner(app)) Root.bg { Admin.applyLocks(app) }
    }

    override fun onResume() {
        super.onResume()
        when {
            !Prefs.hasPin(this) -> setPin(mandatory = true)
            Lock.needed(this) -> askPin()
            else -> refresh()
        }
    }

    private fun task(mode: String) = startActivity(Intent(this, TaskActivity::class.java).putExtra("mode", mode))

    private fun refresh() {
        protectBtn.text = if (Admin.isOwner(this)) "הסר הגנה" else "הפעל הגנה"
        serviceSw.isChecked = Prefs.serviceOn(this)
        val app = applicationContext
        Root.bg("vg-status") {
            val dec = Codecs.visibleVideoDecoders()
            val lines = ArrayList<String>()
            lines += if (dec.isEmpty()) "✓ מפענחי וידאו שאפליקציות רואות: 0"
                else "מפענחי וידאו שאפליקציות רואות: ${dec.size}"
            lines += if (!Root.available()) "⚠ אין גישת root"
                else "מחיקת מפענחים: " + if (Codecs.applied()) "בוצעה" else "לא בוצעה"
            lines += "שירות סריקה: " + if (ScanService.running) "פועל" else if (Prefs.serviceOn(app)) "דלוק אבל לא רץ" else "כבוי"
            lines += "אפליקציות מושעות: ${Prefs.suspended(app).size}"
            lines += "הגנה: " + if (Admin.isOwner(app)) "פעילה (Device Owner)" else "כבויה"
            runOnUiThread { if (!isFinishing) status.text = lines.joinToString("\n") }
        }
    }

    private fun askPin() {
        if (dialogOpen) return
        dialogOpen = true
        val f = Kit.pinField(this, "PIN")
        AlertDialog.Builder(this)
            .setTitle("הזן PIN").setView(f).setCancelable(false)
            .setPositiveButton("אישור") { _, _ ->
                dialogOpen = false
                if (Prefs.checkPin(this, f.text.toString())) { Lock.unlocked = true; refresh() }
                else { Toast.makeText(this, "PIN שגוי", Toast.LENGTH_SHORT).show(); finish() }
            }
            .setNegativeButton("יציאה") { _, _ -> dialogOpen = false; finish() }
            .show()
    }

    private fun setPin(mandatory: Boolean) {
        if (dialogOpen) return
        dialogOpen = true
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(Kit.dp(this@MainActivity, 20), 0, Kit.dp(this@MainActivity, 20), 0) }
        val a = Kit.pinField(this, "PIN חדש (לפחות 4 ספרות)")
        val b = Kit.pinField(this, "שוב")
        box.addView(a); box.addView(b)
        AlertDialog.Builder(this)
            .setTitle(if (mandatory) "הגדרת PIN" else "שינוי PIN")
            .setMessage(if (mandatory) "ה-PIN יידרש בכל כניסה לאפליקציה. אם תשכח אותו, הדרך היחידה לאפס היא root (ראה README)." else null)
            .setView(box).setCancelable(false)
            .setPositiveButton("שמור") { _, _ ->
                dialogOpen = false
                val p = a.text.toString()
                if (p.length >= 4 && p == b.text.toString()) {
                    Prefs.setPin(this, p); Lock.unlocked = true
                    Toast.makeText(this, "ה-PIN נשמר", Toast.LENGTH_SHORT).show(); refresh()
                } else {
                    Toast.makeText(this, "ה-PIN לא תואם או קצר מ-4 ספרות", Toast.LENGTH_SHORT).show()
                    if (mandatory) setPin(true)
                }
            }
            .setNegativeButton(if (mandatory) "יציאה" else "ביטול") { _, _ -> dialogOpen = false; if (mandatory) finish() }
            .show()
    }
}

/** מסך-משנה מוגן: אם האפליקציה ננעלה בינתיים, נסגר וחוזר למסך הראשי (שמבקש PIN). */
abstract class GuardedActivity : Activity() {
    override fun onResume() {
        super.onResume()
        if (Lock.needed(this) && !keepWhenLocked()) { finish(); return }
        onShown()
    }
    protected open fun keepWhenLocked() = false
    protected open fun onShown() {}
}

class LogActivity : GuardedActivity() {
    private lateinit var tv: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = Kit.page(this, "יומן פעולות")
        val bar = LinearLayout(this)
        bar.addView(Kit.button(this, "רענן") { onShown() })
        bar.addView(Kit.button(this, "העתק") {
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            cm.setPrimaryClip(android.content.ClipData.newPlainText("VideoGuard log", VgLog.text()))
            Toast.makeText(this, "הועתק", Toast.LENGTH_SHORT).show()
        })
        bar.addView(Kit.button(this, "נקה") { VgLog.clear(); onShown() })
        root.addView(bar)
        tv = TextView(this).apply { textSize = 12f; setTextIsSelectable(true); textDirection = View.TEXT_DIRECTION_LOCALE }
        root.addView(tv)
    }

    override fun onShown() {
        tv.text = VgLog.text().ifEmpty { "(ריק)" }
    }
}
