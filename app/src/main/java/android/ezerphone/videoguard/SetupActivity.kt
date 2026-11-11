package android.ezerphone.videoguard

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/** מציג את שלבי ההפעלה (או הביטול) בזמן אמת. כל כישלון עוצר ומסביר. פקודות מלאות: "יומן פקודות". */
class SetupActivity : Activity(), Ui {
    private lateinit var tv: TextView
    private lateinit var scroll: ScrollView
    private lateinit var close: Button
    @Volatile private var pending: CountDownLatch? = null
    @Volatile private var dead = false

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

        // רק תהליך אחד בכל רגע: מסך שנוצר מחדש לא מפעיל שוב שינוי מחיצות במקביל
        if (!busy.compareAndSet(false, true)) {
            tv.text = "פעולה קודמת עדיין רצה ברקע. המתן לסיומה (פירוט ב\"יומן פקודות\") ונסה שוב."
            close.visibility = View.VISIBLE
            return
        }
        Thread({
            try {
                if (undo) Setup.runUndo(this, this) else Setup.runAll(this, this)
            } catch (f: Setup.Fail) {
                say("✗ ${f.message}")
                say("\nהפעולה נעצרה. כל שלב ניתן לחזרה: תקן ולחץ שוב. פירוט הפקודות: \"יומן פקודות\".")
            } catch (t: Throwable) {
                VgLog.add("שגיאה: ${t.javaClass.simpleName}: ${t.message}")
                say("✗ שגיאה בלתי צפויה: ${t.message}")
            } finally {
                busy.set(false)
            }
            runOnUiThread { if (!dead) close.visibility = View.VISIBLE }
        }, "vg-setup").start()
    }

    /** "חזור" לא סוגר את המסך באמצע שינוי מחיצות. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (busy.get() && close.visibility != View.VISIBLE) {
            Toast.makeText(this, "המתן לסיום הפעולה", Toast.LENGTH_SHORT).show()
            return
        }
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onDestroy() {
        dead = true
        pending?.countDown() // שאלה פתוחה נענית "לא", כדי שה-thread לא ייתקע לנצח
        super.onDestroy()
    }

    override fun say(s: String) {
        VgLog.add(s)
        if (dead) return
        runOnUiThread {
            if (dead) return@runOnUiThread
            tv.append(s + "\n")
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    /** חוסם עד תשובה. מסך שנסגר = "לא" (אף פעם לא ממשיכים לשנות מחיצות בלי אישור). */
    override fun confirm(msg: String): Boolean {
        if (dead || isFinishing) return false
        val latch = CountDownLatch(1)
        pending = latch
        var res = false
        runOnUiThread {
            if (dead || isFinishing) { latch.countDown(); return@runOnUiThread }
            try {
                AlertDialog.Builder(this)
                    .setMessage(msg).setCancelable(false)
                    .setPositiveButton("אשר") { _, _ -> res = !dead; latch.countDown() }
                    .setNegativeButton("בטל") { _, _ -> latch.countDown() }
                    .show()
            } catch (_: Exception) { latch.countDown() }
        }
        latch.await()
        pending = null
        return res && !dead
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private val busy = AtomicBoolean(false)
    }
}
