package android.ezerphone.videoguard

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** מסך יומן פקודות: כל פקודת root ותוצאתה. נפתח מהמסך הראשי (שמוגן ב-PIN). */
class LogActivity : Activity() {

    private lateinit var tv: TextView
    private lateinit var scroll: ScrollView
    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { render(); h.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.log_title)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(btn(R.string.test_root) { testRoot() })
        bar.addView(btn(R.string.copy) { copy() })
        bar.addView(btn(R.string.clear) { VgLog.clear(); render() })
        root.addView(bar)
        tv = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 11f
            setTextIsSelectable(true)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        scroll = ScrollView(this).apply { addView(tv) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    override fun onResume() { super.onResume(); h.post(tick) }
    override fun onPause() { super.onPause(); h.removeCallbacks(tick) }

    private fun render() {
        val t = VgLog.text()
        if (t == tv.text.toString()) return
        val atBottom = scroll.getChildAt(0).bottom - (scroll.height + scroll.scrollY) < dp(40)
        tv.text = t
        if (atBottom) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** בדיקת root ראשונה: מוכיחה ש-libsu עובד ושהפקודות נרשמות. קריאה בלבד. */
    private fun testRoot() {
        Root.bg {
            VgLog.add("== בדיקת root ==")
            Root.run("id")
            Root.run(
                "getprop ro.boot.slot_suffix",
                "getprop ro.boot.veritymode",
                "getprop ro.build.version.release"
            )
        }
    }

    private fun copy() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("VideoGuard log", VgLog.text()))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun btn(res: Int, onClick: () -> Unit) = Button(this).apply {
        setText(res); setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
