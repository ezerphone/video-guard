package android.ezerphone.videoguard

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager

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
 *  - FLAG_SECURE: התצוגה המקדימה ב"אחרונים" ריקה, ואי אפשר לצלם מסך.
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
                a.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
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
    final override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Lock.unlocked) { toLock(); return }
        build()
    }

    override fun onResume() {
        super.onResume()
        if (!Lock.unlocked) { toLock(); return }
        refresh()
    }

    private fun toLock() {
        startActivity(Intent(this, LockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }

    /** בניית המסך. נקרא רק כשהקוד הוזן. */
    protected abstract fun build()
    /** עדכון התוכן בכל חזרה למסך. */
    protected open fun refresh() {}
}
