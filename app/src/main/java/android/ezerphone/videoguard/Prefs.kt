package android.ezerphone.videoguard

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.UserManager
import java.security.MessageDigest
import java.security.SecureRandom

/** הגדרות, מצבי אפליקציות וקוד הגנה (PIN מגובב עם מלח). */
object Prefs {
    private fun sp(c: Context) =
        c.applicationContext.getSharedPreferences("videoguard", Context.MODE_PRIVATE)

    private fun bool(c: Context, k: String, d: Boolean) = sp(c).getBoolean(k, d)
    private fun setBool(c: Context, k: String, v: Boolean) { sp(c).edit().putBoolean(k, v).apply() }
    private fun strSet(c: Context, k: String): Set<String> = sp(c).getStringSet(k, null) ?: emptySet()
    private fun setStrSet(c: Context, k: String, v: Set<String>) {
        sp(c).edit().putStringSet(k, HashSet(v)).apply()
    }

    // ---- שלושת המתגים של ההפעלה + מתג אפליקציות מערכת ----
    fun visualOn(c: Context) = bool(c, "t_visual", true)
    fun setVisualOn(c: Context, v: Boolean) = setBool(c, "t_visual", v)
    fun codecsOn(c: Context) = bool(c, "t_codecs", true)
    fun setCodecsOn(c: Context, v: Boolean) = setBool(c, "t_codecs", v)
    fun scanOn(c: Context) = bool(c, "t_scan", true)
    fun setScanOn(c: Context, v: Boolean) = setBool(c, "t_scan", v)
    /** "סרוק והשעה גם אפליקציות מערכת": הסריקה וההשעיה חלות גם עליהן. הזיהוי החזותי חל עליהן תמיד. */
    fun blockSystem(c: Context) = bool(c, "t_system", false)
    fun setBlockSystem(c: Context, v: Boolean) = setBool(c, "t_system", v)
    /** כבוי = הסריקה רק מדווחת ליומן ולא משעה כלום. */
    fun enforce(c: Context) = bool(c, "enforce", false)
    fun setEnforce(c: Context, v: Boolean) = setBool(c, "enforce", v)
    fun hideMagisk(c: Context) = bool(c, "hide_magisk", false)
    fun setHideMagisk(c: Context, v: Boolean) = setBool(c, "hide_magisk", v)

    // ---- הגבלות Device Owner (כל אחת עם מתג) ----
    class Restr(val key: String, val label: String, val def: Boolean)
    val RESTRICTIONS = listOf(
        Restr(UserManager.DISALLOW_APPS_CONTROL, "חסימת עצירה/ניקוי/הסרה של אפליקציות מההגדרות", true),
        Restr(UserManager.DISALLOW_SAFE_BOOT, "חסימת מצב בטוח", true),
        Restr(UserManager.DISALLOW_FACTORY_RESET, "חסימת איפוס יצרן מההגדרות", true),
        Restr(UserManager.DISALLOW_DEBUGGING_FEATURES, "חסימת ניפוי באגים ואפשרויות מפתח", true),
        Restr(UserManager.DISALLOW_INSTALL_APPS, "חסימת התקנת אפליקציות", false)
    )
    fun restr(c: Context, r: Restr) = bool(c, "r_" + r.key, r.def)
    fun setRestr(c: Context, r: Restr, v: Boolean) = setBool(c, "r_" + r.key, v)

    // ---- מצב ההגדרה ----
    fun setupDone(c: Context) = bool(c, "setup_done", false)
    fun pendingVerify(c: Context) = bool(c, "pending_verify", false)
    fun setPendingVerify(c: Context, v: Boolean) = setBool(c, "pending_verify", v)
    /** נכתב ב-commit (סינכרוני), כי מיד אחריו המכשיר עשוי לאתחל ו-apply לא יספיק להגיע לדיסק. */
    fun setSetupState(c: Context, done: Boolean, pendingVerify: Boolean) {
        sp(c).edit().putBoolean("setup_done", done).putBoolean("pending_verify", pendingVerify).commit()
    }
    /** מנגנון הבטיחות של האתחול הופעל (/data/vg/restored). נבדק ב-root באתחול ומוצג במסך הראשי. */
    fun restoredMode(c: Context) = bool(c, "restored_mode", false)
    fun setRestoredMode(c: Context, v: Boolean) = setBool(c, "restored_mode", v)
    fun lastVerify(c: Context): String = sp(c).getString("last_verify", "") ?: ""
    fun setLastVerify(c: Context, s: String) { sp(c).edit().putString("last_verify", s).apply() }

    // ---- מצבי אפליקציות: רגילה / מותרת (+ זיהוי חזותי אופציונלי) / מושעית ----
    const val STATE_NORMAL = 0
    const val STATE_ALLOWED = 1
    const val STATE_SUSPENDED = 2

    fun allowedApps(c: Context) = strSet(c, "allowed")
    fun allowedVisual(c: Context) = strSet(c, "allowed_visual")
    fun forcedSuspended(c: Context) = strSet(c, "forced_susp")

    fun appState(c: Context, pkg: String): Int = when {
        pkg in allowedApps(c) -> STATE_ALLOWED
        pkg in forcedSuspended(c) -> STATE_SUSPENDED
        else -> STATE_NORMAL
    }

    fun setAppState(c: Context, pkg: String, state: Int) {
        setStrSet(c, "allowed", allowedApps(c).let { if (state == STATE_ALLOWED) it + pkg else it - pkg })
        setStrSet(c, "forced_susp", forcedSuspended(c).let { if (state == STATE_SUSPENDED) it + pkg else it - pkg })
        if (state != STATE_ALLOWED) setStrSet(c, "allowed_visual", allowedVisual(c) - pkg)
    }

    fun setAllowedVisual(c: Context, pkg: String, v: Boolean) {
        setStrSet(c, "allowed_visual", allowedVisual(c).let { if (v) it + pkg else it - pkg })
    }

    // חבילות שהושעו / הוסתרו על ידינו (כדי לשחרר אותן בביטול)
    fun suspendedByUs(c: Context) = strSet(c, "susp_by_us")
    fun setSuspendedByUs(c: Context, s: Set<String>) = setStrSet(c, "susp_by_us", s)
    fun hiddenByUs(c: Context) = strSet(c, "hidden_by_us")
    fun setHiddenByUs(c: Context, s: Set<String>) = setStrSet(c, "hidden_by_us", s)

    private val sysCache = HashMap<String, Boolean>()
    fun isSystem(c: Context, pkg: String): Boolean = synchronized(sysCache) {
        sysCache.getOrPut(pkg) {
            try {
                (c.packageManager.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_SYSTEM) != 0
            } catch (_: Exception) { false }
        }
    }

    /**
     * האם הזיהוי החזותי פועל באפליקציה הזו. נקרא מ-CaptureService בכל מחזור.
     * חל גם על אפליקציות מערכת: Chrome ו-YouTube מותקנים מראש כאפליקציות מערכת, ול-Chrome יש
     * מפענחים משלו, כך שבלי זה הם לא נבדקים בכלל. רכיבי מערכת חיוניים מוחרגים ב-[Scanner.isProtected].
     */
    fun blockApplies(c: Context, pkg: String): Boolean {
        if (!visualOn(c)) return false
        return if (pkg in allowedApps(c)) pkg in allowedVisual(c) else true
    }

    // ---- קוד הגנה ----
    fun hasPin(c: Context): Boolean = sp(c).contains("pin_hash")

    fun setPin(c: Context, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.toHex()
        sp(c).edit().putString("pin_salt", salt).putString("pin_hash", hash(salt, pin)).apply()
    }

    fun verify(c: Context, pin: String): Boolean {
        val salt = sp(c).getString("pin_salt", null) ?: return false
        val saved = sp(c).getString("pin_hash", null) ?: return false
        return MessageDigest.isEqual(saved.toByteArray(), hash(salt, pin).toByteArray())
    }

    private fun hash(salt: String, pin: String): String =
        MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray()).toHex()

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
