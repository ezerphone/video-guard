package android.ezerphone.videoguard

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * מריץ פעולה ארוכה ב-thread ומציג את ההתקדמות: מחיקת/שחזור מפענחים, סריקה מלאה, הפעלה/הסרה של ההגנה.
 * - רק פעולה אחת בכל רגע (גם אם המסך נוצר מחדש).
 * - סיבוב מסך לא יוצר את המסך מחדש (configChanges במניפסט).
 * - "חזור" חסום באמצע; מסך שנסגר עונה "לא" לשאלה פתוחה, כדי שה-thread לא ייתקע.
 */
class TaskActivity : GuardedActivity(), Ui {
    private lateinit var tv: TextView
    private lateinit var scroll: ScrollView
    private lateinit var close: Button
    @Volatile private var pending: CountDownLatch? = null
    @Volatile private var dead = false
    @Volatile private var done = false

    // פעולה שרצה ממשיכה גם אם המשתמש יצא מהאפליקציה; היא כבר אושרה עם PIN
    override fun keepWhenLocked() = !done

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra("mode") ?: "scan"
        val title = when (mode) {
            "remove" -> "מחיקת מפענחי וידאו"
            "restore" -> "שחזור מפענחים"
            "protect" -> "הפעלת הגנה"
            "unprotect" -> "הסרת הגנה"
            else -> "סריקת כל האפליקציות"
        }
        val root = Kit.page(this, title)
        scroll = root.parent as ScrollView
        tv = TextView(this).apply { textSize = 14f; setPadding(0, Kit.dp(this@TaskActivity, 12), 0, Kit.dp(this@TaskActivity, 12)) }
        root.addView(tv)
        close = Kit.button(this, "סגור") { finish() }.apply { visibility = View.GONE }
        root.addView(close)

        if (!busy.compareAndSet(false, true)) {
            say("פעולה קודמת עדיין רצה ברקע. המתן לסיומה (פירוט ביומן) ונסה שוב.")
            finished(); return
        }
        Thread({
            try {
                VgLog.add("── $title ──")
                run(mode)
            } catch (f: Fail) {
                say("✗ ${f.message}")
            } catch (t: Throwable) {
                VgLog.add("שגיאה: ${t.javaClass.simpleName}: ${t.message}")
                say("✗ שגיאה בלתי צפויה: ${t.message}")
            } finally {
                busy.set(false)
                finished()
            }
        }, "vg-task").start()
    }

    private fun run(mode: String) {
        when (mode) {
            "remove" -> Codecs.remove(this, this)
            "restore" -> Codecs.restore(this, this)
            "scan" -> {
                say("▶ סורק את כל האפליקציות (חוץ מאפליקציות מערכת ופטורות)...")
                val found = Scanner.scanAll(applicationContext) { say(it) }
                say(if (found.isEmpty()) "✓ לא נמצאו אפליקציות שפותחות וידאו" else "✓ נמצאו והושעו ${found.size}")
            }
            "protect" -> {
                if (!confirm("להפעיל הגנה?\n\nהאפליקציה תהפוך ל-Device Owner: אי אפשר יהיה להסיר אותה, והנעילות שבחרת יופעלו." +
                        (if (Prefs.lockAdb(this)) "\n\nשים לב: ADB ייחסם מיד. אם אתה מחובר עכשיו ב-adb, החיבור ינותק." else "") +
                        "\n\nהסרה: רק מתוך האפליקציה, עם ה-PIN.")) throw Fail("בוטל.")
                Admin.protect(applicationContext, this)?.let { throw Fail(it) }
                say("✓ ההגנה פעילה")
            }
            "unprotect" -> {
                if (!confirm("להסיר את ההגנה?\n\nכל האפליקציות המושעות ישוחררו, הנעילות יבוטלו ואפשר יהיה להסיר את האפליקציה. " +
                        "מחיקת המפענחים לא משתנה (לזה יש כפתור נפרד).")) throw Fail("בוטל.")
                Admin.unprotect(applicationContext, this)
            }
        }
    }

    private fun finished() {
        done = true
        runOnUiThread { if (!dead) close.visibility = View.VISIBLE }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!done) { Toast.makeText(this, "המתן לסיום הפעולה", Toast.LENGTH_SHORT).show(); return }
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onDestroy() {
        dead = true
        pending?.countDown()
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
                    .setPositiveButton("אשר") { _, _ -> res = true; latch.countDown() }
                    .setNegativeButton("בטל") { _, _ -> latch.countDown() }
                    .show()
            } catch (_: Exception) { latch.countDown() }
        }
        latch.await()
        pending = null
        return res && !dead
    }

    companion object {
        private val busy = AtomicBoolean(false)
    }
}
