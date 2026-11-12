package android.ezerphone.videoguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import java.util.concurrent.Executors

/**
 * שירות הסריקה: מקבל כל התקנה ועדכון (PACKAGE_ADDED, גם עם EXTRA_REPLACING) וסורק את החבילה.
 * PACKAGE_ADDED לא נמסר לרסיבר שבמניפסט (Android 8+), ולכן צריך תהליך שרץ — שירות קדמי עם
 * התראה קבועה. בכל עלייה הוא סורק גם מה שהותקן או עודכן בזמן שלא רץ.
 */
class ScanService : Service() {
    private val exec = Executors.newSingleThreadExecutor()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val pkg = i.data?.schemeSpecificPart ?: return
            if (pkg == packageName) return
            val update = i.getBooleanExtra(Intent.EXTRA_REPLACING, false)
            VgLog.add((if (update) "עודכנה" else "הותקנה") + ": $pkg")
            exec.execute {
                try {
                    Scanner.scanOne(applicationContext, pkg)
                    Prefs.setLastScan(applicationContext, System.currentTimeMillis())
                } catch (t: Throwable) { VgLog.add("שגיאה בסריקת $pkg: ${t.message}") }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, notification(this))
        // הפעלה מחדש אוטומטית (START_STICKY) אחרי שהמשתמש כיבה: לא סורקים, onStartCommand עוצר
        if (!Prefs.serviceOn(this)) return
        running = true
        registerReceiver(receiver, IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply { addDataScheme("package") })
        VgLog.add("שירות הסריקה פועל")
        exec.execute { try { Scanner.catchUp(applicationContext) } catch (t: Throwable) { VgLog.add("שגיאה: ${t.message}") } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Prefs.serviceOn(this)) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    override fun onDestroy() {
        if (!running) { exec.shutdown(); super.onDestroy(); return }
        running = false
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        exec.shutdown()
        VgLog.add("שירות הסריקה נעצר")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        @Volatile var running = false
        private const val CH_SERVICE = "service"
        private const val NOTIF_ID = 1

        fun start(c: Context) {
            try { c.startForegroundService(Intent(c, ScanService::class.java)) }
            catch (t: Throwable) {
                VgLog.add("הפעלת השירות נכשלה (${t.javaClass.simpleName}), מנסה דרך root")
                Root.bg { Root.run("am start-foreground-service --user 0 -n ${c.packageName}/.ScanService") }
            }
        }

        fun stop(c: Context) { c.stopService(Intent(c, ScanService::class.java)) }

        private fun channels(c: Context) {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "שירות סריקה", NotificationManager.IMPORTANCE_MIN))
        }

        private fun openApp(c: Context) = PendingIntent.getActivity(
            c, 0, Intent(c, LockActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )

        /**
         * שירות קדמי חייב התראה. האפליקציה לא מבקשת הרשאת התראות, ולכן ב-Android 13 ומעלה
         * ההתראה לא מוצגת (השירות מופיע רק ב"אפליקציות פעילות" בתפריט ההגדרות המהירות).
         */
        private fun notification(c: Context): Notification {
            channels(c)
            return Notification.Builder(c, CH_SERVICE)
                .setSmallIcon(R.drawable.ic_block)
                .setContentTitle("סריקת התקנות פעילה")
                .setContentText("כל אפליקציה חדשה או מעודכנת נבדקת")
                .setContentIntent(openApp(c))
                .setOngoing(true)
                .build()
        }

    }
}
