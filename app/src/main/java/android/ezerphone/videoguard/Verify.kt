package android.ezerphone.videoguard

import android.content.Context
import android.media.MediaCodecList
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** שלב 6: ספירת מפענחי וידאו כפי שמערכת המדיה (mediaserver, swcodec, HAL) רואה אותם. */
object Verify {
    class Result(val total: Int?, val viaList: Int, val text: String)

    private val PROC_RE = Regex("mediaswcodec|mediaserver|media\\.codec|mediacodec|c2@|c2-|omx|\\.media\\.", RegexOption.IGNORE_CASE)

    /** כמה מפענחי וידאו רואה כל אחד מתהליכי המדיה, לפי אותו סינון של ההסרה. */
    fun run(c: Context): Result {
        val sb = StringBuilder()
        val bb = listOf("/data/adb/magisk/busybox", "/data/adb/ksu/bin/busybox", "/data/adb/ap/bin/busybox")
            .firstOrNull { Root.test("[ -x '$it' ]") }
        val awk = File(c.filesDir, "strip_video_decoders.awk")
        try {
            c.assets.open("strip_video_decoders.awk").use { i -> awk.outputStream().use { o -> i.copyTo(o) } }
            awk.setReadable(true, false)
        } catch (_: Exception) {}

        var total: Int? = null
        if (bb != null) {
            val files = "/vendor/etc/media_codecs*.xml /odm/etc/media_codecs*.xml /product/etc/media_codecs*.xml " +
                "/system_ext/etc/media_codecs*.xml /system/etc/media_codecs*.xml ${Paths.APEX_XML}"
            val sh = File(c.filesDir, "count_decoders.sh")
            sh.writeText(
                "A=\$(cat $files 2>/dev/null | grep -cE '<MediaCodec([[:space:]>]|\$)')\n" +
                    "B=\$(cat $files 2>/dev/null | $bb awk -f '${awk.absolutePath}' | grep -cE '<MediaCodec([[:space:]>]|\$)')\n" +
                    "G=\$(cat $files 2>/dev/null | grep -c 'decoder\" type=\"video/')\n" +
                    "echo \"\$((A-B)) \$G\"\n"
            )
            sh.setReadable(true, false)
            val pids = Root.quiet("ps -A -o PID,NAME").mapNotNull { l ->
                val t = l.trim().split(Regex("\\s+"), 2)
                if (t.size == 2 && t[0].toIntOrNull() != null && PROC_RE.containsMatchIn(t[1])) t[0] to t[1] else null
            }.take(8)
            // כל תהליך רואה את אותה רשימה מבחינתו; סכום היה מכפיל. המדד = המקסימום שתהליך כלשהו רואה.
            var most = 0; var seen = 0
            for ((pid, name) in pids) {
                val o = Root.quiet("nsenter -t $pid -m -- sh '${sh.absolutePath}'").lastOrNull()?.trim().orEmpty()
                val n = o.split(' ').firstOrNull()?.toIntOrNull()
                sb.append("  $name ($pid): ").append(if (n != null) "$n  [grep: ${o.substringAfter(' ', "?")}]" else "לא נקרא ($o)").append('\n')
                if (n != null) { most = maxOf(most, n); seen++ }
            }
            if (seen > 0) total = most else sb.append("  ⚠ לא נמצאו תהליכי מדיה לבדיקה\n")
        } else sb.append("  ⚠ אין busybox — אי אפשר לספור מתוך תהליכי המדיה\n")

        val viaList = try {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.count { info ->
                !info.isEncoder && info.supportedTypes.any { it.startsWith("video/") }
            }
        } catch (_: Throwable) { -1 }

        val head = "מפענחי וידאו במערכת (המקסימום בתהליכי המדיה): ${total ?: "לא ידוע"}"
        val text = head + "\nדרך MediaCodecList (כמו שכל אפליקציה רואה): ${if (viaList >= 0) viaList else "שגיאה"}\n" + sb
        VgLog.add(text)
        val stamp = SimpleDateFormat("dd/MM HH:mm", Locale.US).format(Date())
        Prefs.setLastVerify(c, "$stamp — $head | MediaCodecList: $viaList")
        return Result(total, viaList, text)
    }
}
