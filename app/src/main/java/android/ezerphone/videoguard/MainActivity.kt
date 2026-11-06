package android.ezerphone.videoguard

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** המסך הראשי: מתגי ההפעלה, "הפעל חסימה מלאה", הגבלות, אפליקציות, אבחון ו"בטל הכל". מוגן ב-PIN. */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private var unlocked = false
    private var openingChild = false // פתיחת מסך-משנה שלנו לא נועלת מחדש

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        openingChild = false
        if (Prefs.hasPin(this) && !unlocked) { promptPin(); return }
        refresh()
    }

    override fun onStop() {
        super.onStop()
        if (!openingChild && !isChangingConfigurations) unlocked = false
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(28))
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.app_name); textSize = 26f; setTextColor(Color.BLACK)
        })
        status = TextView(this).apply { textSize = 14f; setPadding(0, dp(10), 0, dp(16)) }
        root.addView(status)

        // ---- הפעלה ----
        root.addView(sectionLabel("מה להפעיל"))
        root.addView(sw("הפעל זיהוי חזותי", Prefs.visualOn(this)) { Prefs.setVisualOn(this, it) })
        root.addView(sw("מחק רכיבי וידאו של המערכת", Prefs.codecsOn(this)) { Prefs.setCodecsOn(this, it) })
        root.addView(sw("סריקת אפליקציות בכל אתחול ובכל התקנה חדשה", Prefs.scanOn(this)) { Prefs.setScanOn(this, it) })
        root.addView(sw("חסום גם אפליקציות מערכת שמזוהה בהן וידאו", Prefs.blockSystem(this)) { Prefs.setBlockSystem(this, it) })
        root.addView(btn("הפעל חסימה מלאה") {
            if (!Prefs.hasPin(this)) askNewPin { launch("setup") } else launch("setup")
        })

        // ---- סריקה והשעיה ----
        root.addView(sectionLabel("סריקה והשעיה"))
        root.addView(sw("אכוף השעיה (כבוי = דיווח ליומן בלבד)", Prefs.enforce(this)) { on ->
            if (on) {
                AlertDialog.Builder(this)
                    .setMessage("להפעיל אכיפה? אפליקציות שנתפסו בסריקה יושעו. כדאי קודם לעבור על רשימת האפליקציות ולסמן \"מותרת\" מה שצריך.")
                    .setPositiveButton("הפעל") { _, _ ->
                        Prefs.setEnforce(this, true)
                        Thread({ Scanner.scanAndApply(applicationContext) }).start()
                    }
                    .setNegativeButton("בטל") { _, _ -> refreshAll() }
                    .show()
            } else {
                Prefs.setEnforce(this, false)
                Thread({ Scanner.releaseAll(applicationContext) }).start()
            }
        })
        root.addView(btn("אפליקציות (רגילה / מותרת / מושעית)") { child(AppListActivity::class.java) })
        root.addView(btn("סרוק עכשיו") {
            Thread({
                Scanner.scanAndApply(applicationContext)
                runOnUiThread { Toast.makeText(this, "נמצאו ${Scanner.last.size} — פירוט ביומן", Toast.LENGTH_LONG).show() }
            }).start()
        })

        // ---- הגבלות ----
        root.addView(sectionLabel("הגבלות (Device Owner)"))
        Prefs.RESTRICTIONS.forEach { r ->
            root.addView(sw(r.label, Prefs.restr(this, r)) { Prefs.setRestr(this, r, it) })
        }
        root.addView(sw("הסתר את אפליקציית Magisk (ליבת ה-root ממשיכה לפעול)", Prefs.hideMagisk(this)) { Prefs.setHideMagisk(this, it) })
        root.addView(btn("החל הגבלות עכשיו") {
            Thread({
                val ok = Admin.isOwner(applicationContext)
                if (ok) Admin.applyAll(applicationContext)
                runOnUiThread { Toast.makeText(this, if (ok) "הוחלו" else "האפליקציה אינה Device Owner", Toast.LENGTH_SHORT).show() }
            }).start()
        })

        // ---- אבחון ----
        root.addView(sectionLabel("אבחון"))
        root.addView(btn("הפעל זיהוי חזותי עכשיו") {
            Thread({ if (Root.available()) Guard.grantOps(applicationContext); Guard.startVisual(applicationContext) }).start()
        })
        root.addView(btn("בדוק מפענחי וידאו") {
            Thread({
                val r = Verify.run(applicationContext)
                runOnUiThread { AlertDialog.Builder(this).setMessage(r.text).setPositiveButton("סגור", null).show(); refresh() }
            }).start()
        })
        root.addView(btn("יומן פקודות") { child(LogActivity::class.java) })

        // ---- קוד ושחרור ----
        root.addView(sectionLabel("קוד הגנה ושחרור"))
        root.addView(btn("הגדרת / שינוי קוד") { askNewPin { refresh() } })
        root.addView(btn("בטל הכל") {
            if (Prefs.hasPin(this)) askPin { launch("undo") } else launch("undo")
        }.apply { setTextColor(Color.parseColor("#B00020")) })

        return ScrollView(this).apply { addView(root) }
    }

    private fun launch(mode: String) {
        openingChild = true
        startActivity(Intent(this, SetupActivity::class.java).putExtra("mode", mode))
    }

    private fun child(cls: Class<*>) {
        openingChild = true
        startActivity(Intent(this, cls))
    }

    private fun refreshAll() { recreate() }

    private fun refresh() {
        val run = CaptureService.running && CaptureService.hasProjection
        val issues = Integrity.check(this)
        status.text = buildString {
            append(if (run) "● זיהוי חזותי פעיל" else "○ זיהוי חזותי כבוי")
            append("\nDevice Owner: ").append(if (Admin.isOwner(this@MainActivity)) "כן" else "לא")
            val v = Prefs.lastVerify(this@MainActivity)
            if (v.isNotEmpty()) append("\nבדיקה אחרונה: ").append(v)
            if (issues.isNotEmpty()) {
                append("\n\n⚠ ההגנה לא שלמה, נדרשת הגדרה מחדש:\n")
                append(issues.joinToString("\n") { "• $it" })
            }
        }
        status.setTextColor(if (issues.isNotEmpty()) Color.parseColor("#B00020")
            else if (run) Color.parseColor("#1B873F") else Color.DKGRAY)
    }

    // ---- קוד ----

    private fun pinField(hint: String) = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        this.hint = hint
    }

    private fun askNewPin(done: () -> Unit) {
        val a = pinField(getString(R.string.pin_hint))
        val b = pinField("שוב, לאישור")
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0)
            addView(a); addView(b)
        }
        AlertDialog.Builder(this)
            .setTitle("הגדרת קוד הגנה")
            .setMessage("הקוד נדרש לכניסה להגדרות. אם תשכח אותו, \"בטל הכל\" לא יעבוד.")
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val p = a.text.toString()
                if (p.length >= 4 && p == b.text.toString()) { Prefs.setPin(this, p); unlocked = true; done() }
                else Toast.makeText(this, "הקוד קצר מ-4 ספרות או שלא תואם", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun askPin(onOk: () -> Unit) {
        val input = pinField("")
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.enter_pin)).setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (Prefs.verify(this, input.text.toString())) onOk()
                else Toast.makeText(this, "קוד שגוי", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun promptPin() {
        val input = pinField("")
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.enter_pin)).setView(input).setCancelable(false)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (Prefs.verify(this, input.text.toString())) { unlocked = true; refresh() } else finish()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .show()
    }

    // ---- עזרי UI ----

    private fun sw(label: String, checked: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = label; isChecked = checked; setPadding(0, dp(6), 0, dp(6))
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }

    private fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; setOnClickListener { onClick() }
    }

    private fun sectionLabel(t: String) = TextView(this).apply {
        text = t; textSize = 17f; setTextColor(Color.BLACK)
        setPadding(0, dp(24), 0, dp(8)); gravity = Gravity.START
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
