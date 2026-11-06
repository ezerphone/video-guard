package android.ezerphone.videoguard

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import java.io.File
import java.util.zip.ZipFile

/**
 * סריקת האפליקציות. אפליקציה נתפסת אם:
 *  1. היא ברשימת האסורות (CaptureService.VIDEO_APPS, כולל נגני אופליין),
 *  2. היא מצהירה על ACTION_VIEW עם video/*, או
 *  3. ה-APK שלה מצרף ספריית מפענח (libvlc, libavcodec, libffmpeg, libmpv, libdav1d ...).
 * אפליקציות מערכת מדולגות אלא אם הופעל "חסום גם אפליקציות מערכת". אפליקציה מותרת פטורה תמיד.
 * ההשעיה (Device Owner) מתבצעת רק כשהאכיפה דלוקה; אחרת הסריקה רק מדווחת ליומן.
 */
object Scanner {
    class Finding(val pkg: String, val label: String, val reasons: List<String>, val system: Boolean)

    @Volatile var last: Map<String, Finding> = emptyMap()

    /** חבילות שלא מושעות לעולם (בנוסף לכללים הדינמיים ב-[isProtected]). */
    private val NEVER = setOf(
        "android", "com.android.systemui", "com.android.settings", "com.android.phone",
        "com.android.server.telecom", "com.android.dialer", "com.google.android.dialer",
        "com.android.incallui", "com.android.mms", "com.google.android.apps.messaging",
        "com.android.providers.media", "com.android.providers.media.module",
        "com.android.providers.settings", "com.android.providers.contacts",
        "com.android.providers.telephony", "com.android.providers.downloads",
        "com.android.externalstorage", "com.android.documentsui",
        "com.android.webview", "com.google.android.webview",
        "com.android.permissioncontroller", "com.android.packageinstaller",
        "com.google.android.packageinstaller", "com.android.bluetooth", "com.android.nfc",
        "com.android.shell", "com.android.keychain", "com.android.certinstaller",
        "com.topjohnwu.magisk"
    )

    private val LIB_RE = Regex(
        """(^|/)lib(vlc|avcodec|avformat|avutil|ffmpeg|ijkffmpeg|mpv|dav1d|x264|x265|openh264|vpx)[^/]*\.so$""",
        RegexOption.IGNORE_CASE
    )

    private fun dynamicProtected(c: Context): Set<String> {
        val pm = c.packageManager
        val out = HashSet<String>()
        try {
            pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_ALL
            ).forEach { out += it.activityInfo.packageName }
        } catch (_: Exception) {}
        try {
            Settings.Secure.getString(c.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.substringBefore('/')?.let { out += it }
        } catch (_: Exception) {}
        return out
    }

    private fun isProtected(c: Context, p: String, extra: Set<String>): Boolean =
        p == c.packageName || p in NEVER || p in extra || p.startsWith("com.mediatek.") ||
            p.contains("magisk", ignoreCase = true) ||
            p in CaptureService.MOTION_EXEMPT_APPS || p.contains("camera", ignoreCase = true)

    private fun videoHandlers(pm: PackageManager): Set<String> {
        val out = HashSet<String>()
        // נגן מצהיר בדרך כלל על scheme (file/content/http), ולכן בודקים כמה URI-ים
        for (scheme in listOf("content", "file", "http", "https")) {
            try {
                val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("$scheme://x/a.mp4"), "video/*")
                pm.queryIntentActivities(i, PackageManager.MATCH_ALL).forEach { out += it.activityInfo.packageName }
            } catch (_: Exception) {}
        }
        return out
    }

    private fun decoderLibs(ai: ApplicationInfo): List<String> {
        val paths = ArrayList<String>()
        ai.sourceDir?.let { paths += it }
        ai.splitSourceDirs?.let { paths.addAll(it) }
        val hits = LinkedHashSet<String>()
        for (p in paths) {
            try {
                ZipFile(p).use { z ->
                    val e = z.entries()
                    while (e.hasMoreElements() && hits.size < 4) {
                        val n = e.nextElement().name
                        if (n.startsWith("lib/") && LIB_RE.containsMatchIn(n)) hits += n.substringAfterLast('/')
                    }
                }
            } catch (_: Exception) {}
        }
        // ספריות שחולצו לדיסק (extractNativeLibs=true)
        try {
            ai.nativeLibraryDir?.let { d ->
                File(d).list()?.forEach { if (LIB_RE.containsMatchIn(it)) hits += it }
            }
        } catch (_: Exception) {}
        return hits.take(4)
    }

    private fun sharedSystemUid(pm: PackageManager, p: String): Boolean = try {
        pm.getPackageInfo(p, 0).sharedUserId?.startsWith("android.uid.") == true
    } catch (_: Exception) { false }

    /** סריקה בלבד, בלי שינוי במכשיר. only = חבילה בודדת (התקנה חדשה). */
    fun scan(c: Context, only: String? = null): List<Finding> {
        val pm = c.packageManager
        val handlers = videoHandlers(pm)
        val extra = dynamicProtected(c)
        val blockSys = Prefs.blockSystem(c)
        val allowed = Prefs.allowedApps(c)
        val apps: List<ApplicationInfo> = if (only != null) {
            listOfNotNull(try { pm.getApplicationInfo(only, 0) } catch (_: Exception) { null })
        } else pm.getInstalledApplications(0)

        val out = ArrayList<Finding>()
        for (ai in apps) {
            val p = ai.packageName
            val sys = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (sys && !blockSys) continue
            if (p in allowed || isProtected(c, p, extra)) continue
            val reasons = ArrayList<String>()
            if (p in CaptureService.VIDEO_APPS) reasons += "ברשימת האסורות"
            if (p in handlers) reasons += "מצהירה על video/*"
            val libs = decoderLibs(ai)
            if (libs.isNotEmpty()) reasons += "ספריית מפענח: " + libs.joinToString(", ")
            if (reasons.isEmpty()) continue
            if (sys && sharedSystemUid(pm, p)) continue
            out += Finding(p, pm.getApplicationLabel(ai).toString(), reasons, sys)
        }
        return out
    }

    /** סורק ומחיל. בלי אכיפה או בלי Device Owner: דיווח בלבד. */
    @Synchronized
    fun scanAndApply(c: Context, only: String? = null) {
        val found = scan(c, only)
        last = if (only == null) found.associateBy { it.pkg } else last + found.associateBy { it.pkg }
        VgLog.add("סריקה${if (only != null) " ($only)" else ""}: נמצאו ${found.size}")
        found.forEach { VgLog.add("  ${it.pkg} — ${it.reasons.joinToString("; ")}") }

        val want = LinkedHashSet<String>()
        found.forEach { want += it.pkg }
        if (only == null) want += Prefs.forcedSuspended(c)
        want.remove(c.packageName)

        val dpm = c.getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(c, VgAdmin::class.java)
        if (!Prefs.enforce(c) || !dpm.isDeviceOwnerApp(c.packageName)) {
            VgLog.add("מצב דיווח בלבד: הייתי משעה ${want.size} חבילות (אכיפה כבויה או שאין Device Owner)")
            return
        }
        if (want.isNotEmpty()) {
            val failed = dpm.setPackagesSuspended(admin, want.toTypedArray(), true).toSet()
            if (failed.isNotEmpty()) VgLog.add("השעיה נכשלה (המערכת מסרבת): ${failed.joinToString()}")
            Prefs.setSuspendedByUs(c, Prefs.suspendedByUs(c) + (want - failed))
        }
        if (only == null) {
            val release = Prefs.suspendedByUs(c) - want
            if (release.isNotEmpty()) {
                dpm.setPackagesSuspended(admin, release.toTypedArray(), false)
                Prefs.setSuspendedByUs(c, Prefs.suspendedByUs(c) - release)
                VgLog.add("שוחררו מהשעיה: ${release.joinToString()}")
            }
        }
        VgLog.add("הושעו כרגע על ידינו: ${Prefs.suspendedByUs(c).size}")
    }

    /** משחרר את כל מה שהושעה על ידינו (ביטול אכיפה / "בטל הכל"). */
    fun releaseAll(c: Context) {
        val dpm = c.getSystemService(DevicePolicyManager::class.java)
        if (!dpm.isDeviceOwnerApp(c.packageName)) return
        val s = Prefs.suspendedByUs(c)
        if (s.isNotEmpty()) {
            try { dpm.setPackagesSuspended(ComponentName(c, VgAdmin::class.java), s.toTypedArray(), false) } catch (_: Exception) {}
            Prefs.setSuspendedByUs(c, emptySet())
            VgLog.add("שוחררו מהשעיה: ${s.size}")
        }
    }
}
