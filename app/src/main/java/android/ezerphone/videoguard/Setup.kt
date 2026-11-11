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

    /** שורת ה-mount של נקודת העגינה ("dev on mp type t (...)"). */
    private fun mountLine(mp: String): String? {
        val lines = Root.quiet("mount")
        return lines.lastOrNull { it.contains(" on $mp type ") }
            ?: if (mp == "/system") lines.lastOrNull { it.contains(" on / type ") } else null
    }

    /** שם ה-device-mapper של המחיצה, אם היא יושבת על dm-N (למשל "system-verity"). */
    private fun dmName(dev: String): String? {
        val real = Root.quiet("readlink -f '$dev'").firstOrNull()?.trim() ?: dev
        val dm = real.substringAfterLast('/').takeIf { it.startsWith("dm-") } ?: return null
        return Root.quiet("cat /sys/block/$dm/dm/name").firstOrNull()?.trim()
    }

    /** null = הכול נפתח לכתיבה, אחרת הסבר למה לא. */
    fun makeWritable(parts: Set<String>): String? {
        val slot = Root.quiet("getprop ro.boot.slot_suffix").firstOrNull()?.trim().orEmpty()
        val verity = Root.quiet("getprop ro.boot.veritymode").firstOrNull()?.trim().orEmpty()
        val all = parts + "system"
        for (p in all) {
            val l = mountLine(mountPoint(p))
            val t = l?.substringAfter(" type ")?.substringBefore(' ')
            val dev = l?.substringBefore(" on ")?.trim()
            val dm = dev?.let { dmName(it) }
            VgLog.add("מחיצה $p: מערכת קבצים=$t התקן=$dev dm=$dm slot='$slot' veritymode='$verity'")
            if (t == "erofs") return "המחיצה $p היא erofs (קריאה בלבד, אי אפשר לפתוח לכתיבה). צריך לבנות את המחיצה מחדש במחשב."
            // כתיבה מתחת ל-dm-verity פעיל משבשת את עץ ה-hash, והאתחול הבא נכשל ברמת הבלוק —
            // מצב ש-bind mount של boot_guard לא יכול לתקן. לכן עוצרים לפני שנוגעים.
            if (dm != null && dm.contains("verity", true))
                return "dm-verity פעיל על המחיצה $p ($dm). כתיבה אליה תשבש את האתחול. יש לכבות את verity במחשב (vbmeta עם --disable-verity) ולנסות שוב."
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

        // Device Owner קודם: זה השלב שהכי סביר שייכשל (חשבונות, משתמשים), ועדיף שייכשל
        // לפני ששינינו מחיצות. ההגבלות עצמן מוחלות רק בסוף.
        ui.say("▶ Device Owner")
        Admin.becomeOwner(c)?.let { throw Fail(it) }
        ui.say("✓ Device Owner פעיל")

        if (Prefs.codecsOn(c)) codecs(c, ui) else ui.say("○ הסרת מפענחים כבויה (מתג)")
        installApp(c, ui)
        restrictions(c, ui)

        // commit ולא apply: מיד אחרי זה אולי יש reboot
        Prefs.setSetupState(c, done = true, pendingVerify = true)
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

    /**
     * fresh = הקובץ החי שונה גם מהגיבוי וגם מהגרסה המסוננת של הגיבוי: הקושחה עודכנה (OTA /
     * עדכון APEX) מאז הגיבוי. במקרה כזה מסננים את הקובץ החי, והגיבוי הישן נשמר בצד.
     */
    private class Item(val part: String, val name: String, val live: String, val bk: String,
                       val tmp: String, val removed: Int, val fresh: Boolean)

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

    /**
     * מסיר bind mounts שמכסים את [path] (במצב "שוחזר" boot_guard מצמיד את הגיבוי מעל הקובץ החי,
     * ואז `cat bk > live` היה מרוקן את הגיבוי עצמו — אותו inode). המעטפת רצה במרחב ה-mount הגלובלי.
     */
    fun unbind(path: String) {
        Root.run("n=0; while [ \$n -lt 5 ] && grep -q ' $path ' /proc/self/mountinfo; do umount -l '$path' || break; n=\$((n+1)); done; true")
    }

    /** האם שני הנתיבים הם אותו קובץ (אותו inode) — הגנה אחרונה לפני כתיבה. */
    private fun sameFile(a: String, b: String) = Root.test("[ '$a' -ef '$b' ]")

    /** רשת ביטחון הופעלה בעבר (3 אתחולים כושלים) — boot_guard מצמיד את המקורות בכל אתחול. */
    fun restoredMode(): Boolean = Root.exists("/data/vg/restored")

    private fun codecs(c: Context, ui: Ui) {
        ui.say("▶ הסרת מפענחי וידאו")
        val bb = findBusybox() ?: throw Fail("לא נמצא busybox (נדרש ל-awk). ב-Magisk הוא ב-/data/adb/magisk/busybox.")
        val awk = asset(c, "strip_video_decoders.awk")
        Root.run("mkdir -p ${Paths.TMP}")

        if (restoredMode() && !ui.confirm(
                "אזהרה: מנגנון הבטיחות כבר הופעל בעבר — המכשיר לא עלה 3 פעמים אחרי הסרת המפענחים, " +
                    "והקבצים המקוריים מוצמדים מאז בכל אתחול.\n\nהפעלה מחדש עלולה לגרום שוב ללולאת אתחול. להמשיך בכל זאת?"
            )) throw Fail("בוטל על ידי המשתמש. לא שונה כלום.")

        // ---- הכנה (קריאה בלבד, חוץ מהסרת bind mounts ישנים): מה יוסר מכל קובץ ----
        val items = ArrayList<Item>()
        for ((part, dir) in Paths.PARTS) {
            for (live in Root.quiet("ls $dir/media_codecs*.xml 2>/dev/null").filter { it.startsWith("/") && it.endsWith(".xml") }) {
                unbind(live)
                val name = live.substringAfterLast('/')
                val bk = "${Paths.SYS_BK}/$part/$name"
                val tmp = "${Paths.TMP}/${part}_$name"
                var fresh = false
                var n: Int
                if (Root.exists(bk)) {
                    n = filterTo(c, bb, awk, bk, tmp)
                    // הקובץ החי חייב להיות המקור או הגרסה המסוננת שלו; אחרת הקושחה התעדכנה
                    if (n >= 0 && !Root.test("cmp -s '$live' '$bk' || cmp -s '$live' '$tmp'")) {
                        VgLog.add("⚠ $live שונה מהגיבוי — כנראה עדכון קושחה. מסננים את הקובץ החי.")
                        fresh = true
                        n = filterTo(c, bb, awk, live, tmp)
                    }
                } else {
                    fresh = true
                    n = filterTo(c, bb, awk, live, tmp)
                }
                if (n > 0) items += Item(part, name, live, bk, tmp, n, fresh)
                ui.say("  $live: " + (if (n > 0) "יוסרו $n מפענחי וידאו" else if (n == 0) "אין מה להסיר" else "דולג") +
                    if (fresh && n > 0 && Root.exists(bk)) " (הקושחה התעדכנה מאז הגיבוי)" else "")
            }
        }

        // APEX swcodec: מסירים קודם את ה-bind של העותק המסונן הישן, כדי לקרוא את הקובץ האמיתי
        // (גם אחרי עדכון APEX דרך Google Play). עד האתחול הבא המקור גלוי — האתחול מחזיר את ה-bind.
        var swRemoved = 0
        var swFresh = false
        val swBk = "${Paths.SYS_BK}/swcodec.orig.xml"
        val swTmp = "${Paths.TMP}/swcodec.xml"
        unbind(Paths.APEX_XML)
        unbind("${Paths.SYS_VG}/swcodec.xml")
        if (Root.exists(Paths.APEX_XML)) {
            swFresh = !Root.exists(swBk) || !Root.test("cmp -s '${Paths.APEX_XML}' '$swBk'")
            if (swFresh && Root.exists(swBk)) VgLog.add("⚠ APEX swcodec עודכן מאז הגיבוי — מסננים את הגרסה החדשה")
            swRemoved = filterTo(c, bb, awk, Paths.APEX_XML, swTmp)
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

        // ---- גיבוי. גיבוי קיים לא נדרס: אם הקושחה התעדכנה, הישן עובר לשם עם חותמת זמן ----
        val stamp = System.currentTimeMillis() / 1000
        Root.run("mkdir -p '${Paths.SYS_BK}' '${Paths.SD_BK}'")
        for (x in items) {
            val sd = "${Paths.SD_BK}/${x.part}/${x.name}"
            val r = Root.chain(
                "mkdir -p '${Paths.SYS_BK}/${x.part}' '${Paths.SD_BK}/${x.part}'",
                if (x.fresh) "{ [ ! -f '${x.bk}' ] || mv '${x.bk}' '${x.bk}.old-$stamp'; }" else "true",
                "{ [ -f '${x.bk}' ] || cp -p '${x.live}' '${x.bk}'; }",
                "chmod 644 '${x.bk}'", "chcon u:object_r:system_file:s0 '${x.bk}'",
                "{ ! [ '${x.bk}' -ef '${x.live}' ]; }",
                // הגיבוי חייב להיות המקור שממנו נוצר הקובץ המסונן
                "$bb awk -f '${awk.absolutePath}' '${x.bk}' > '${x.tmp}.chk'", "cmp -s '${x.tmp}.chk' '${x.tmp}'"
            )
            if (!r.ok) throw Fail("גיבוי של ${x.live} נכשל. לא כותבים.")
            // ב-sdcard: עותק ראשון נשמר לתמיד; עותק לקושחה חדשה נשמר בשם נפרד
            val sdOk = Root.chain(
                if (x.fresh) "{ [ ! -f '$sd' ] || mv -f '$sd' '$sd.old-$stamp'; }" else "true",
                "{ [ -f '$sd' ] || cp '${x.bk}' '$sd'; }"
            ).ok
            if (!sdOk) ui.say("  ⚠ גיבוי ל-/sdcard נכשל עבור ${x.name} (הגיבוי ב-/system תקין)")
        }
        if (swRemoved > 0 && swFresh) {
            if (!Root.chain(
                    "{ [ ! -f '$swBk' ] || mv '$swBk' '$swBk.old-$stamp'; }",
                    "cp -p '${Paths.APEX_XML}' '$swBk'", "chmod 644 '$swBk'", "chcon u:object_r:system_file:s0 '$swBk'"
                ).ok) throw Fail("גיבוי של קובץ ה-APEX נכשל. לא כותבים.")
            Root.run("[ -f '${Paths.SD_BK}/swcodec.orig.xml' ] && mv -f '${Paths.SD_BK}/swcodec.orig.xml' '${Paths.SD_BK}/swcodec.orig.xml.old-$stamp'; " +
                "cp -f '$swBk' '${Paths.SD_BK}/swcodec.orig.xml'")
        }
        ui.say("✓ גיבויים נוצרו")

        // ---- סימון "ממתין" לפני הכתיבה (מנגנון בטיחות). מצב "שוחזר" ישן מתבטל ----
        if (!Root.chain("mkdir -p /data/vg", "touch /data/vg/pending", "echo 0 > /data/vg/attempts", "rm -f /data/vg/restored").ok)
            throw Fail("יצירת סימון הבטיחות ב-/data/vg נכשלה. לא כותבים.")

        // ---- כתיבה (cat >: שומר הרשאות ו-SELinux context). כישלון = שחזור מיידי ----
        val written = ArrayList<Item>()
        try {
            for (x in items) {
                if (sameFile(x.bk, x.live)) throw Fail("${x.live} מכוסה עדיין על ידי הגיבוי (bind mount). לא כותבים.")
                if (!Root.chain("cat '${x.tmp}' > '${x.live}'", "cmp -s '${x.tmp}' '${x.live}'").ok)
                    throw Fail("כתיבה/אימות נכשלו עבור ${x.live}")
                written += x
                ui.say("✓ נכתב ${x.live}")
            }
            if (swRemoved > 0) {
                val dst = "${Paths.SYS_VG}/swcodec.xml"
                if (!Root.chain("cp -f '$swTmp' '$dst'", "chmod 644 '$dst'", "chcon u:object_r:system_file:s0 '$dst'", "cmp -s '$swTmp' '$dst'").ok)
                    throw Fail("כתיבת $dst נכשלה")
                ui.say("✓ נכתב $dst")
            }
            val rc = asset(c, "vg_swcodec.rc"); val bg = asset(c, "boot_guard.sh")
            val ok = Root.chain(
                "cp -f '${rc.absolutePath}' '${Paths.RC}'", "chmod 644 '${Paths.RC}'", "chcon u:object_r:system_file:s0 '${Paths.RC}'",
                "cp -f '${bg.absolutePath}' '${Paths.SYS_VG}/boot_guard.sh'", "chmod 755 '${Paths.SYS_VG}/boot_guard.sh'",
                "chcon u:object_r:system_file:s0 '${Paths.SYS_VG}/boot_guard.sh'",
                "cmp -s '${rc.absolutePath}' '${Paths.RC}'", "cmp -s '${bg.absolutePath}' '${Paths.SYS_VG}/boot_guard.sh'"
            ).ok
            if (!ok) throw Fail("יצירת קובץ ה-rc או סקריפט הבטיחות נכשלה")
            ui.say("✓ נוצרו ${Paths.RC} וסקריפט הבטיחות")
        } catch (f: Fail) {
            ui.say("✗ ${f.message}\nמשחזר את מה שנכתב...")
            for (x in written) Root.run("cat '${x.bk}' > '${x.live}'")
            Root.run("rm -f /data/vg/pending '${Paths.RC}' '${Paths.SYS_VG}/swcodec.xml'")
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
        val ok = Root.chain(
            "mkdir -p '${Paths.APP_DIR}'", "cp -f '$src' '$apk'",
            "chmod 755 '${Paths.APP_DIR}'", "chmod 644 '$apk'", "chown -R root:root '${Paths.APP_DIR}'",
            "chcon -R u:object_r:system_file:s0 '${Paths.APP_DIR}'", "sync", "cmp -s '$src' '$apk'"
        ).ok
        Fs.readOnlyAgain(emptySet())
        if (!ok) throw Fail("העתקת ה-APK ל-/system/app נכשלה")
        ui.say("✓ הותקנה ב-$apk (תהפוך לאפליקציית מערכת באתחול)")
    }

    // ───────────────────────── הגבלות וסריקה ─────────────────────────

    private fun restrictions(c: Context, ui: Ui) {
        ui.say("▶ הגבלות Device Owner")
        Admin.applyAll(c).forEach { ui.say("  $it") }
        Scanner.scanAndApply(c)
        if (Prefs.scanOn(c)) {
            ui.say("✓ סריקה: ${Scanner.last.size} אפליקציות מזוהות " +
                if (Prefs.enforce(c)) "(אכיפה פעילה)" else "(דיווח בלבד — הפעל \"אכוף השעיה\" אחרי שבדקת את הרשימה)")
        }
    }

    // ───────────────────────── "בטל הכל" ─────────────────────────

    fun runUndo(c: Context, ui: Ui) {
        if (!Root.available()) throw Fail("אין גישת root.")
        if (!ui.confirm("לבטל הכל?\n\nיוסרו ההגבלות ו-Device Owner, יוחזרו קבצי media_codecs מהגיבוי, יימחקו קובץ ה-rc ותיקיית /system/etc/vg, " +
                "האפליקציה תוסר מ-/system/app ותישאר כאפליקציה רגילה שאפשר להסיר, והמכשיר יאותחל.")) throw Fail("בוטל.")
        ui.say("▶ שחזור קבצי המערכת")
        val backups = Paths.PARTS.flatMap { (part, dir) ->
            Root.quiet("ls '${Paths.SYS_BK}/$part'/media_codecs*.xml 2>/dev/null")
                .filter { it.endsWith(".xml") }.map { Triple(part, dir, it) }
        }
        // במצב "שוחזר" הגיבוי מוצמד מעל הקובץ החי: קודם מסירים את ה-bind, אחרת cat > ירוקן את הגיבוי
        for ((_, dir, bk) in backups) unbind("$dir/${bk.substringAfterLast('/')}")
        unbind(Paths.APEX_XML)
        unbind("${Paths.SYS_VG}/swcodec.xml")

        val parts = backups.map { it.first }.toSet()
        Fs.makeWritable(parts)?.let { throw Fail(it) }
        if (backups.isEmpty()) ui.say("○ אין גיבויי media_codecs")
        val bb = findBusybox()
        val awk = asset(c, "strip_video_decoders.awk")
        for ((_, dir, bk) in backups) {
            val live = "$dir/${bk.substringAfterLast('/')}"
            if (sameFile(bk, live)) throw Fail("$live עדיין מוצמד לגיבוי (bind mount) ולא ניתן לשחזר בבטחה. אתחל ונסה שוב.")
            if (Root.test("cmp -s '$bk' '$live'")) { ui.say("✓ $live כבר מקורי"); continue }
            // משחזרים רק קובץ שהוא באמת הגרסה המסוננת של הגיבוי. אחרת הקושחה עודכנה מאז,
            // והגיבוי ישן — דריסה הייתה מחזירה קובץ מקושחה קודמת.
            val chk = "${Paths.TMP}/undo.chk"
            if (bb != null && !Root.test("mkdir -p ${Paths.TMP} && $bb awk -f '${awk.absolutePath}' '$bk' > '$chk' && cmp -s '$chk' '$live'")) {
                ui.say("⚠ $live לא תואם לגיבוי (כנראה עדכון קושחה) — לא נוגעים בו")
                continue
            }
            if (!Root.chain("cat '$bk' > '$live'", "cmp -s '$bk' '$live'").ok) {
                Fs.readOnlyAgain(parts)
                throw Fail("שחזור $live נכשל. לא מוחקים את הגיבוי.")
            }
            ui.say("✓ שוחזר $live")
        }

        // האפליקציה עצמה: עותק ל-/data/local/tmp לפני המחיקה מ-/system, והתקנה כאפליקציה רגילה
        // ממש לפני האתחול (ההתקנה הורגת את התהליך שלנו).
        val ai = c.applicationInfo
        val inSystem = ai.sourceDir.startsWith("${Paths.APP_DIR}/")
        val apkTmp = "${Paths.TMP}/VideoGuard.apk"
        if (inSystem && !Root.chain("mkdir -p ${Paths.TMP}", "cp -f '${ai.sourceDir}' '$apkTmp'", "chmod 644 '$apkTmp'").ok) {
            Fs.readOnlyAgain(parts)
            throw Fail("העתקת ה-APK לפני ההסרה מ-/system נכשלה. לא מוחקים.")
        }

        Root.run("rm -f '${Paths.RC}'", "rm -rf '${Paths.SYS_VG}'", "rm -rf '${Paths.APP_DIR}'", "rm -rf /vendor/etc/vg", "rm -rf /data/vg", "sync")
        Fs.readOnlyAgain(parts)
        ui.say("✓ נמחקו ${Paths.RC}, ${Paths.SYS_VG} ו-${Paths.APP_DIR}")
        Guard.showCastIcon()

        ui.say("▶ ביטול הגבלות והסרת Device Owner")
        Admin.clearAll(c)
        Prefs.setSetupState(c, done = false, pendingVerify = false)
        Prefs.setRestoredMode(c, false)
        ui.say("✓ בוטל. מאתחל...")
        if (inSystem) {
            // רץ במעטפת מנותקת: pm install סוגר את האפליקציה, והאתחול חייב לקרות בכל זאת
            Root.run("setsid sh -c \"pm install -r -d '$apkTmp' >/dev/null 2>&1; sync; reboot\" </dev/null >/dev/null 2>&1 &")
        } else Root.run("sync", "reboot")
    }
}
