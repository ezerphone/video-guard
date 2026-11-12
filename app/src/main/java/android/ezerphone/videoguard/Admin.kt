package android.ezerphone.videoguard

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.UserManager

class AdminReceiver : DeviceAdminReceiver()

/**
 * Device Owner: חוסם את הסרת האפליקציה, מבצע את ההשעיות (כך שאי אפשר לבטל אותן ב-adb),
 * ומפעיל את הנעילות שנבחרו. הופכים ל-Device Owner דרך root (`dpm set-device-owner`), וזה דורש
 * שלא יהיו חשבונות במכשיר ולא משתמשים נוספים.
 */
object Admin {
    private fun dpm(c: Context) = c.getSystemService(DevicePolicyManager::class.java)
    private fun admin(c: Context) = ComponentName(c, AdminReceiver::class.java)

    fun isOwner(c: Context) = try { dpm(c).isDeviceOwnerApp(c.packageName) } catch (_: Exception) { false }

    /** מפעיל את ההגנה. נכשל ב-[Fail] עם הסבר פשוט. */
    fun protect(c: Context, ui: Ui) {
        if (!isOwner(c)) {
            if (!Root.available()) throw Fail("אין גישת root", Fail.NO_ROOT)
            ui.say("▶ הופך ל-Device Owner")
            val r = Root.run("dpm set-device-owner ${c.packageName}/.AdminReceiver")
            if (!isOwner(c)) {
                val t = r.text
                throw when {
                    t.contains("account", true) -> Fail(t,
                        "יש חשבונות בטלפון.\nהסר אותם בהגדרות > חשבונות ונסה שוב. אפשר להוסיף אותם בחזרה אחרי ההפעלה.")
                    t.contains("user", true) -> Fail(t,
                        "יש בטלפון משתמשים נוספים או פרופיל עבודה.\nהסר אותם ונסה שוב.")
                    else -> Fail(t, "לא הצלחנו להפעיל את ההגנה.\nלא בוצע שום שינוי בטלפון.")
                }
            }
            ui.say("✓ Device Owner פעיל")
        }
        applyLocks(c).forEach { ui.say("  $it") }
        // השעיות שנעשו קודם דרך root עוברות ל-Device Owner
        val ours = Prefs.suspended(c)
        if (ours.isNotEmpty()) setSuspended(c, ours.toList(), true)
    }

    /** מחיל את חסימת ההסרה ואת שלושת המתגים. מחזיר שורות סטטוס. */
    fun applyLocks(c: Context): List<String> {
        if (!isOwner(c)) return listOf("○ אין Device Owner — הנעילות לא הוחלו")
        val d = dpm(c); val a = admin(c)
        val out = ArrayList<String>()
        try { d.setUninstallBlocked(a, c.packageName, true); out += "✓ הסרת האפליקציה חסומה" }
        catch (t: Throwable) { out += "✗ חסימת הסרה: ${t.message}" }
        fun r(key: String, on: Boolean, label: String) {
            try {
                if (on) d.addUserRestriction(a, key) else d.clearUserRestriction(a, key)
                out += (if (on) "✓ " else "○ ") + label + (if (on) " — חסום" else " — מותר")
            } catch (t: Throwable) { out += "✗ $label: ${t.message}" }
        }
        r(UserManager.DISALLOW_SAFE_BOOT, Prefs.lockSafeBoot(c), "מצב בטוח")
        r(UserManager.DISALLOW_FACTORY_RESET, Prefs.lockFactoryReset(c), "איפוס יצרן מההגדרות")
        r(UserManager.DISALLOW_DEBUGGING_FEATURES, Prefs.lockAdb(c), "ADB ואפשרויות מפתח")
        out.forEach { VgLog.add(it) }
        return out
    }

    /** מסיר את כל ההגנה: משחרר את ההשעיות, מבטל נעילות וחסימת הסרה, ומסיר את Device Owner. */
    fun unprotect(c: Context, ui: Ui) {
        val ours = Prefs.suspended(c)
        if (ours.isNotEmpty()) {
            Scanner.unsuspend(c, ours)
            ui.say("✓ שוחררו ${ours.size} אפליקציות מושעות")
        }
        if (!isOwner(c)) { ui.say("○ האפליקציה אינה Device Owner"); return }
        val d = dpm(c); val a = admin(c)
        for (k in listOf(UserManager.DISALLOW_SAFE_BOOT, UserManager.DISALLOW_FACTORY_RESET, UserManager.DISALLOW_DEBUGGING_FEATURES))
            try { d.clearUserRestriction(a, k) } catch (_: Throwable) {}
        try { d.setUninstallBlocked(a, c.packageName, false) } catch (_: Throwable) {}
        @Suppress("DEPRECATION")
        try { d.clearDeviceOwnerApp(c.packageName) } catch (t: Throwable) { ui.say("✗ הסרת Device Owner: ${t.message}") }
        ui.say(if (isOwner(c)) "✗ Device Owner עדיין פעיל" else "✓ ההגנה הוסרה. אפשר להסיר את האפליקציה כרגיל.")
    }

    /** מחזיר את החבילות שנכשלו. */
    fun setSuspended(c: Context, pkgs: List<String>, on: Boolean): List<String> = try {
        dpm(c).setPackagesSuspended(admin(c), pkgs.toTypedArray(), on).toList()
    } catch (t: Throwable) {
        VgLog.add("השעיה דרך Device Owner נכשלה: ${t.message}")
        pkgs
    }
}
