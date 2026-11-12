package android.ezerphone.videoguard

import android.content.Context
import android.util.Base64
import android.util.Log
import com.topjohnwu.superuser.Shell
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** יומן: בזיכרון ובקובץ, מוצג במסך "יומן". כל שורה גם ל-logcat (תג VideoGuard). */
object VgLog {
    private const val TAG = "VideoGuard"
    private const val MAX_LINES = 1500
    private const val MAX_FILE = 256 * 1024L
    private val lines = ArrayList<String>()
    private var file: File? = null
    private val fmt = SimpleDateFormat("dd/MM HH:mm:ss", Locale.US)
    private var sinceTrim = 0

    @Synchronized
    fun init(c: Context) {
        val f = File(c.filesDir, "vg.log")
        file = f
        try { if (f.exists()) lines.addAll(f.readLines().takeLast(MAX_LINES)) } catch (_: Exception) {}
        trim()
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
        if (++sinceTrim >= 200) { sinceTrim = 0; trim() }
    }

    private fun trim() {
        try {
            val f = file ?: return
            if (f.length() > MAX_FILE) f.writeText(lines.joinToString("\n") + "\n")
        } catch (_: Exception) {}
    }

    @Synchronized fun text(): String = lines.joinToString("\n")

    @Synchronized
    fun clear() {
        lines.clear()
        try { file?.writeText("") } catch (_: Exception) {}
    }
}

/**
 * גישת root (libsu). כל פקודה ותוצאה נרשמות ליומן. לא לקרוא מה-thread הראשי.
 * המעטפת רצה במרחב ה-mount הגלובלי, כדי ש-mount/umount ישפיעו על תהליכי המדיה.
 */
object Root {
    class Res(val code: Int, val out: List<String>) {
        val ok get() = code == 0
        val text get() = out.joinToString("\n")
    }

    fun init() {
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER or Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(30)
        )
    }

    fun available(): Boolean = try { Shell.getShell().isRoot } catch (_: Throwable) { false }

    /** כל הפקודות רצות; רק קוד היציאה של האחרונה נבדק. לצעד שכולו חייב להצליח — [chain]. */
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

    /** משורשר ב-&&: נכשל אם *אחת* הפקודות נכשלה. פקודה עם || חייבת להיות עטופה ב-{ ...; }. */
    fun chain(vararg cmds: String): Res = run(cmds.joinToString(" && \\\n  "))

    /** בלי רישום הפלט (טבלת mount וכד'). */
    fun quiet(cmd: String): List<String> = try {
        Shell.cmd(cmd).exec().out.map { it.trimEnd() }
    } catch (_: Throwable) { emptyList() }

    fun test(cmd: String): Boolean = try { Shell.cmd(cmd).exec().isSuccess } catch (_: Throwable) { false }

    fun exists(path: String) = test("[ -e '$path' ]")

    /** קורא קובץ בדיוק כמו שהוא (דרך base64, בלי לאבד שורות ריקות או סוף שורה). null = נכשל. */
    fun readText(path: String): String? = try {
        val r = Shell.cmd("base64 -w 0 '$path'").exec()
        if (!r.isSuccess) null
        else String(Base64.decode(r.out.joinToString(""), Base64.DEFAULT), Charsets.UTF_8)
    } catch (_: Throwable) { null }

    /**
     * כותב טקסט לקובץ: קודם לקובץ זמני בתיקיית האפליקציה, ואז לקובץ היעד (שומר הרשאות ו-SELinux
     * context של קובץ קיים), ואימות ב-cmp. מחזיר true רק אם התוכן בקובץ היעד זהה.
     *
     * הקובץ מרוקן ו-sync לפני הכתיבה: מחיצות המערכת בנויות בלי מקום פנוי, וב-ext4 בלוקים שהתפנו
     * לא זמינים לשימוש חוזר עד שהיומן נשמר. `cat > dst` ישיר נכשל שם ב-"No space left on device"
     * גם כשהתוכן החדש קטן מהישן, ומשאיר קובץ ריק.
     */
    fun writeText(c: Context, dst: String, text: String): Boolean {
        val tmp = File(c.cacheDir, "w-${System.nanoTime()}.tmp")
        return try {
            tmp.writeText(text, Charsets.UTF_8)
            chain(": > '$dst'", "sync", "cat '${tmp.absolutePath}' >> '$dst'", "sync",
                "cmp -s '${tmp.absolutePath}' '$dst'").ok
        } catch (t: Throwable) {
            VgLog.add("✗ כתיבה ל-$dst: ${t.message}")
            false
        } finally { tmp.delete() }
    }

    fun bg(name: String = "vg-root", block: () -> Unit) { Thread(block, name).start() }
}
