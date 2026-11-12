package android.ezerphone.videoguard

/**
 * זיהוי ספריות פענוח וידאו שאפליקציה מצרפת בתוך ה-APK שלה. אפליקציה כזו מנגנת וידאו גם אחרי
 * שמחקנו את מפענחי המערכת, כי היא לא צריכה אותם. קוד Kotlin נקי, נבדק בבדיקות יחידה.
 */
object Detector {
    /** תחילת שם הספרייה (בלי "lib" ובלי ".so"). */
    private val PREFIXES = listOf(
        "avcodec", "avformat", "ffmpeg", "mobileffmpeg", "ffmpegkit", "ijkffmpeg", "ijkplayer",
        "vlc", "mpv", "dav1d", "gav1", "vpx", "de265", "openh264", "aom"
    )

    /** מחזיר את שמות ספריות הפענוח (למשל libavcodec.so) מתוך רשימת הקבצים ב-APK. */
    fun decoderLibs(entries: Sequence<String>): List<String> {
        val out = LinkedHashSet<String>()
        for (e in entries) {
            if (!e.endsWith(".so", ignoreCase = true)) continue
            val file = e.substringAfterLast('/')
            if (isDecoderLib(file)) out += file
        }
        return out.toList()
    }

    fun isDecoderLib(file: String): Boolean {
        val f = file.lowercase()
        if (!f.startsWith("lib") || !f.endsWith(".so")) return false
        val base = f.removePrefix("lib").removeSuffix(".so")
        return "ffmpeg" in base || PREFIXES.any { base.startsWith(it) }
    }
}
