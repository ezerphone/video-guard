package android.ezerphone.videoguard

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View

class VgApp : Application() {
    override fun onCreate() {
        super.onCreate()
        VgLog.init(this)
        Root.init()
        Lock.install(this)
    }
}

/** אתחול ועדכון של האפליקציה עצמה: מפעיל את שירות הסריקה אם הוא דלוק. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        VgLog.add("== ${if (i.action == Intent.ACTION_BOOT_COMPLETED) "אתחול" else "עדכון האפליקציה"} ==")
        if (Prefs.serviceOn(c)) ScanService.start(c.applicationContext)
        if (Admin.isOwner(c)) Admin.applyLocks(c)
    }
}

/**
 * נעילת הקוד. בלי קוד נכון לא נפתח שום מסך חוץ ממסך הקוד:
 *  - מסך הקוד ([LockActivity]) הוא היחיד שאפשר לפתוח מבחוץ. כל שאר המסכים יורשים מ-[Guarded],
 *    שבודק את הנעילה ב-onCreate, *לפני* שהתוכן נבנה, ואם נעול — עובר למסך הקוד.
 *  - ברגע שאף מסך של האפליקציה לא גלוי (Home, מעבר לאפליקציה אחרת, כיבוי מסך) האפליקציה ננעלת
 *    וכל המסכים שלה נסגרים. היא נשארת ב"אחרונים", אבל לחיצה עליה פותחת מחדש את מסך הקוד.
 *  - התצוגה המקדימה ב"אחרונים" ריקה (Android 13 ומעלה). צילומי מסך מותרים.
 */
object Lock {
    @Volatile var unlocked = false
        private set
    private val alive = ArrayList<Activity>()
    private var started = 0

    fun open() { unlocked = true }

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) {
                alive += a
                if (Build.VERSION.SDK_INT >= 33) a.setRecentsScreenshotEnabled(false)
                a.window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
            }
            override fun onActivityStarted(a: Activity) { started++ }
            override fun onActivityStopped(a: Activity) {
                started = maxOf(0, started - 1)
                if (started == 0 && !a.isChangingConfigurations) lockNow()
            }
            override fun onActivityDestroyed(a: Activity) { alive -= a }
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
        })
    }

    private fun lockNow() {
        unlocked = false
        for (a in alive.toList()) if (!a.isFinishing) a.finish()
    }
}

/** מסך מוגן: בלי קוד נכון הוא לא נבנה בכלל, אלא מעביר למסך הקוד. */
abstract class Guarded : Activity() {
    /** התאמה אוטומטית לגובה המסך: התוכן מוקטן עד שהוא נכנס בלי גלילה. */
    protected open val autoFit = false
    private var fitK = 1f

    final override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Lock.unlocked) { toLock(); return }
        St.k = 1f
        build()
    }

    override fun onResume() {
        super.onResume()
        if (!Lock.unlocked) { toLock(); return }
        fitK = 1f
        St.k = fitK
        refresh()
        if (autoFit) fit()
    }

    /**
     * לפני כל ציור: אם התוכן גבוה מהמסך, מקטינים את המקדם באותו יחס ובונים מחדש (בלי לצייר את
     * הגרסה הגדולה). עד [MIN_K]; מתחת לזה הטקסט קטן מדי, והמסך פשוט נגלל.
     */
    private fun fit() {
        val listener = object : android.view.ViewTreeObserver.OnPreDrawListener {
            var rounds = 0
            override fun onPreDraw(): Boolean {
                val sv = findScroll(window.decorView)
                val child = sv?.getChildAt(0)
                val have = sv?.height ?: 0
                val need = child?.height ?: 0
                if (have > 0 && need > have && fitK > MIN_K && rounds++ < 6) {
                    fitK = maxOf(MIN_K, fitK * have / need * 0.98f)
                    St.k = fitK
                    refresh()
                    return false
                }
                window.decorView.viewTreeObserver.removeOnPreDrawListener(this)
                return true
            }
        }
        window.decorView.viewTreeObserver.addOnPreDrawListener(listener)
    }

    private fun findScroll(v: View): android.widget.ScrollView? {
        if (v is android.widget.ScrollView) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) findScroll(v.getChildAt(i))?.let { return it }
        return null
    }

    companion object { const val MIN_K = 0.7f }

    private fun toLock() {
        startActivity(Intent(this, LockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }

    /** בניית המסך. נקרא רק כשהקוד הוזן. */
    protected abstract fun build()
    /** עדכון התוכן בכל חזרה למסך. */
    protected open fun refresh() {}
}
