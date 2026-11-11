package android.ezerphone.videoguard

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle

class VgApp : Application() {
    override fun onCreate() {
        super.onCreate()
        VgLog.init(this)
        Root.init()
        Lock.install(this)
        // התקנה חדשה / עדכון: סריקה מיידית של החבילה. PACKAGE_ADDED לא נמסר לרסיבר שבמניפסט
        // (מגבלת Android 8+), ולכן רושמים כאן; האפליקציה persistent ולכן התהליך חי.
        val f = IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply { addDataScheme("package") }
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val pkg = i.data?.schemeSpecificPart ?: return
                if (pkg == c.packageName) return
                val app = c.applicationContext
                Thread({ Scanner.scanAndApply(app, pkg) }, "vg-scan").start()
            }
        }, f)
        // פתיחת המסך: אם הזיהוי החזותי לא רץ או שאין לו הרשאת צילום — עכשיו אפשר לבקש
        // (באתחול המסך נעול, והבקשה נדחתה).
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (!Prefs.visualOn(c)) return
                if (CaptureService.running && CaptureService.hasProjection) return
                val app = c.applicationContext
                Thread({ try { Guard.startVisual(app) } catch (_: Throwable) {} }, "vg-unlock").start()
            }
        }, IntentFilter(Intent.ACTION_USER_PRESENT))
    }
}

/**
 * נעילת ה-PIN ברמת האפליקציה: ננעלת ברגע שאף מסך שלנו לא גלוי. קודם הנעילה הייתה רק ב-MainActivity
 * ודולגה כשנפתח מסך-משנה, כך שמי שחזר אחר כך מהיומן/רשימת האפליקציות הגיע למסך הראשי פתוח.
 */
object Lock {
    @Volatile var unlocked = false
    private var started = 0

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) { if (a !is RequestActivity) started++ }
            override fun onActivityStopped(a: Activity) {
                if (a is RequestActivity) return
                started = maxOf(0, started - 1)
                if (started == 0 && !a.isChangingConfigurations) unlocked = false
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    /** האם צריך להזין PIN לפני הצגת מסך שלנו. */
    fun needed(c: Context) = Prefs.hasPin(c) && !unlocked
}
