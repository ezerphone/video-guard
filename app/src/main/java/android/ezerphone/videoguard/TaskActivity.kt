package android.ezerphone.videoguard

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast

/**
 * מריץ פעולה ארוכה ברקע. הפעולות לא שואלות שאלות: האישור נלקח לפני שהן מתחילות (חלון האישור
 * במסך הקודם). כך הפעולה ממשיכה גם אם יוצאים מהאפליקציה והיא ננעלת, והתוצאה מוצגת אחרי הזנת הקוד.
 * המשתמש רואה רק הודעה פשוטה; הפירוט הטכני נכתב ליומן (קובץ files/vg.log, נגיש רק עם root).
 */
object TaskRunner {
    enum class Kind(val running: String, val wait: String) {
        BLOCK("מפעיל חסימה…", "זה לוקח כמה שניות.\nאל תכבה את הטלפון."),
        RESTORE("מחזיר את נגני הווידאו…", "זה לוקח כמה שניות.\nאל תכבה את הטלפון."),
        SCAN("בודק אפליקציות…", "זה יכול לקחת עד דקה."),
        PROTECT("מפעיל הגנה…", "זה לוקח כמה שניות."),
        UNPROTECT("מבטל את ההגנה…", "זה לוקח כמה שניות.")
    }
    enum class Phase { IDLE, RUNNING, DONE, FAILED }

    @Volatile var kind = Kind.SCAN
        private set
    @Volatile var phase = Phase.IDLE
        private set
    @Volatile var message = ""
        private set
    @Volatile var reboot = false
        private set
    @Volatile private var seen = true

    /** יש פעולה שרצה או תוצאה שהמשתמש עוד לא ראה. */
    fun needsAttention() = phase == Phase.RUNNING || (!seen && phase != Phase.IDLE)

    fun ack() { seen = true; if (phase != Phase.RUNNING) phase = Phase.IDLE }

    @Synchronized
    fun start(c: Context, k: Kind): Boolean {
        if (phase == Phase.RUNNING) return false
        val app = c.applicationContext
        kind = k; phase = Phase.RUNNING; seen = false; reboot = false; message = ""
        Thread({
            VgLog.add("── ${k.running} ──")
            try {
                message = run(app, k)
                phase = Phase.DONE
            } catch (f: Fail) {
                VgLog.add("✗ ${f.message}")
                message = f.friendly
                phase = Phase.FAILED
            } catch (t: Throwable) {
                VgLog.add("✗ ${t.javaClass.simpleName}: ${t.message}")
                message = Fail.NO_CHANGE
                phase = Phase.FAILED
            }
        }, "vg-task").start()
        return true
    }

    private val log = object : Ui {
        override fun say(s: String) = VgLog.add(s)
        override fun confirm(msg: String) = true
    }

    private fun run(c: Context, k: Kind): String = when (k) {
        Kind.BLOCK -> if (Codecs.remove(c, log)) {
            reboot = true; Prefs.setRebootBoot(c, bootCount(c))
            "החסימה תתחיל לפעול אחרי הפעלה מחדש של הטלפון."
        } else "נגני הווידאו כבר חסומים."
        Kind.RESTORE -> {
            Codecs.restore(c, log)
            reboot = true; Prefs.setRebootBoot(c, bootCount(c))
            "נגני הווידאו יחזרו לפעול אחרי הפעלה מחדש של הטלפון."
        }
        Kind.SCAN -> {
            val n = Scanner.scanAll(c).size
            if (n == 0) "לא נמצאו אפליקציות שפותחות וידאו." else "נמצאו ונחסמו $n אפליקציות שפותחות וידאו."
        }
        Kind.PROTECT -> { Admin.protect(c, log); "ההגנה פעילה. אי אפשר להסיר את האפליקציה." }
        Kind.UNPROTECT -> { Admin.unprotect(c, log); "ההגנה בוטלה. עכשיו אפשר להסיר את האפליקציה." }
    }

    fun bootCount(c: Context) = Settings.Global.getInt(c.contentResolver, Settings.Global.BOOT_COUNT, -1)
}

/** מסך ההתקדמות והתוצאה של [TaskRunner]. */
class TaskActivity : Guarded() {
    private val h = Handler(Looper.getMainLooper())
    private var shown: TaskRunner.Phase? = null
    private lateinit var box: LinearLayout

    override fun build() {
        box = St.col(this)
        setContentView(St.scroll(this, box))
    }

    override fun refresh() { h.removeCallbacks(tick); h.post(tick) }

    override fun onPause() { super.onPause(); h.removeCallbacks(tick) }

    private val tick = object : Runnable {
        override fun run() {
            val p = TaskRunner.phase
            if (p == TaskRunner.Phase.IDLE) { finish(); return }
            if (p != shown) { shown = p; render(p) }
            if (p == TaskRunner.Phase.RUNNING) h.postDelayed(this, 300)
        }
    }

    private fun render(p: TaskRunner.Phase) {
        box.removeAllViews()
        val center = St.col(this).apply { gravity = Gravity.CENTER; setPadding(St.dp(this@TaskActivity, 32), 0, St.dp(this@TaskActivity, 32), 0) }
        val buttons = St.col(this).apply { setPadding(St.dp(this@TaskActivity, 16), 0, St.dp(this@TaskActivity, 16), St.dp(this@TaskActivity, 32)) }
        val k = TaskRunner.kind
        when (p) {
            TaskRunner.Phase.RUNNING, TaskRunner.Phase.IDLE -> {
                center.addView(ProgressBar(this).apply { indeterminateTintList = android.content.res.ColorStateList.valueOf(St.PRIMARY) },
                    LinearLayout.LayoutParams(St.dp(this, 72), St.dp(this, 72)))
                addTexts(center, k.running, k.wait)
            }
            TaskRunner.Phase.DONE -> {
                center.addView(St.iconBox(this, R.drawable.ic_check, St.OK_SOFT, box = 96, size = 48, radius = 48))
                addTexts(center, "הושלם", TaskRunner.message)
                if (TaskRunner.reboot) {
                    buttons.addView(St.button(this, "הפעל מחדש עכשיו") { TaskRunner.ack(); Root.bg { Codecs.reboot() } })
                    buttons.addView(St.button(this, "אחר כך", 't') { close() })
                } else buttons.addView(St.button(this, "סגור") { close() })
            }
            TaskRunner.Phase.FAILED -> {
                center.addView(St.iconBox(this, R.drawable.ic_alert, St.BAD_SOFT, box = 96, size = 44, radius = 48))
                addTexts(center, "הפעולה לא הושלמה", TaskRunner.message)
                buttons.addView(St.button(this, "נסה שוב") { TaskRunner.start(this, k); refresh() })
                buttons.addView(St.button(this, "סגור", 't') { close() })
            }
        }
        box.addView(center, St.lp(h = 0, weight = 1f))
        box.addView(buttons)
    }

    private fun addTexts(v: LinearLayout, t: String, s: String) {
        v.addView(St.text(this, t, 24f, St.TEXT, true).apply { gravity = Gravity.CENTER; setPadding(0, St.dp(this@TaskActivity, 18), 0, St.dp(this@TaskActivity, 10)) })
        v.addView(St.text(this, s, 16f, St.MUTED).apply { gravity = Gravity.CENTER })
    }

    private fun close() { TaskRunner.ack(); finish() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (TaskRunner.phase == TaskRunner.Phase.RUNNING) { Toast.makeText(this, "המתן לסיום הפעולה", Toast.LENGTH_SHORT).show(); return }
        close()
    }

    companion object {
        fun start(a: android.app.Activity, k: TaskRunner.Kind) {
            if (!TaskRunner.start(a, k)) Toast.makeText(a, "פעולה אחרת עדיין רצה", Toast.LENGTH_SHORT).show()
            a.startActivity(Intent(a, TaskActivity::class.java))
        }
    }
}
