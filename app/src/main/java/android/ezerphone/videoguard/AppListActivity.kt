package android.ezerphone.videoguard

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView

/** מצב לכל אפליקציה: רגילה / מותרת (פטורה מהכול, ואפשר להשאיר זיהוי חזותי) / מושעית. */
class AppListActivity : Activity() {
    private lateinit var list: LinearLayout
    private var showSystem = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        root.addView(TextView(this).apply { text = "אפליקציות"; textSize = 20f })
        root.addView(TextView(this).apply {
            text = "רגילה: נסרקת ועוברת זיהוי חזותי. מותרת: לא עוברת שום בדיקה (אפשר לסמן \"זיהוי חזותי\"). מושעית: מושעית תמיד (דורש Device Owner)."
            textSize = 12f; setPadding(0, dp(4), 0, dp(8))
        })
        root.addView(CheckBox(this).apply {
            text = "הצג גם אפליקציות מערכת"
            setOnCheckedChangeListener { _, v -> showSystem = v; fill() }
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        setContentView(ScrollView(this).apply { addView(root) })
        fill()
        // סריקה (קריאה בלבד) כדי להראות מה נתפס
        Thread({
            Scanner.scan(this).let { f -> Scanner.last = f.associateBy { it.pkg } }
            runOnUiThread { fill() }
        }, "vg-applist-scan").start()
    }

    override fun onResume() {
        super.onResume()
        // מסך מוגן: אם האפליקציה ננעלה (יצאו ממנה) — חוזרים למסך הראשי, שמבקש PIN
        if (Lock.needed(this)) finish()
    }

    override fun onPause() {
        super.onPause()
        // שינוי מצבים נכנס לתוקף מיד ("מושעית" חלה גם כשהסריקה כבויה)
        Thread({ Scanner.scanAndApply(applicationContext) }, "vg-apply").start()
    }

    private fun fill() {
        list.removeAllViews()
        val pm = packageManager
        val found = Scanner.last
        val apps = pm.getInstalledApplications(0)
            .filter { it.packageName != packageName }
            .filter { showSystem || (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .map { Triple(it.packageName, pm.getApplicationLabel(it).toString(), it) }
            .sortedWith(compareBy({ it.first !in found }, { it.second.lowercase() }))

        for ((pkg, label, ai) in apps) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(10), 0, dp(10))
            }
            val f = found[pkg]
            val susp = try { pm.isPackageSuspended(pkg) } catch (_: Exception) { false }
            row.addView(TextView(this).apply {
                text = buildString {
                    append(label)
                    if ((ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0) append("  [מערכת]")
                    if (susp) append("  ⏸ מושעית")
                }
                textSize = 15f; setTextColor(Color.BLACK)
            })
            if (f != null) row.addView(TextView(this).apply {
                text = "⚑ " + f.reasons.joinToString("; "); textSize = 11f; setTextColor(Color.parseColor("#B00020"))
            })
            row.addView(TextView(this).apply { text = pkg; textSize = 10f; setTextColor(Color.GRAY) })

            val state = Prefs.appState(this, pkg)
            val group = RadioGroup(this).apply { orientation = LinearLayout.HORIZONTAL }
            val visual = CheckBox(this).apply {
                text = "זיהוי חזותי פעיל"
                isChecked = pkg in Prefs.allowedVisual(this@AppListActivity)
                visibility = if (state == Prefs.STATE_ALLOWED) View.VISIBLE else View.GONE
                setOnCheckedChangeListener { _, v -> Prefs.setAllowedVisual(this@AppListActivity, pkg, v) }
            }
            listOf(Prefs.STATE_NORMAL to "רגילה", Prefs.STATE_ALLOWED to "מותרת", Prefs.STATE_SUSPENDED to "מושעית")
                .forEach { (s, name) ->
                    group.addView(RadioButton(this).apply {
                        text = name; id = View.generateViewId(); isChecked = state == s
                        setOnClickListener {
                            Prefs.setAppState(this@AppListActivity, pkg, s)
                            visual.visibility = if (s == Prefs.STATE_ALLOWED) View.VISIBLE else View.GONE
                            if (s != Prefs.STATE_ALLOWED) visual.isChecked = false
                        }
                    })
                }
            row.addView(group)
            row.addView(visual)
            list.addView(row)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
