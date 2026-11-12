package android.ezerphone.videoguard

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import java.util.zip.ZipFile

/**
 * סורק אפליקציה ומשעה אותה אם היא פותחת וידאו:
 *  1. מצהירה שהיא פותחת קבצי וידאו (ACTION_VIEW עם סוג video), או
 *  2. מצרפת ספריית פענוח משלה ([Detector]) — נגנים כאלה עובדים גם בלי מפענחי המערכת.
 *
 * לא נסרקות: אפליקציות מערכת (גם אם עודכנו), אפליקציות שהמשתמש פטר, מסך הבית, המקלדת והאפליקציה עצמה.
 * ההשעיה דרך Device Owner (אי אפשר לבטל אותה ב-adb), ואם אין — דרך `pm suspend` של root.
 */
object Scanner {
    class Finding(val pkg: String, val label: String, val reasons: List<String>)

    /** בודק חבילה אחת. null = לא פותחת וידאו או לא נסרקת. */
    fun check(c: Context, pkg: String, skip: Set<String> = skipSet(c)): Finding? {
        val pm = c.packageManager
        val ai = try { pm.getApplicationInfo(pkg, 0) } catch (_: Exception) { return null }
        if (!shouldScan(c, ai, skip)) return null
        val reasons = ArrayList<String>()
        if (opensVideo(c, pkg)) reasons += "פותחת קבצי וידאו"
        val libs = decoderLibs(ai)
        if (libs.isNotEmpty()) reasons += "ספריית פענוח: ${libs.take(3).joinToString()}"
        if (reasons.isEmpty()) return null
        return Finding(pkg, label(c, ai), reasons)
    }

    /** סורק חבילה ומשעה אם צריך. נקרא מהשירות בהתקנה/עדכון. */
    fun scanOne(c: Context, pkg: String) {
        val f = check(c, pkg)
        if (f == null) { VgLog.add("סריקה: $pkg — תקין"); return }
        suspend(c, listOf(f))
    }

    /** סורק את כל האפליקציות המותקנות. מחזיר את מה שנמצא. */
    fun scanAll(c: Context, onProgress: (String) -> Unit = {}): List<Finding> {
        val apps = c.packageManager.getInstalledApplications(0)
        val skip = skipSet(c)
        val found = ArrayList<Finding>()
        for (ai in apps) {
            if (!shouldScan(c, ai, skip)) continue
            check(c, ai.packageName, skip)?.let { found += it; onProgress("  ${it.label} — ${it.reasons.joinToString("; ")}") }
        }
        Prefs.setLastScan(c, System.currentTimeMillis())
        VgLog.add("סריקה מלאה: נמצאו ${found.size}")
        if (found.isNotEmpty()) suspend(c, found)
        return found
    }

    /** מה שהותקן או עודכן מאז הסריקה האחרונה (בזמן שהשירות לא רץ). */
    fun catchUp(c: Context) {
        val since = Prefs.lastScan(c) - 60_000L
        val now = System.currentTimeMillis()
        val pm = c.packageManager
        val skip = skipSet(c)
        val changed = pm.getInstalledApplications(0).filter { ai ->
            shouldScan(c, ai, skip) && try { pm.getPackageInfo(ai.packageName, 0).lastUpdateTime >= since } catch (_: Exception) { false }
        }
        if (Prefs.lastScan(c) == 0L) VgLog.add("שירות: סריקה ראשונה של כל האפליקציות")
        else if (changed.isNotEmpty()) VgLog.add("שירות: ${changed.size} אפליקציות הותקנו או עודכנו מאז הסריקה האחרונה")
        val found = changed.mapNotNull { check(c, it.packageName, skip) }
        if (found.isNotEmpty()) suspend(c, found)
        Prefs.setLastScan(c, now)
    }

    // ───────────────────────── השעיה ─────────────────────────

    fun suspend(c: Context, list: List<Finding>) {
        val pkgs = list.map { it.pkg }
        val failed = if (Admin.isOwner(c)) Admin.setSuspended(c, pkgs, true)
            else pkgs.filterNot { Root.run("pm suspend --user 0 $it").ok }
        val ok = list.filter { it.pkg !in failed }
        for (f in ok) {
            Prefs.setReason(c, f.pkg, f.reasons.joinToString("; "))
            VgLog.add("הושעתה: ${f.label} (${f.pkg}) — ${f.reasons.joinToString("; ")}")
        }
        Prefs.addSuspended(c, ok.map { it.pkg })
        if (failed.isNotEmpty()) VgLog.add("השעיה נכשלה: ${failed.joinToString()}")
    }

    /** משחרר (גם השעיה של Device Owner וגם של root, ליתר ביטחון). */
    fun unsuspend(c: Context, pkgs: Collection<String>) {
        if (pkgs.isEmpty()) return
        if (Admin.isOwner(c)) Admin.setSuspended(c, pkgs.toList(), false)
        for (p in pkgs) Root.run("pm unsuspend --user 0 $p")
        Prefs.removeSuspended(c, pkgs)
        pkgs.forEach { Prefs.setReason(c, it, null) }
        VgLog.add("שוחררו: ${pkgs.joinToString()}")
    }

    // ───────────────────────── פנימי ─────────────────────────

    /** פטורות + מסכי בית ומקלדת. מחושב פעם אחת לכל סריקה (שאילתה לכל אפליקציה הייתה איטית מאוד). */
    private fun skipSet(c: Context): Set<String> = Prefs.exempt(c) + essential(c) + c.packageName

    private fun shouldScan(c: Context, ai: ApplicationInfo, skip: Set<String>): Boolean {
        if (ai.packageName in skip) return false
        if (ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) return false
        return true
    }

    /** מסכי בית ומקלדת שמותקנים כאפליקציות רגילות. */
    private fun essential(c: Context): Set<String> {
        val out = HashSet<String>()
        try {
            c.packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_ALL
            ).forEach { out += it.activityInfo.packageName }
        } catch (_: Exception) {}
        try {
            Settings.Secure.getString(c.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.substringBefore('/')?.let { out += it }
        } catch (_: Exception) {}
        return out
    }

    private fun opensVideo(c: Context, pkg: String): Boolean {
        val pm = c.packageManager
        val intents = listOf(
            Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://media/external/video/media/1"), "video/mp4"),
            Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("file:///sdcard/a.mkv"), "video/x-matroska"),
            Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://x/a"), "video/*"),
            Intent(Intent.ACTION_VIEW).setType("video/*")
        )
        return intents.any { i ->
            try {
                pm.queryIntentActivities(i.setPackage(pkg), PackageManager.MATCH_DISABLED_COMPONENTS).isNotEmpty()
            } catch (_: Exception) { false }
        }
    }

    private fun decoderLibs(ai: ApplicationInfo): List<String> {
        val apks = listOfNotNull(ai.sourceDir) + (ai.splitSourceDirs?.toList() ?: emptyList())
        val out = LinkedHashSet<String>()
        for (apk in apks) try {
            ZipFile(apk).use { z -> out += Detector.decoderLibs(z.entries().asSequence().map { it.name }) }
        } catch (_: Exception) {}
        return out.toList()
    }

    fun label(c: Context, pkg: String): String = try {
        label(c, c.packageManager.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES))
    } catch (_: Exception) { pkg }

    private fun label(c: Context, ai: ApplicationInfo) = try { c.packageManager.getApplicationLabel(ai).toString() } catch (_: Exception) { ai.packageName }
}
