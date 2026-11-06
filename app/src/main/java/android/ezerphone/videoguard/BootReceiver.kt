package android.ezerphone.videoguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** טריגר האתחול היחיד (מחליף את service.sh של המודול הישן). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        Thread({ try { Guard.onBoot(app) } catch (t: Throwable) { VgLog.add("שגיאה באתחול: ${t.message}") } }, "vg-boot").start()
    }
}
