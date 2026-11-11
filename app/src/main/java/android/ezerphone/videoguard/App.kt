package android.ezerphone.videoguard

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle

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
 * נעילת PIN ברמת האפליקציה: ננעלת ברגע שאף מסך שלה לא גלוי. כל מסך בודק [needed] ב-onResume:
 * המסך הראשי מבקש PIN, ומסכי-משנה נסגרים (וחוזרים לראשי). כך אי אפשר לחזור למסך פתוח דרך "אחרונים".
 */
object Lock {
    @Volatile var unlocked = false
    private var started = 0

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) { started++ }
            override fun onActivityStopped(a: Activity) {
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

    fun needed(c: Context) = !unlocked
}
