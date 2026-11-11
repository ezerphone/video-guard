package android.ezerphone.videoguard

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object Prefs {
    private fun sp(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences("videoguard", Context.MODE_PRIVATE)

    private fun bool(c: Context, k: String, d: Boolean) = sp(c).getBoolean(k, d)
    private fun setBool(c: Context, k: String, v: Boolean) = sp(c).edit().putBoolean(k, v).commit()
    private fun set(c: Context, k: String): Set<String> = sp(c).getStringSet(k, emptySet())!!.toSet()
    private fun setSet(c: Context, k: String, v: Set<String>) = sp(c).edit().putStringSet(k, v.toSet()).commit()

    // ── שירות הסריקה ──
    fun serviceOn(c: Context) = bool(c, "service_on", false)
    fun setServiceOn(c: Context, v: Boolean) = setBool(c, "service_on", v)
    /** זמן הסריקה האחרונה: בעלייה, השירות סורק גם מה שהותקן/עודכן מאז (כשהשירות לא רץ). */
    fun lastScan(c: Context) = sp(c).getLong("last_scan", 0L)
    fun setLastScan(c: Context, t: Long) { sp(c).edit().putLong("last_scan", t).commit() }

    // ── אפליקציות ──
    /** אפליקציות שהשעינו (כדי לדעת מה לשחרר). */
    fun suspended(c: Context) = set(c, "suspended")
    @Synchronized fun addSuspended(c: Context, p: Collection<String>) = setSet(c, "suspended", suspended(c) + p)
    @Synchronized fun removeSuspended(c: Context, p: Collection<String>) = setSet(c, "suspended", suspended(c) - p.toSet())
    /** אפליקציות שהמשתמש פטר (עם PIN): לא נסרקות ולא מושעות. */
    fun exempt(c: Context) = set(c, "exempt")
    @Synchronized fun setExempt(c: Context, p: String, v: Boolean) = setSet(c, "exempt", if (v) exempt(c) + p else exempt(c) - p)
    /** הסיבה שבגללה כל אפליקציה הושעתה, להצגה. */
    fun reason(c: Context, p: String): String? = sp(c).getString("why:$p", null)
    fun setReason(c: Context, p: String, why: String?) {
        sp(c).edit().apply { if (why == null) remove("why:$p") else putString("why:$p", why) }.commit()
    }

    // ── נעילות (Device Owner) ──
    fun lockSafeBoot(c: Context) = bool(c, "lock_safe_boot", true)
    fun setLockSafeBoot(c: Context, v: Boolean) = setBool(c, "lock_safe_boot", v)
    fun lockFactoryReset(c: Context) = bool(c, "lock_factory_reset", true)
    fun setLockFactoryReset(c: Context, v: Boolean) = setBool(c, "lock_factory_reset", v)
    fun lockAdb(c: Context) = bool(c, "lock_adb", true)
    fun setLockAdb(c: Context, v: Boolean) = setBool(c, "lock_adb", v)

    // ── PIN (PBKDF2 עם salt; ה-PIN עצמו לא נשמר) ──
    fun hasPin(c: Context) = sp(c).contains("pin_hash")

    fun setPin(c: Context, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        sp(c).edit()
            .putString("pin_salt", b64(salt))
            .putString("pin_hash", b64(hash(pin, salt)))
            .commit()
    }

    fun checkPin(c: Context, pin: String): Boolean {
        val salt = sp(c).getString("pin_salt", null) ?: return false
        val h = sp(c).getString("pin_hash", null) ?: return false
        return b64(hash(pin, Base64.decode(salt, Base64.NO_WRAP))) == h
    }

    private fun hash(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, 20000, 256)).encoded

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
}
