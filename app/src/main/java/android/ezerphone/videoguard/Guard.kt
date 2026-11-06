package android.ezerphone.videoguard

import android.content.Context

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
        val cur = Root.quiet("settings get secure icon_blacklist").firstOrNull()?.trim().orEmpty()
        val v = if (cur == "null") "" else cur
        if (!(",$v,").contains(",cast,")) {
            Root.run("settings put secure icon_blacklist \"${if (v.isNotEmpty()) "$v," else ""}cast\"")
        }
    }

    fun startVisual(c: Context) {
        if (CaptureService.running && CaptureService.hasProjection) return
        // טריגר יחיד (RequestActivity עם מנעול בקשה), כדי שלא ייווצרו שתי בקשות הקרנה
        Root.run("am start --user 0 -n ${c.packageName}/.RequestActivity")
    }

    /** רץ בכל אתחול (BootReceiver), ברקע. */
    fun onBoot(c: Context) {
        VgLog.add("== אתחול ==")
        if (Root.available()) {
            grantOps(c)
            hideCastIcon()
            if (Prefs.visualOn(c)) startVisual(c)
        } else VgLog.add("אין root — הרשאות והפעלה אוטומטית לא בוצעו")
        if (Prefs.scanOn(c)) Scanner.scanAndApply(c)
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
