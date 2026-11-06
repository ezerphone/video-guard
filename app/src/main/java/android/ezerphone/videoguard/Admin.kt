package android.ezerphone.videoguard

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context

class VgAdmin : DeviceAdminReceiver()

/** Device Owner: הגדרה דרך root, והגבלות דרך DevicePolicyManager. */
object Admin {
    fun dpm(c: Context): DevicePolicyManager = c.getSystemService(DevicePolicyManager::class.java)
    fun comp(c: Context) = ComponentName(c, VgAdmin::class.java)
    fun isOwner(c: Context) = dpm(c).isDeviceOwnerApp(c.packageName)

    /** בדיקת תנאים (קריאה בלבד) לפני שנוגעים במחיצות. מחזיר הסבר או null. */
    fun prerequisites(c: Context): String? {
        if (isOwner(c)) return null
        val accounts = Root.quiet("dumpsys account").count { it.contains("Account {") }
        val users = Root.quiet("pm list users").count { it.contains("UserInfo{") }
        VgLog.add("Device Owner: חשבונות=$accounts משתמשים=$users")
        if (accounts > 0) return "יש $accounts חשבונות במכשיר. הסר אותם (הגדרות > חשבונות, כולל חשבונות של אפליקציות) ונסה שוב. הגדרת Device Owner נכשלת כשיש חשבונות."
        if (users > 1) return "יש יותר ממשתמש אחד במכשיר (משתמש משני, אורח, פרופיל עבודה או מרחב נוסף). הסר אותם ונסה שוב."
        return null
    }

    /** null = הצליח, אחרת הסבר. */
    fun becomeOwner(c: Context): String? {
        if (isOwner(c)) return null
        val r = Root.run("dpm set-device-owner ${c.packageName}/${VgAdmin::class.java.name}")
        if (r.ok) {
            Thread.sleep(500)
            if (isOwner(c)) return null
        }
        val t = r.text
        return when {
            t.contains("account", true) -> "יש חשבונות במכשיר. הסר אותם ונסה שוב.\n$t"
            t.contains("already set", true) || t.contains("already has", true) ->
                "כבר קיים Device Owner או Profile Owner של אפליקציה אחרת.\n$t"
            t.contains("user", true) -> "יש יותר ממשתמש אחד במכשיר, או שההגדרה הראשונית הסתיימה בצורה שחוסמת. הסר משתמשים נוספים.\n$t"
            else -> "dpm set-device-owner נכשל:\n$t"
        }
    }

    fun magiskPackages(c: Context): List<String> = try {
        val pm = c.packageManager
        pm.getInstalledApplications(0).filter {
            it.packageName != c.packageName &&
                (it.packageName.contains("magisk", true) || pm.getApplicationLabel(it).toString().equals("Magisk", true))
        }.map { it.packageName }
    } catch (_: Exception) { emptyList() }

    /** מחיל את כל ההגבלות לפי המתגים. דורש Device Owner. */
    fun applyAll(c: Context): List<String> {
        val d = dpm(c); val a = comp(c)
        val log = ArrayList<String>()
        for (r in Prefs.RESTRICTIONS) {
            try {
                if (Prefs.restr(c, r)) d.addUserRestriction(a, r.key) else d.clearUserRestriction(a, r.key)
                log += (if (Prefs.restr(c, r)) "✓ " else "○ ") + r.label
            } catch (t: Throwable) { log += "✗ ${r.label}: ${t.message}" }
        }
        try { d.setUninstallBlocked(a, c.packageName, true); log += "✓ האפליקציה חסומה להסרה" }
        catch (t: Throwable) { log += "✗ חסימת הסרה: ${t.message}" }

        val hidden = HashSet(Prefs.hiddenByUs(c))
        for (p in magiskPackages(c)) {
            try {
                d.setApplicationHidden(a, p, Prefs.hideMagisk(c))
                if (Prefs.hideMagisk(c)) hidden += p else hidden -= p
                log += (if (Prefs.hideMagisk(c)) "✓ הוסתרה: " else "○ מוצגת: ") + p
            } catch (t: Throwable) { log += "✗ $p: ${t.message}" }
        }
        Prefs.setHiddenByUs(c, hidden)
        log.forEach { VgLog.add(it) }
        return log
    }

    /** מבטל את כל ההגבלות ומסיר את ה-Device Owner. */
    @Suppress("DEPRECATION")
    fun clearAll(c: Context) {
        if (!isOwner(c)) { VgLog.add("אין Device Owner פעיל"); return }
        val d = dpm(c); val a = comp(c)
        for (r in Prefs.RESTRICTIONS) try { d.clearUserRestriction(a, r.key) } catch (_: Throwable) {}
        Scanner.releaseAll(c)
        for (p in Prefs.hiddenByUs(c)) try { d.setApplicationHidden(a, p, false) } catch (_: Throwable) {}
        Prefs.setHiddenByUs(c, emptySet())
        try { d.setUninstallBlocked(a, c.packageName, false) } catch (_: Throwable) {}
        try { d.clearDeviceOwnerApp(c.packageName); VgLog.add("Device Owner הוסר") }
        catch (t: Throwable) { VgLog.add("הסרת Device Owner נכשלה: ${t.message}") }
    }
}
