package android.ezerphone.videoguard

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * מסנן קבצי media_codecs*.xml: מוחק מתוך <Decoders> כל <MediaCodec> של וידאו, ומשאיר את כל השאר
 * (מפענחי אודיו, מקודדים, הגדרות, הערות, עיצוב) בדיוק כמו שהיה.
 *
 * מפענח נחשב "וידאו" אם ה-type שלו (או אחד מתגי <Type> שבתוכו) מתחיל ב-video/, או אם השם שלו
 * הוא של מפענח וידאו שהוגדר באחד הקבצים האחרים ([videoNames]). המקרה השני חשוב: קבצי
 * performance מכילים לפעמים <MediaCodec name="..." update="true"> בלי type, ועדכון של מפענח
 * שכבר נמחק הוא שגיאה שעלולה לפסול את כל הקובץ.
 *
 * עובד על הטקסט (ולא על DOM) כדי לא לשנות שום דבר אחר בקובץ. [verify] בודק את התוצאה עם DOM.
 * קוד Kotlin נקי בלי תלות באנדרואיד, כדי שאפשר יהיה לבדוק אותו בבדיקות יחידה.
 */
object CodecFilter {
    class Result(val text: String, val removed: List<String>)

    private val TYPE_ATTR = Regex("""(?<![\w:-])type\s*=\s*["']\s*video/""", RegexOption.IGNORE_CASE)
    private val TYPE_CHILD = Regex("""<Type\b[^>]*?(?<![\w:-])name\s*=\s*["']\s*video/""", RegexOption.IGNORE_CASE)
    private val NAME_ATTR = Regex("""(?<![\w:-])name\s*=\s*["']([^"']*)["']""")
    private val COMMENT = Regex("""<!--[\s\S]*?-->""")

    /** שמות כל מפענחי הווידאו בקובץ (לפי type), כדי לסנן גם עדכונים שלהם בקבצים אחרים. */
    fun videoDecoderNames(xml: String): Set<String> {
        val out = HashSet<String>()
        walk(xml) { block -> if (isVideoByType(xml, block)) nameOf(xml, block)?.let { out += it } }
        return out
    }

    fun strip(xml: String, videoNames: Set<String> = emptySet()): Result {
        val removed = ArrayList<String>()
        val sb = StringBuilder(xml.length)
        var copied = 0
        walk(xml) { block ->
            val start = block.first
            val end = block.last + 1
            val name = nameOf(xml, block)
            if (isVideoByType(xml, block) || (name != null && name in videoNames)) {
                removed += name ?: "?"
                // מוחק גם את ההזחה שלפני הבלוק ואת סוף השורה שאחריו, אם הבלוק עומד בשורה משלו
                var s = start
                while (s > copied && (xml[s - 1] == ' ' || xml[s - 1] == '\t')) s--
                val ownLine = s == 0 || xml[s - 1] == '\n'
                var e = end
                if (ownLine) {
                    var k = end
                    while (k < xml.length && (xml[k] == ' ' || xml[k] == '\t' || xml[k] == '\r')) k++
                    if (k < xml.length && xml[k] == '\n') e = k + 1 else if (k == xml.length) e = k else s = start
                } else s = start
                sb.append(xml, copied, s)
                copied = e
            }
        }
        sb.append(xml, copied, xml.length)
        return Result(sb.toString(), removed)
    }

    /**
     * בודק שהסינון תקין: הקובץ המסונן הוא XML תקין, לא נשאר בו אף מפענח וידאו, וכל שאר
     * ה-MediaCodec (אודיו, מקודדים) זהים למקור. מחזיר null אם הכול תקין, אחרת תיאור הבעיה.
     */
    fun verify(original: String, filtered: String, videoNames: Set<String> = emptySet()): String? {
        val a = parse(original) ?: return "הקובץ המקורי אינו XML תקין"
        val b = parse(filtered) ?: return "הקובץ המסונן אינו XML תקין"
        fun keep(l: List<Codec>) = l.filter { !(it.decoder && (it.video || it.name in videoNames)) }
        val left = b.filter { it.decoder && (it.video || it.name in videoNames) }
        if (left.isNotEmpty()) return "נשארו מפענחי וידאו: ${left.joinToString { it.name }}"
        val ka = keep(a).map { it.key }
        val kb = b.map { it.key }
        if (ka != kb) return "הסינון שינה מפענחי אודיו או מקודדים"
        return null
    }

    fun isWellFormed(xml: String) = parse(xml) != null

    // ───────────────────────── פנימי ─────────────────────────

    private class Codec(val name: String, val decoder: Boolean, val video: Boolean, val key: String)

    private fun parse(xml: String): List<Codec>? = try {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = false
        f.isValidating = false
        val doc = f.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        val nodes = doc.getElementsByTagName("MediaCodec")
        (0 until nodes.length).map { i ->
            val el = nodes.item(i) as Element
            var p = el.parentNode
            var decoder = false
            while (p != null) {
                if (p.nodeName == "Decoders") { decoder = true; break }
                if (p.nodeName == "Encoders") break
                p = p.parentNode
            }
            val types = ArrayList<String>()
            el.getAttribute("type").takeIf { it.isNotEmpty() }?.let { types += it }
            val kids = el.getElementsByTagName("Type")
            for (k in 0 until kids.length) types += (kids.item(k) as Element).getAttribute("name")
            val name = el.getAttribute("name")
            Codec(name, decoder, types.any { it.trim().startsWith("video/", true) },
                "${if (decoder) "D" else "E"}:$name:${types.joinToString(",")}")
        }
    } catch (_: Exception) { null }

    private fun isVideoByType(xml: String, block: IntRange): Boolean {
        val text = COMMENT.replace(xml.substring(block.first, block.last + 1), "")
        val startTag = text.substring(0, tagEnd(text, 0) + 1)
        return TYPE_ATTR.containsMatchIn(startTag) || TYPE_CHILD.containsMatchIn(text)
    }

    private fun nameOf(xml: String, block: IntRange): String? {
        val startTag = xml.substring(block.first, tagEnd(xml, block.first) + 1)
        return NAME_ATTR.find(startTag)?.groupValues?.get(1)
    }

    /** קורא ל-[f] עם הטווח של כל <MediaCodec>...</MediaCodec> שנמצא בתוך <Decoders>. */
    private fun walk(xml: String, f: (IntRange) -> Unit) {
        var i = 0
        var inDecoders = 0
        val n = xml.length
        while (i < n) {
            if (xml.startsWith("<!--", i)) { i = xml.indexOf("-->", i + 4).let { if (it < 0) n else it + 3 }; continue }
            if (xml.startsWith("<![CDATA[", i)) { i = xml.indexOf("]]>", i + 9).let { if (it < 0) n else it + 3 }; continue }
            if (xml.startsWith("<?", i)) { i = xml.indexOf("?>", i + 2).let { if (it < 0) n else it + 2 }; continue }
            if (xml[i] != '<') { i++; continue }
            val name = tagName(xml, i)
            val te = tagEnd(xml, i)
            if (te < 0) return
            val selfClosing = xml[te - 1] == '/'
            when (name) {
                "Decoders" -> if (!selfClosing) inDecoders++
                "/Decoders" -> if (inDecoders > 0) inDecoders--
                "MediaCodec" -> if (inDecoders > 0) {
                    val end = if (selfClosing) te + 1 else closeOf(xml, te + 1)
                    if (end > 0) { f(i until end); i = end; continue }
                }
            }
            i = te + 1
        }
    }

    private fun tagName(xml: String, lt: Int): String {
        var j = lt + 1
        val sb = StringBuilder()
        if (j < xml.length && xml[j] == '/') { sb.append('/'); j++ }
        while (j < xml.length && (xml[j].isLetterOrDigit() || xml[j] in "_-:.")) { sb.append(xml[j]); j++ }
        return sb.toString()
    }

    /** האינדקס של ה-'>' שסוגר את התג שמתחיל ב-[lt], תוך דילוג על ערכים במרכאות. */
    private fun tagEnd(xml: String, lt: Int): Int {
        var q = 0.toChar()
        var j = lt + 1
        while (j < xml.length) {
            val ch = xml[j]
            if (q != 0.toChar()) { if (ch == q) q = 0.toChar() }
            else if (ch == '"' || ch == '\'') q = ch
            else if (ch == '>') return j
            j++
        }
        return -1
    }

    /** האינדקס שאחרי </MediaCodec> המתאים, בדילוג על הערות. -1 אם לא נמצא. */
    private fun closeOf(xml: String, from: Int): Int {
        var i = from
        while (i < xml.length) {
            if (xml.startsWith("<!--", i)) { i = xml.indexOf("-->", i + 4).let { if (it < 0) return -1 else it + 3 }; continue }
            if (xml[i] == '<' && tagName(xml, i) == "/MediaCodec") {
                val te = tagEnd(xml, i)
                return if (te < 0) -1 else te + 1
            }
            i++
        }
        return -1
    }
}
