package android.ezerphone.videoguard

import android.content.Context
import android.util.Log
import com.topjohnwu.superuser.Shell
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** יומן פקודות: נשמר בזיכרון ובקובץ, ומוצג במסך "יומן פקודות". כל שורה גם ל-logcat (תג VideoGuard). */
object VgLog {
    private const val TAG = "VideoGuard"
    private const val MAX_LINES = 1500
    private val lines = ArrayList<String>()
    private var file: File? = null
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun init(c: Context) {
        val f = File(c.filesDir, "vg.log")
        file = f
        try {
            if (f.exists()) {
                val old = f.readLines().takeLast(MAX_LINES)
                lines.addAll(old)
                if (f.length() > 256 * 1024) f.writeText(old.joinToString("\n") + "\n")
            }
        } catch (_: Exception) {}
    }

    @Synchronized
    fun add(msg: String) {
        Log.i(TAG, msg)
        val stamp = fmt.format(Date())
        for (part in msg.split("\n")) {
            val line = "$stamp $part"
            lines.add(line)
            if (lines.size > MAX_LINES) lines.removeAt(0)
            try { file?.appendText(line + "\n") } catch (_: Exception) {}
        }
    }

    @Synchronized fun text(): String = lines.joinToString("\n")

    @Synchronized
    fun clear() {
        lines.clear()
        try { file?.writeText("") } catch (_: Exception) {}
    }
}

/**
 * שכבת ה-root היחידה באפליקציה (libsu). כל פקודה ותוצאה שלה נרשמות ליומן.
 * חשוב: לא לקרוא מה-thread הראשי — הקריאה הראשונה מחכה לבקשת ה-root. השתמש ב-[bg].
 */
object Root {
    class Res(val code: Int, val out: List<String>) {
        val ok get() = code == 0
        val text get() = out.joinToString("\n")
    }

    /** לקרוא פעם אחת ב-Application.onCreate, לפני כל שימוש ב-Shell. */
    fun init() {
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                // MOUNT_MASTER: מעטפת במרחב ה-mount הגלובלי (של init), כדי ש-mount יגיע
                // גם ל-mediaserver ול-swcodec. stderr מתמזג ל-stdout כדי שנראה שגיאות ביומן.
                .setFlags(Shell.FLAG_MOUNT_MASTER or Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(30)
        )
    }

    fun available(): Boolean = try { Shell.getShell().isRoot } catch (_: Throwable) { false }

    /** מריץ פקודה אחת או יותר (בלוק אחד) ורושם ליומן. */
    fun run(vararg cmds: String): Res {
        VgLog.add("$ " + cmds.joinToString("\n$ "))
        return try {
            val r = Shell.cmd(*cmds).exec()
            val out = r.out.map { it.trimEnd() }
            if (out.isNotEmpty()) VgLog.add(out.joinToString("\n"))
            VgLog.add("→ exit ${r.code}")
            Res(r.code, out)
        } catch (t: Throwable) {
            VgLog.add("✗ ${t.javaClass.simpleName}: ${t.message}")
            Res(-1, listOf(t.message ?: "error"))
        }
    }

    /** הרצה בלי רישום הפלט (לקריאת קבצים גדולים / טבלת mount). */
    fun quiet(cmd: String): List<String> = try {
        Shell.cmd(cmd).exec().out.map { it.trimEnd() }
    } catch (_: Throwable) { emptyList() }

    fun test(cmd: String): Boolean = try { Shell.cmd(cmd).exec().isSuccess } catch (_: Throwable) { false }

    fun exists(path: String) = test("[ -e '$path' ]")

    /** קורא קובץ שלם דרך root, בלי להציף את היומן. */
    fun read(path: String): String {
        val t = quiet("cat '$path'").joinToString("\n")
        VgLog.add("read $path (${t.length} chars)")
        return t
    }

    /** הרצה ברקע (לא חוסמת את ה-UI). */
    fun bg(block: () -> Unit) { Thread(block, "vg-root").start() }
}
