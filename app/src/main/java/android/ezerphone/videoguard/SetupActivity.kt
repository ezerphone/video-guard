package android.ezerphone.videoguard

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.CountDownLatch

/** מציג את שלבי ההפעלה (או הביטול) בזמן אמת. כל כישלון עוצר ומסביר. פקודות מלאות: "יומן פקודות". */
class SetupActivity : Activity(), Ui {
    private lateinit var tv: TextView
    private lateinit var scroll: ScrollView
    private lateinit var close: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val undo = intent.getStringExtra("mode") == "undo"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        root.addView(TextView(this).apply {
            text = if (undo) "ביטול הכל" else "הפעלת חסימה מלאה"; textSize = 22f
        })
        tv = TextView(this).apply { textSize = 14f; setPadding(0, dp(12), 0, dp(12)) }
        scroll = ScrollView(this).apply { addView(tv) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        close = Button(this).apply {
            text = "סגור"; visibility = View.GONE
            setOnClickListener { finish() }
        }
        root.addView(close)
        setContentView(root)

        Thread({
            try {
                if (undo) Setup.runUndo(this, this) else Setup.runAll(this, this)
            } catch (f: Setup.Fail) {
                say("✗ ${f.message}")
                say("\nהפעולה נעצרה. כל שלב ניתן לחזרה: תקן ולחץ שוב. פירוט הפקודות: \"יומן פקודות\".")
            } catch (t: Throwable) {
                VgLog.add("שגיאה: ${t.javaClass.simpleName}: ${t.message}")
                say("✗ שגיאה בלתי צפויה: ${t.message}")
            }
            runOnUiThread { close.visibility = View.VISIBLE }
        }, "vg-setup").start()
    }

    override fun say(s: String) {
        VgLog.add(s)
        runOnUiThread {
            tv.append(s + "\n")
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    override fun confirm(msg: String): Boolean {
        val latch = CountDownLatch(1)
        var res = false
        runOnUiThread {
            AlertDialog.Builder(this)
                .setMessage(msg).setCancelable(false)
                .setPositiveButton("אשר") { _, _ -> res = true; latch.countDown() }
                .setNegativeButton("בטל") { _, _ -> latch.countDown() }
                .show()
        }
        latch.await()
        return res
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
