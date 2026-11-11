package android.ezerphone.videoguard

import android.content.Context
import android.content.Intent

/** מה שמודול Magisk הישן עשה ב-service.sh: הרשאות, הסתרת סמל ההטלה, והפעלת ההגנה באתחול. */
object Guard {
    /** PROJECT_MEDIA: אישור הקרנה אוטומטי. GET_USAGE_STATS: האפליקציה בחזית. SYSTEM_ALERT_WINDOW: חלון החסימה. */
    fun grantOps(c: Context) {
        val p = c.packageName
        Root.run(
            "appops set $p PROJECT_MEDIA allow",
            "appops set $p GET_USAGE_STATS allow",
            "appops set $p SYSTEM_ALERT_WINDOW allow",
            "pm grant $p android.permission.POST_NOTIFICATIONS"
        )
    }

    /** מוסיף cast ל-icon_blacklist בלי לדרוס את הערכים הקיימים. */
    fun hideCastIcon() {
        val cur = iconBlacklist()
        if ("cast" !in cur) Root.run("settings put secure icon_blacklist \"${(cur + "cast").joinToString(",")}\"")
    }

    /** "בטל הכל": מסיר רק את cast שהוספנו, ומשאיר את שאר הערכים. */
    fun showCastIcon() {
        val cur = iconBlacklist()
        if ("cast" !in cur) return
        val rest = cur - "cast"
        Root.run(if (rest.isEmpty()) "settings delete secure icon_blacklist"
            else "settings put secure icon_blacklist \"${rest.joinToString(",")}\"")
    }

    private fun iconBlacklist(): List<String> {
        val v = Root.quiet("settings get secure icon_blacklist").firstOrNull()?.trim().orEmpty()
        return if (v == "null") emptyList() else v.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * מפעיל את השירות. בלי הרשאת צילום השירות עדיין חוסם מיד את אפליקציות הווידאו המוכרות,
     * ומבקש את ההרשאה בעצמו ברגע שהמסך דלוק ולא נעול (וגם ב-USER_PRESENT, ראה VgApp).
     * קודם: הבקשה נשלחה רק פעם אחת באתחול, נדחתה כי המסך היה נעול, ואף אחד לא ניסה שוב.
     */
    fun startVisual(c: Context) {
        if (!Prefs.visualOn(c)) return
        if (CaptureService.running) {
            // root עוקף את מגבלת הפעלת מסכים מהרקע; RequestActivity יוצא לבד אם המסך נעול
            if (!CaptureService.hasProjection) Root.run("am start --user 0 -n ${c.packageName}/.RequestActivity")
            return
        }
        try {
            c.startForegroundService(Intent(c, CaptureService::class.java))
        } catch (t: Throwable) {
            // הפעלה מהרקע נחסמה — root עוקף את המגבלה
            VgLog.add("הפעלת השירות נכשלה (${t.javaClass.simpleName}), מנסה דרך root")
            Root.run("am start-foreground-service --user 0 -n ${c.packageName}/.CaptureService")
        }
    }

    /** רץ בכל אתחול (BootReceiver), ברקע. */
    fun onBoot(c: Context) {
        VgLog.add("== אתחול ==")
        // קודם השירות: חלון הפטור להפעלת שירות קדמי אחרי BOOT_COMPLETED קצר, ופקודות ה-root לוקחות זמן
        startVisual(c)
        if (Root.available()) {
            grantOps(c)
            hideCastIcon()
            startVisual(c)
            val restored = Root.exists("/data/vg/restored")
            Prefs.setRestoredMode(c, restored)
            if (restored) VgLog.add("⚠ מנגנון הבטיחות פעיל: קבצי המפענחים המקוריים מוצמדים (3 אתחולים כושלים בעבר)")
        } else VgLog.add("אין root — הרשאות והפעלה אוטומטית לא בוצעו")
        Scanner.scanAndApply(c)
        if (Prefs.pendingVerify(c)) {
            Verify.run(c)
            Prefs.setPendingVerify(c, false)
        }
    }
}

/** בדיקת שלמות: מזהה גם מצב אחרי איפוס יצרן (נשארו האפליקציה והמפענחים, נמחקו ה-appops וה-Device Owner). */
object Integrity {
    fun check(c: Context): List<String> {
        val out = ArrayList<String>()
        val isSystemApp = (c.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
        val expected = Prefs.setupDone(c)
        if (!expected && !isSystemApp) return out          // עוד לא הוגדר
        if (!expected && isSystemApp) {
            out += "האפליקציה מותקנת כאפליקציית מערכת אבל אין רישום של הגדרה — כנראה בוצע איפוס יצרן."
        }
        if (Prefs.restoredMode(c)) out += "מנגנון הבטיחות הופעל: המכשיר לא עלה 3 פעמים אחרי הסרת המפענחים, והקבצים המקוריים מוצמדים בכל אתחול. המפענחים לא מוסרים כרגע."
        if (!Admin.isOwner(c)) out += "האפליקציה אינה Device Owner."
        if (Prefs.visualOn(c)) {
            if (!hasUsage(c)) out += "חסרה הרשאת גישה לנתוני שימוש."
            if (!android.provider.Settings.canDrawOverlays(c)) out += "חסרה הרשאת הצגה מעל אפליקציות אחרות."
        }
        return out
    }

    private fun hasUsage(c: Context): Boolean = try {
        val ops = c.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), c.packageName) ==
            android.app.AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) { false }
}
