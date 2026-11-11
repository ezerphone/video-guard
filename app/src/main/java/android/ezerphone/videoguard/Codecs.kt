package android.ezerphone.videoguard

import android.content.Context
import android.media.MediaCodecList

/** ממשק המסך שמריץ פעולה ארוכה (TaskActivity). */
interface Ui {
    fun say(s: String)
    /** שואל את המשתמש וחוסם עד תשובה. מסך שנסגר = "לא". */
    fun confirm(msg: String): Boolean
}

class Fail(msg: String) : Exception(msg)

/**
 * מחיקת מפענחי וידאו בכתיבה ישירה למחיצות, ושחזור.
 *
 * - כל קובץ media_codecs*.xml בתיקיות etc של vendor/odm/product/system_ext/system מסונן ב-[CodecFilter].
 * - APEX swcodec (מפענחי התוכנה של גוגל) הוא תמונה לקריאה בלבד: עותק מסונן נשמר ב-/system ומולבש
 *   (bind mount) על המקור בכל אתחול, מקובץ ה-rc שלנו.
 * - גיבוי של המקור ב-[BK] (לפי הנתיב המלא של כל קובץ), לשחזור. גיבוי קיים לא נדרס.
 */
object Codecs {
    const val DIR = "/system/etc/videoguard"
    const val BK = "$DIR/backup"
    const val SW = "$DIR/swcodec.xml"
    const val RC = "/system/etc/init/videoguard.rc"
    const val APEX_XML = "/apex/com.android.media.swcodec/etc/media_codecs.xml"
    private val PARTS = listOf("vendor", "odm", "product", "system_ext", "system")

    fun applied() = Root.exists(RC)

    /** מפענחי הווידאו שאפליקציות רואות בפועל (מה שהמערכת מחזירה ל-MediaCodecList). בלי root. */
    fun visibleVideoDecoders(): List<String> = try {
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .filter { !it.isEncoder && it.supportedTypes.any { t -> t.startsWith("video/", true) } }
            .map { it.name }
    } catch (_: Throwable) { emptyList() }

    // ───────────────────────── מחיקה ─────────────────────────

    private class Item(val live: String, val bk: String, val mp: String, val orig: String, val liveText: String) {
        var filtered = ""
    }

    fun remove(c: Context, ui: Ui) {
        if (!Root.available()) throw Fail("אין גישת root. אשר את הבקשה ב-Magisk ונסה שוב.")

        ui.say("▶ קורא את קבצי המפענחים")
        val lives = listLiveFiles()
        if (lives.isEmpty()) throw Fail("לא נמצאו קבצי media_codecs*.xml.")
        val items = ArrayList<Item>()
        for (live in lives) {
            val liveText = Root.readText(live) ?: throw Fail("קריאת $live נכשלה.")
            // אם כבר יש גיבוי (הפעלה קודמת), הוא המקור
            val orig = Root.readText(BK + live) ?: liveText
            items += Item(live, BK + live, mountPointOf(live.substringBeforeLast('/')), orig, liveText)
        }
        // הקובץ האמיתי של ה-APEX (אם העותק המסונן שלנו כבר מולבש עליו — מסירים אותו כדי לקרוא את המקור)
        unbind(APEX_XML)
        val apexText = Root.readText(APEX_XML)
        if (apexText == null) ui.say("  ⚠ לא נמצא $APEX_XML — מפענחי התוכנה של גוגל לא יטופלו")

        // שמות כל מפענחי הווידאו מכל הקבצים, כדי לסנן גם עדכונים (update="true") שלהם בקבצים אחרים
        val names = HashSet<String>()
        items.forEach { names += CodecFilter.videoDecoderNames(it.orig) }
        apexText?.let { names += CodecFilter.videoDecoderNames(it) }

        var total = 0
        val todo = ArrayList<Item>()
        for (x in items) {
            if (!CodecFilter.isWellFormed(x.orig)) { ui.say("  ⚠ ${x.live} אינו XML תקין — לא נוגעים בו"); continue }
            val r = CodecFilter.strip(x.orig, names)
            if (r.removed.isEmpty()) continue
            CodecFilter.verify(x.orig, r.text, names)?.let { throw Fail("בדיקת הסינון של ${x.live} נכשלה: $it. לא נכתב כלום.") }
            x.filtered = r.text; total += r.removed.size; todo += x
            ui.say("  ${x.live}: ${r.removed.size} מפענחי וידאו")
        }
        var swFiltered: String? = null
        if (apexText != null) {
            val r = CodecFilter.strip(apexText, names)
            if (r.removed.isNotEmpty()) {
                CodecFilter.verify(apexText, r.text, names)?.let { throw Fail("בדיקת הסינון של קובץ ה-APEX נכשלה: $it. לא נכתב כלום.") }
                swFiltered = r.text; total += r.removed.size
                ui.say("  APEX swcodec: ${r.removed.size} מפענחי וידאו (דרך עותק מסונן)")
            }
        }
        if (todo.isEmpty() && swFiltered == null) { ui.say("✓ לא נמצאו מפענחי וידאו למחיקה"); return }

        val mps = todo.map { it.mp }.toSet() + mountPointOf("/system/etc")
        if (!ui.confirm(
                "יימחקו $total מפענחי וידאו מ-${todo.size} קבצים" + (if (swFiltered != null) " ומ-APEX swcodec" else "") + ".\n\n" +
                    "המחיצות ייפתחו לכתיבה: ${mps.joinToString()}.\n" +
                    "לפני הכתיבה המקור נשמר ב-$BK, ואפשר להחזיר אותו בכפתור \"שחזר מפענחים\".\n\nלהמשיך?"
            )) throw Fail("בוטל. לא שונה כלום.")

        Fs.makeWritable(mps)?.let { throw Fail(it) }
        ui.say("✓ המחיצות פתוחות לכתיבה")
        try {
            for (x in todo) if (!Root.exists(x.bk)) {
                if (!Root.run("mkdir -p '${x.bk.substringBeforeLast('/')}'").ok || !Root.writeText(c, x.bk, x.orig))
                    throw Fail("גיבוי של ${x.live} נכשל. לא נכתב כלום.")
            }
            ui.say("✓ גיבוי נוצר")
            write(c, ui, todo, swFiltered)
        } finally {
            Root.run("sync")
            Fs.readOnlyAgain(mps)
        }
        ui.say("✓ הושלם. השינוי ייכנס לתוקף אחרי אתחול.")
        if (ui.confirm("לאתחל עכשיו?")) reboot()
    }

    private fun write(c: Context, ui: Ui, items: List<Item>, swFiltered: String?) {
        val written = ArrayList<Item>()
        try {
            for (x in items) {
                if (x.liveText == x.filtered) { ui.say("✓ ${x.live} כבר מסונן"); continue }
                if (!Root.writeText(c, x.live, x.filtered)) throw Fail("הכתיבה ל-${x.live} נכשלה")
                written += x
                ui.say("✓ נכתב ${x.live}")
            }
            if (swFiltered != null) {
                if (!Root.run("mkdir -p '$DIR'").ok || !Root.writeText(c, SW, swFiltered)) throw Fail("כתיבת $SW נכשלה")
                val rc = c.assets.open("videoguard.rc").bufferedReader().use { it.readText() }
                if (!Root.writeText(c, RC, rc)) throw Fail("יצירת $RC נכשלה")
            }
            Root.run(
                "find '$DIR' -type d -exec chmod 755 {} +", "find '$DIR' -type f -exec chmod 644 {} +",
                "chcon -R u:object_r:system_file:s0 '$DIR'", "[ -f '$RC' ] && chmod 644 '$RC' && chcon u:object_r:system_file:s0 '$RC'; true"
            )
        } catch (f: Fail) {
            ui.say("✗ ${f.message}\nמחזיר את מה שנכתב...")
            for (x in written) Root.writeText(c, x.live, x.orig)
            Root.run("rm -f '$RC' '$SW'")
            throw f
        }
    }

    // ───────────────────────── שחזור ─────────────────────────

    fun restore(c: Context, ui: Ui) {
        if (!Root.available()) throw Fail("אין גישת root.")
        val bks = Root.quiet("find '$BK' -type f -name 'media_codecs*.xml' 2>/dev/null").filter { it.startsWith("$BK/") }
        if (bks.isEmpty() && !Root.exists(RC)) { ui.say("○ אין מה לשחזר (לא נמצא גיבוי)"); return }
        if (!ui.confirm("לשחזר את מפענחי הווידאו?\n\nהקבצים המקוריים יחזרו מהגיבוי, ואחר כך צריך לאתחל.")) throw Fail("בוטל.")

        val mps = bks.map { mountPointOf(it.removePrefix(BK).substringBeforeLast('/')) }.toSet() + mountPointOf("/system/etc")
        Fs.makeWritable(mps)?.let { throw Fail(it) }
        try {
            for (bk in bks) {
                val live = bk.removePrefix(BK)
                val orig = Root.readText(bk) ?: throw Fail("קריאת הגיבוי $bk נכשלה. לא ממשיכים.")
                if (Root.readText(live) == orig) { ui.say("✓ $live כבר מקורי"); continue }
                if (!Root.writeText(c, live, orig)) throw Fail("שחזור $live נכשל. הגיבוי לא נמחק.")
                ui.say("✓ שוחזר $live")
            }
            Root.run("rm -f '$RC'", "rm -rf '$DIR'")
            ui.say("✓ נמחקו $RC ו-$DIR")
        } finally {
            Root.run("sync")
            Fs.readOnlyAgain(mps)
        }
        ui.say("✓ הושלם. המפענחים יחזרו אחרי אתחול.")
        if (ui.confirm("לאתחל עכשיו?")) reboot()
    }

    // ───────────────────────── עזר ─────────────────────────

    /** כל קבצי media_codecs*.xml, לפי הנתיב האמיתי (odm הוא לפעמים קישור ל-vendor/odm). */
    private fun listLiveFiles(): List<String> {
        val out = LinkedHashSet<String>()
        for (p in PARTS) {
            val dir = Root.quiet("readlink -f /$p/etc").firstOrNull()?.trim()
            if (dir.isNullOrEmpty() || !dir.startsWith("/")) continue
            Root.quiet("ls '$dir'/media_codecs*.xml 2>/dev/null").filter { it.startsWith("/") && it.endsWith(".xml") }.forEach { out += it }
        }
        return out.toList()
    }

    private fun unbind(path: String) {
        var n = 0
        while (n++ < 5 && Root.test("cut -d' ' -f5 /proc/self/mountinfo | grep -qxF '$path'"))
            if (!Root.run("umount -l '$path'").ok) break
    }

    /** נקודת העגינה של תיקייה: הארוכה ביותר מטבלת ה-mount שהיא תחילית שלה. */
    private fun mountPointOf(dir: String): String {
        val mps = Root.quiet("cut -d' ' -f2 /proc/self/mounts")
        return mps.filter { dir == it || dir.startsWith(if (it == "/") "/" else "$it/") }
            .maxByOrNull { it.length } ?: "/"
    }

    fun reboot() { Root.run("sync", "svc power reboot || reboot") }
}

/** פתיחת מחיצות לכתיבה וסגירה. */
object Fs {
    /** null = הכול פתוח לכתיבה, אחרת הסבר. */
    fun makeWritable(mps: Set<String>): String? {
        val mounts = Root.quiet("cat /proc/self/mounts")
        for (mp in mps) {
            val l = mounts.lastOrNull { it.split(' ').getOrNull(1) == mp }?.split(' ') ?: return "לא נמצאה נקודת העגינה $mp"
            val dev = l[0]
            VgLog.add("מחיצה $mp: התקן=$dev סוג=${l.getOrNull(2)}")
            Root.run("blockdev --setrw '$dev'")
            val r = Root.run("mount -o remount,rw '$mp'")
            if (!r.ok) return when {
                l.getOrNull(2) == "erofs" -> "המחיצה $mp היא erofs (קריאה בלבד מטבעה). אי אפשר לכתוב אליה מהטלפון."
                r.text.contains("shared", true) -> "המחיצה $mp משתמשת ב-shared_blocks ואי אפשר לפתוח אותה לכתיבה."
                else -> "פתיחת $mp לכתיבה נכשלה:\n${r.text}"
            }
        }
        return null
    }

    fun readOnlyAgain(mps: Set<String>) { for (mp in mps) Root.run("mount -o remount,ro '$mp'") }
}
