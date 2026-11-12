package android.ezerphone.videoguard

import android.content.Context
import android.media.MediaCodecList

/** ממשק המסך שמריץ פעולה ארוכה (TaskActivity). */
interface Ui {
    fun say(s: String)
    /** שואל את המשתמש וחוסם עד תשובה. מסך שנסגר = "לא". */
    fun confirm(msg: String): Boolean
}

/**
 * כישלון של פעולה. [message] = הפירוט הטכני (נכתב ליומן). [friendly] = מה שהמשתמש רואה.
 */
class Fail(msg: String, val friendly: String = NO_CHANGE) : Exception(msg) {
    companion object {
        const val NO_CHANGE = "לא בוצע שום שינוי בטלפון.\nאפשר לנסות שוב מאוחר יותר."
        const val NO_ROOT = "אין הרשאת root. אשר את הבקשה של Magisk ונסה שוב."
        const val HALFWAY = "הפעולה נעצרה באמצע.\nלפני הפעלה מחדש של הטלפון, היכנס להגדרות ובחר \"החזר את נגני הווידאו\"."
        const val RESTORE_HALFWAY = "החזרת נגני הווידאו לא הושלמה.\nאל תפעיל מחדש את הטלפון — נסה שוב."
        const val NOT_WRITABLE = "אי אפשר לשנות את קבצי המערכת בטלפון הזה.\nלא בוצע שום שינוי."
    }
}

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
    /** נכתב רק בסוף כתיבה מוצלחת; קריא גם בלי root. */
    const val MARK = "$DIR/applied"
    const val APEX_XML = "/apex/com.android.media.swcodec/etc/media_codecs.xml"
    private val PARTS = listOf("vendor", "odm", "product", "system_ext", "system")


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

    /** מחזיר true אם נכתב שינוי (צריך אתחול), false אם לא היה מה למחוק. */
    fun remove(c: Context, ui: Ui): Boolean {
        if (!Root.available()) throw Fail("אין גישת root", Fail.NO_ROOT)

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
        if (todo.isEmpty() && swFiltered == null) { ui.say("✓ לא נמצאו מפענחי וידאו למחיקה"); return false }

        val mps = todo.map { it.mp }.toSet() + mountPointOf("/system/etc")
        ui.say("יימחקו $total מפענחי וידאו מ-${todo.size} קבצים" + (if (swFiltered != null) " ומ-APEX swcodec" else ""))
        var changed = false
        try {
            // בתוך ה-try: אם רק חלק מהמחיצות נפתחו, ה-finally מחזיר אותן לקריאה בלבד
            Fs.makeWritable(mps)?.let { throw Fail(it, Fail.NOT_WRITABLE) }
            ui.say("✓ המחיצות פתוחות לכתיבה")
            for (x in todo) if (!Root.exists(x.bk)) {
                if (!Root.run("mkdir -p '${x.bk.substringBeforeLast('/')}'").ok || !Root.writeText(c, x.bk, x.orig))
                    throw Fail("גיבוי של ${x.live} נכשל. לא נכתב כלום.")
            }
            ui.say("✓ גיבוי נוצר")
            changed = write(c, ui, todo, swFiltered)
        } finally {
            Root.run("sync")
            Fs.readOnlyAgain(mps)
        }
        ui.say(if (changed) "✓ הושלם. השינוי ייכנס לתוקף אחרי אתחול." else "✓ הכול כבר מסונן")
        return changed
    }

    /** מחזיר true אם משהו השתנה בפועל (צריך אתחול). */
    private fun write(c: Context, ui: Ui, items: List<Item>, swFiltered: String?): Boolean {
        val written = ArrayList<Item>()
        // הפעלה קודמת שהצליחה: אם הפעם משהו נכשל, לא מוחקים את מה שהיא יצרה
        val hadRun = Root.exists(MARK)
        var changed = false
        try {
            for (x in items) {
                if (x.liveText == x.filtered) { ui.say("✓ ${x.live} כבר מסונן"); continue }
                // נכנס לרשימה לפני הכתיבה: כתיבה שנכשלה באמצע משאירה קובץ ריק או חלקי, וגם אותו צריך להחזיר
                written += x
                if (!Root.writeText(c, x.live, x.filtered)) throw Fail("הכתיבה ל-${x.live} נכשלה")
                changed = true
                ui.say("✓ נכתב ${x.live}")
            }
            if (swFiltered != null) {
                val rc = c.assets.open("videoguard.rc").bufferedReader().use { it.readText() }
                if (Root.readText(SW) != swFiltered || Root.readText(RC) != rc) {
                    if (!Root.run("mkdir -p '$DIR'").ok || !Root.writeText(c, SW, swFiltered)) throw Fail("כתיבת $SW נכשלה")
                    if (!Root.writeText(c, RC, rc)) throw Fail("יצירת $RC נכשלה")
                    changed = true
                }
            }
            if (!Root.run("mkdir -p '$DIR'", "touch '$MARK'").ok) throw Fail("יצירת $MARK נכשלה")
            Root.run(
                "find '$DIR' -type d -exec chmod 755 {} +", "find '$DIR' -type f -exec chmod 644 {} +",
                "chcon -R u:object_r:system_file:s0 '$DIR'", "[ -f '$RC' ] && chmod 644 '$RC' && chcon u:object_r:system_file:s0 '$RC'; true"
            )
        } catch (f: Fail) {
            ui.say("✗ ${f.message}\nמחזיר את מה שנכתב...")
            var allBack = true
            for (x in written) {
                val ok = Root.writeText(c, x.live, x.orig)
                if (!ok) allBack = false
                ui.say(if (ok) "✓ הוחזר ${x.live}" else "✗ החזרת ${x.live} נכשלה! הגיבוי ב-${x.bk}")
            }
            if (!hadRun) Root.run("rm -f '$RC' '$SW' '$MARK'")
            throw if (allBack) f else Fail(f.message ?: "", Fail.HALFWAY)
        }
        return changed
    }

    // ───────────────────────── שחזור ─────────────────────────

    /** מחזיר false אם לא היה מה לשחזר. */
    fun restore(c: Context, ui: Ui): Boolean {
        if (!Root.available()) throw Fail("אין גישת root", Fail.NO_ROOT)
        val bks = Root.quiet("find '$BK' -type f -name 'media_codecs*.xml' 2>/dev/null").filter { it.startsWith("$BK/") }
        if (bks.isEmpty() && !Root.exists(RC) && !Root.exists(MARK)) { ui.say("○ אין מה לשחזר (לא נמצא גיבוי)"); return false }

        val mps = bks.map { mountPointOf(it.removePrefix(BK).substringBeforeLast('/')) }.toSet() + mountPointOf("/system/etc")
        try {
            Fs.makeWritable(mps)?.let { throw Fail(it, Fail.NOT_WRITABLE) }
            for (bk in bks) {
                val live = bk.removePrefix(BK)
                val orig = Root.readText(bk) ?: throw Fail("קריאת הגיבוי $bk נכשלה. לא ממשיכים.", Fail.RESTORE_HALFWAY)
                if (Root.readText(live) == orig) { ui.say("✓ $live כבר מקורי"); continue }
                if (!Root.writeText(c, live, orig)) throw Fail("שחזור $live נכשל. הגיבוי לא נמחק.", Fail.RESTORE_HALFWAY)
                ui.say("✓ שוחזר $live")
            }
            Root.run("rm -f '$RC'", "rm -rf '$DIR'")
            ui.say("✓ נמחקו $RC ו-$DIR")
        } finally {
            Root.run("sync")
            Fs.readOnlyAgain(mps)
        }
        ui.say("✓ הושלם. המפענחים יחזרו אחרי אתחול.")
        return true
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
            if (l.getOrNull(2) == "ext4") releaseReserve(dev)
        }
        return null
    }

    /**
     * מחיצות המערכת בנויות מלאות (0 מקום פנוי), ו-ext4 שומר עתודה (reserved_clusters, עד 2% / 4096
     * בלוקים) שאסורה לכתיבה רגילה — גם ל-root. לכן כל כתיבה ל-vendor נכשלת ב-"No space left on device",
     * גם אחרי שהקובץ התרוקן. לזמן הכתיבה העתודה מאופסת, ו-[readOnlyAgain] מחזיר אותה.
     */
    private val savedReserve = LinkedHashMap<String, String>()

    private fun releaseReserve(dev: String) {
        val name = Root.quiet("readlink -f '$dev'").firstOrNull()?.trim()?.substringAfterLast('/') ?: return
        val sys = "/sys/fs/ext4/$name/reserved_clusters"
        if (sys in savedReserve) return
        val v = Root.quiet("cat '$sys' 2>/dev/null").firstOrNull()?.trim() ?: return
        if (v.toLongOrNull() == null || v == "0") return
        if (Root.run("echo 0 > '$sys'").ok) { savedReserve[sys] = v; VgLog.add("עתודת ext4 ב-$name: $v → 0 (זמנית)") }
    }

    fun readOnlyAgain(mps: Set<String>) {
        for ((sys, v) in savedReserve) Root.run("echo $v > '$sys'")
        savedReserve.clear()
        for (mp in mps) Root.run("mount -o remount,ro '$mp'")
    }
}
