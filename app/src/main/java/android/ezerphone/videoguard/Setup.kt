package android.ezerphone.videoguard

import android.content.Context
import android.content.pm.ApplicationInfo
import org.xml.sax.InputSource
import java.io.File
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

interface Ui {
    fun say(s: String)
    /** שואל את המשתמש (חוסם עד תשובה). */
    fun confirm(msg: String): Boolean
}

object Paths {
    const val SYS_VG = "/system/etc/vg"
    const val SYS_BK = "/system/etc/vg/backup"
    const val SD_BK = "/sdcard/VideoGuard/backup"
    const val RC = "/system/etc/init/vg_swcodec.rc"
    const val APP_DIR = "/system/app/VideoGuard"
    const val APEX_XML = "/apex/com.android.media.swcodec/etc/media_codecs.xml"
    const val TMP = "/data/local/tmp/vg"
    /** מחיצה -> תיקיית etc שלה. */
    val PARTS = listOf(
        "vendor" to "/vendor/etc", "odm" to "/odm/etc", "product" to "/product/etc",
        "system_ext" to "/system_ext/etc", "system" to "/system/etc"
    )
}

/** פתיחת מחיצות לכתיבה. */
object Fs {
    private fun mountPoint(part: String) = if (part == "system") "/system" else "/$part"

    private fun fsType(mp: String): String? {
        val lines = Root.quiet("mount")
        val l = lines.firstOrNull { it.contains(" on $mp type ") }
            ?: if (mp == "/system") lines.firstOrNull { it.contains(" on / type ") } else null
        return l?.substringAfter(" type ")?.substringBefore(' ')
    }

    /** null = הכול נפתח לכתיבה, אחרת הסבר למה לא. */
    fun makeWritable(parts: Set<String>): String? {
        val slot = Root.quiet("getprop ro.boot.slot_suffix").firstOrNull()?.trim().orEmpty()
        val all = parts + "system"
        for (p in all) {
            val t = fsType(mountPoint(p))
            VgLog.add("מחיצה $p: מערכת קבצים=$t slot='$slot'")
            if (t == "erofs") return "המחיצה $p היא erofs (קריאה בלבד, אי אפשר לפתוח לכתיבה). צריך לבנות את המחיצה מחדש במחשב."
        }
        for (p in all) {
            Root.run("blockdev --setrw /dev/block/mapper/$p$slot")
            var r = Root.run("mount -o remount,rw ${mountPoint(p)}")
            if (!r.ok && p == "system") r = Root.run("mount -o remount,rw /")
            if (!r.ok) {
                val t = r.text
                return if (t.contains("shared_blocks", true) || t.contains("shared blocks", true))
                    "המחיצה $p משתמשת ב-shared_blocks ולא ניתן לפתוח אותה לכתיבה. צריך לבנות את המחיצה מחדש במחשב."
                else "פתיחת $p לכתיבה נכשלה:\n$t\nאם dm-verity פעיל (ro.boot.veritymode=enforcing) הכתיבה תיכשל או תגרום לשגיאות קריאה; יש לכבות אותו במחשב."
            }
        }
        if (!Root.run("mkdir -p ${Paths.SYS_VG} && touch ${Paths.SYS_VG}/.w && rm ${Paths.SYS_VG}/.w").ok)
            return "הכתיבה ל-/system נכשלה למרות ה-remount."
        for (p in parts) if (p != "system") {
            val dir = Paths.PARTS.first { it.first == p }.second
            if (!Root.run("touch $dir/.vgw && rm $dir/.vgw").ok) return "הכתיבה ל-$dir נכשלה למרות ה-remount."
        }
        return null
    }

    fun readOnlyAgain(parts: Set<String>) {
        for (p in parts + "system") {
            if (!Root.run("mount -o remount,ro ${mountPoint(p)}").ok && p == "system")
                Root.run("mount -o remount,ro /")
        }
    }
}

object Setup {
    class Fail(msg: String) : Exception(msg)

    private val MC = Regex("<MediaCodec[\\s>]")
    private val ENC = Regex("<Encoders")
    private val AUDIO = Regex("audio/")

    private fun asset(c: Context, name: String): File {
        val f = File(c.filesDir, name)
        c.assets.open(name).use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        f.setReadable(true, false)
        return f
    }

    private fun validXml(s: String) = try {
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(InputSource(StringReader(s))); true
    } catch (_: Exception) { false }

    private fun findBusybox(): String? =
        listOf("/data/adb/magisk/busybox", "/data/adb/ksu/bin/busybox", "/data/adb/ap/bin/busybox")
            .firstOrNull { Root.test("[ -x '$it' ]") }
            ?: Root.quiet("which busybox").firstOrNull()?.takeIf { it.startsWith("/") }

    // ───────────────────────── הפעלה מלאה ─────────────────────────

    fun runAll(c: Context, ui: Ui) {
        if (!Root.available()) throw Fail("אין גישת root. אשר את בקשת ה-root של Magisk לאפליקציה ונסה שוב.")
        ui.say("✓ root זמין")
        checkOldModule()
        Admin.prerequisites(c)?.let { throw Fail(it) }

        if (Prefs.codecsOn(c)) codecs(c, ui) else ui.say("○ הסרת מפענחים כבויה (מתג)")
        installApp(c, ui)
        deviceOwner(c, ui)

        Prefs.setSetupDone(c, true)
        Prefs.setPendingVerify(c, true)
        ui.say("✓ ההגדרה הושלמה. נדרש אתחול כדי שהשינויים יחולו.")
        if (ui.confirm("לאתחל עכשיו? אחרי האתחול האפליקציה תבדוק אוטומטית ותציג \"מפענחי וידאו במערכת\".")) {
            Root.run("sync", "reboot")
        } else ui.say("אתחל ידנית כשנוח. הבדיקה תרוץ אחרי האתחול.")
    }

    private fun checkOldModule() {
        val mods = Root.quiet("ls /data/adb/modules 2>/dev/null").filter {
            it.contains("videoguard", true) || it.contains("videostop", true)
        }
        for (m in mods) {
            if (!Root.test("[ -f /data/adb/modules/$m/disable ] || [ -f /data/adb/modules/$m/remove ]"))
                throw Fail("מודול Magisk ישן ($m) עדיין פעיל. כבה אותו ב-Magisk, אתחל, ואז חזור לכאן. הוא מציג עותקים מעל הקבצים המקוריים ויפריע לבדיקות.")
        }
    }

    // ───────────────────────── הסרת מפענחים ─────────────────────────

    private class Item(val part: String, val name: String, val live: String, val orig: String,
                       val tmp: String, val removed: Int)

    /** מפיק עותק מסונן ל-tmp, בודק אותו, ומחזיר כמה בלוקי MediaCodec יוסרו. -1 = מדלגים. */
    private fun filterTo(c: Context, bb: String, awk: File, orig: String, tmp: String): Int {
        val r = Root.run("$bb awk -f '${awk.absolutePath}' '$orig' > '$tmp'")
        if (!r.ok) throw Fail("awk נכשל על $orig:\n${r.text}")
        val a = Root.read(orig)
        val b = Root.read(tmp)
        if (!validXml(a)) { VgLog.add("⚠ $orig אינו XML תקין — לא נוגעים בו"); return -1 }
        if (b.isBlank()) throw Fail("הפלט המסונן של $orig ריק. לא כותבים.")
        if (!validXml(b)) throw Fail("הפלט המסונן של $orig אינו XML תקין. לא כותבים.")
        if (AUDIO.findAll(a).count() != AUDIO.findAll(b).count()) throw Fail("הסינון שינה מקודדי אודיו ב-$orig. לא כותבים.")
        if (ENC.findAll(a).count() != ENC.findAll(b).count()) throw Fail("הסינון שינה את בלוק ה-Encoders ב-$orig. לא כותבים.")
        return MC.findAll(a).count() - MC.findAll(b).count()
    }

    private fun codecs(c: Context, ui: Ui) {
        ui.say("▶ הסרת מפענחי וידאו")
        val bb = findBusybox() ?: throw Fail("לא נמצא busybox (נדרש ל-awk). ב-Magisk הוא ב-/data/adb/magisk/busybox.")
        val awk = asset(c, "strip_video_decoders.awk")
        Root.run("mkdir -p ${Paths.TMP}")

        // ---- הכנה (קריאה בלבד): מה יוסר מכל קובץ ----
        val items = ArrayList<Item>()
        for ((part, dir) in Paths.PARTS) {
            for (live in Root.quiet("ls $dir/media_codecs*.xml 2>/dev/null").filter { it.startsWith("/") && it.endsWith(".xml") }) {
                val name = live.substringAfterLast('/')
                val bk = "${Paths.SYS_BK}/$part/$name"
                val orig = if (Root.exists(bk)) bk else live
                val tmp = "${Paths.TMP}/${part}_$name"
                val n = filterTo(c, bb, awk, orig, tmp)
                if (n > 0) items += Item(part, name, live, orig, tmp, n)
                ui.say("  $live: " + if (n > 0) "יוסרו $n מפענחי וידאו" else if (n == 0) "אין מה להסיר" else "דולג")
            }
        }
        var swRemoved = 0
        val swBk = "${Paths.SYS_BK}/swcodec.orig.xml"
        val swTmp = "${Paths.TMP}/swcodec.xml"
        val swOrig = if (Root.exists(swBk)) swBk else Paths.APEX_XML
        if (Root.exists(Paths.APEX_XML)) {
            swRemoved = filterTo(c, bb, awk, swOrig, swTmp)
            ui.say("  APEX swcodec: " + if (swRemoved > 0) "יוסרו $swRemoved מפענחי וידאו (דרך עותק מסונן + bind mount)" else "אין מה להסיר או דולג")
        } else ui.say("  ⚠ לא נמצא ${Paths.APEX_XML} — מקודדי התוכנה של גוגל לא יטופלו")

        if (items.isEmpty() && swRemoved <= 0) { ui.say("✓ אין מפענחי וידאו להסרה"); return }

        val parts = items.map { it.part }.toSet()
        if (!ui.confirm(
                "שינוי מחיצות!\n\n" +
                    "יוסרו מפענחי וידאו מ-${items.size} קבצים (מחיצות: ${parts.joinToString().ifEmpty { "—" }})" +
                    (if (swRemoved > 0) " וגם מ-APEX swcodec" else "") + ".\n\n" +
                    "לפני כל כתיבה: גיבוי מלא ל-/system/etc/vg/backup ול-/sdcard/VideoGuard/backup.\n" +
                    "שחזור: \"בטל הכל\" באפליקציה. אם האתחול נכשל 3 פעמים, מנגנון בטיחות ישחזר לבד.\n\nלהמשיך?"
            )) throw Fail("בוטל על ידי המשתמש. לא שונה כלום.")

        // ---- פתיחה לכתיבה ----
        Fs.makeWritable(parts)?.let { throw Fail(it) }
        ui.say("✓ המחיצות נפתחו לכתיבה")

        // ---- גיבוי (לא דורסים גיבוי קיים: הוא המקור) ----
        Root.run("mkdir -p '${Paths.SYS_BK}' '${Paths.SD_BK}'")
        for (x in items) {
            val bk = "${Paths.SYS_BK}/${x.part}/${x.name}"
            val sd = "${Paths.SD_BK}/${x.part}/${x.name}"
            val r = Root.run(
                "mkdir -p '${Paths.SYS_BK}/${x.part}' '${Paths.SD_BK}/${x.part}'",
                "[ -f '$bk' ] || cp -p '${x.live}' '$bk'",
                "chmod 644 '$bk'", "chcon u:object_r:system_file:s0 '$bk'",
                "cmp -s '$bk' '${x.orig}' || [ '${x.orig}' = '$bk' ]"
            )
            if (!r.ok) throw Fail("גיבוי של ${x.live} נכשל. לא כותבים.")
            if (!Root.run("cp -f '$bk' '$sd'").ok) ui.say("  ⚠ גיבוי ל-/sdcard נכשל עבור ${x.name} (הגיבוי ב-/system תקין)")
        }
        if (swRemoved > 0 && !Root.exists(swBk)) {
            if (!Root.run("cp -p '${Paths.APEX_XML}' '$swBk'", "chmod 644 '$swBk'", "chcon u:object_r:system_file:s0 '$swBk'").ok)
                throw Fail("גיבוי של קובץ ה-APEX נכשל. לא כותבים.")
            Root.run("cp -f '$swBk' '${Paths.SD_BK}/swcodec.orig.xml'")
        }
        ui.say("✓ גיבויים נוצרו")

        // ---- סימון "ממתין" לפני הכתיבה (מנגנון בטיחות) ----
        Root.run("mkdir -p /data/vg", "touch /data/vg/pending", "echo 0 > /data/vg/attempts", "rm -f /data/vg/restored")

        // ---- כתיבה (cat >: שומר הרשאות ו-SELinux context). כישלון = שחזור מיידי ----
        val written = ArrayList<Item>()
        try {
            for (x in items) {
                if (!Root.run("cat '${x.tmp}' > '${x.live}'").ok || !Root.run("cmp -s '${x.tmp}' '${x.live}'").ok)
                    throw Fail("כתיבה/אימות נכשלו עבור ${x.live}")
                written += it
                ui.say("✓ נכתב ${x.live}")
            }
            if (swRemoved > 0) {
                val dst = "${Paths.SYS_VG}/swcodec.xml"
                if (!Root.run("cp -f '$swTmp' '$dst'", "chmod 644 '$dst'", "chcon u:object_r:system_file:s0 '$dst'", "cmp -s '$swTmp' '$dst'").ok)
                    throw Fail("כתיבת $dst נכשלה")
                ui.say("✓ נכתב $dst")
            }
            val rc = asset(c, "vg_swcodec.rc"); val bg = asset(c, "boot_guard.sh")
            val ok = Root.run(
                "cp -f '${rc.absolutePath}' '${Paths.RC}'", "chmod 644 '${Paths.RC}'", "chcon u:object_r:system_file:s0 '${Paths.RC}'",
                "cp -f '${bg.absolutePath}' '${Paths.SYS_VG}/boot_guard.sh'", "chmod 755 '${Paths.SYS_VG}/boot_guard.sh'",
                "chcon u:object_r:system_file:s0 '${Paths.SYS_VG}/boot_guard.sh'"
            ).ok
            if (!ok) throw Fail("יצירת קובץ ה-rc או סקריפט הבטיחות נכשלה")
            ui.say("✓ נוצרו ${Paths.RC} וסקריפט הבטיחות")
        } catch (f: Fail) {
            ui.say("✗ ${f.message}\nמשחזר את מה שנכתב...")
            for (x in written) Root.run("cat '${Paths.SYS_BK}/${x.part}/${x.name}' > '${x.live}'")
            Root.run("rm -f /data/vg/pending '${Paths.RC}'")
            Fs.readOnlyAgain(parts)
            throw f
        }
        Root.run("sync")
        Fs.readOnlyAgain(parts)
        ui.say("✓ הסרת מפענחים הושלמה (ייכנס לתוקף באתחול)")
    }

    // ───────────────────────── התקנה כאפליקציית מערכת ─────────────────────────

    private fun installApp(c: Context, ui: Ui) {
        ui.say("▶ התקנת האפליקציה ב-/system/app")
        val ai = c.applicationInfo
        val src = ai.sourceDir
        val isSys = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        if (isSys && src.startsWith("/system/")) { ui.say("✓ כבר מותקנת כאפליקציית מערכת"); return }
        if (!ui.confirm(
                "שינוי מחיצה!\n\nה-APK יועתק ל-${Paths.APP_DIR}/VideoGuard.apk (שורד איפוס יצרן ולא תלוי ב-Magisk). " +
                    "בלי הרשאות signature/privileged, כדי שלא יהיה סיכון ללופ אתחול.\n\nלהמשיך?"
            )) throw Fail("בוטל על ידי המשתמש.")
        Fs.makeWritable(emptySet())?.let { throw Fail(it) }
        val apk = "${Paths.APP_DIR}/VideoGuard.apk"
        val ok = Root.run(
            "mkdir -p '${Paths.APP_DIR}'", "cp -f '$src' '$apk'",
            "chmod 755 '${Paths.APP_DIR}'", "chmod 644 '$apk'", "chown -R root:root '${Paths.APP_DIR}'",
            "chcon -R u:object_r:system_file:s0 '${Paths.APP_DIR}'", "sync", "cmp -s '$src' '$apk'"
        ).ok
        Fs.readOnlyAgain(emptySet())
        if (!ok) throw Fail("העתקת ה-APK ל-/system/app נכשלה")
        ui.say("✓ הותקנה ב-$apk (תהפוך לאפליקציית מערכת באתחול)")
    }

    // ───────────────────────── Device Owner ─────────────────────────

    private fun deviceOwner(c: Context, ui: Ui) {
        ui.say("▶ Device Owner והגבלות")
        Admin.becomeOwner(c)?.let { throw Fail(it) }
        ui.say("✓ Device Owner פעיל")
        Admin.applyAll(c).forEach { ui.say("  $it") }
        if (Prefs.scanOn(c)) {
            Scanner.scanAndApply(c)
            ui.say("✓ סריקה: ${Scanner.last.size} אפליקציות מזוהות " +
                if (Prefs.enforce(c)) "(אכיפה פעילה)" else "(דיווח בלבד — הפעל \"אכוף השעיה\" אחרי שבדקת את הרשימה)")
        }
    }

    // ───────────────────────── "בטל הכל" ─────────────────────────

    fun runUndo(c: Context, ui: Ui) {
        if (!Root.available()) throw Fail("אין גישת root.")
        if (!ui.confirm("לבטל הכל?\n\nיוסרו ההגבלות ו-Device Owner, יוחזרו קבצי media_codecs מהגיבוי, יימחקו קובץ ה-rc ותיקיית /system/etc/vg, והמכשיר יאותחל.")) throw Fail("בוטל.")
        ui.say("▶ שחזור קבצי המערכת")
        val backups = Paths.PARTS.flatMap { (part, dir) ->
            Root.quiet("ls '${Paths.SYS_BK}/$part'/media_codecs*.xml 2>/dev/null")
                .filter { it.endsWith(".xml") }.map { Triple(part, dir, it) }
        }
        if (backups.isNotEmpty()) {
            Fs.makeWritable(backups.map { it.first }.toSet())?.let { throw Fail(it) }
            for ((part, dir, bk) in backups) {
                val live = "$dir/${bk.substringAfterLast('/')}"
                if (!Root.run("cat '$bk' > '$live'", "cmp -s '$bk' '$live'").ok)
                    throw Fail("שחזור $live נכשל. לא מוחקים את הגיבוי.")
                ui.say("✓ שוחזר $live")
            }
        } else {
            Fs.makeWritable(emptySet())?.let { throw Fail(it) }
            ui.say("○ אין גיבויי media_codecs")
        }
        Root.run("rm -f '${Paths.RC}'", "rm -rf '${Paths.SYS_VG}'", "rm -rf /vendor/etc/vg", "rm -rf /data/vg", "sync")
        Fs.readOnlyAgain(backups.map { it.first }.toSet())
        ui.say("✓ נמחקו ${Paths.RC} ו-${Paths.SYS_VG}")

        ui.say("▶ ביטול הגבלות והסרת Device Owner")
        Admin.clearAll(c)
        Prefs.setSetupDone(c, false); Prefs.setPendingVerify(c, false)
        ui.say("✓ בוטל. מאתחל...")
        Root.run("sync", "reboot")
    }
}
