package android.ezerphone.videoguard

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

class VgApp : Application() {
    override fun onCreate() {
        super.onCreate()
        VgLog.init(this)
        Root.init()
        // התקנה חדשה / עדכון: סריקה מיידית של החבילה. PACKAGE_ADDED לא נמסר לרסיבר שבמניפסט
        // (מגבלת Android 8+), ולכן רושמים כאן; האפליקציה persistent ולכן התהליך חי.
        val f = IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply { addDataScheme("package") }
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (!Prefs.scanOn(c)) return
                val pkg = i.data?.schemeSpecificPart ?: return
                if (pkg == c.packageName) return
                val app = c.applicationContext
                Thread({ Scanner.scanAndApply(app, pkg) }, "vg-scan").start()
            }
        }, f)
    }
}
