package android.ezerphone.videoguard

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.SystemClock

/**
 * פעילות שקופה שמבקשת הרשאת צילום מסך (MediaProjection) ומעבירה אותה לשירות.
 *
 * כשה-appop PROJECT_MEDIA מוגדר ל-allow (ע"י מודול המג'יסק עם root), חלון
 * האישור של המערכת מאשר אוטומטית בלי להציג דבר למשתמש. אם לא — יוצג חלון
 * האישור הרגיל.
 *
 * מנעול: אם כבר יש הרשאה פעילה או שבקשה נמצאת בתהליך — יוצאים מיד בלי לבקש.
 * בקשת הרשאה שנייה עוצרת את הראשונה, וזה מה שיצר קודם לולאת הבהוב.
 * המנעול פג לבד אחרי זמן קצוב, ומשתחרר גם אם הפעילות נסגרת בלי תוצאה.
 * במסך נעול לא מבקשים (הבקשה הייתה נתקעת מאחורי הנעילה); השירות/מודול המג'יסק ינסו שוב.
 */
class RequestActivity : Activity() {

    private val REQ = 7001
    private var mine = false // האם הפעילות הזו היא שפתחה את הבקשה

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        if (CaptureService.hasProjection || CaptureService.requestInFlight || km.isKeyguardLocked) {
            done(); return
        }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            CaptureService.requestStartedAt = SystemClock.uptimeMillis()
            mine = true
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ)
        } catch (_: Exception) {
            release()
            done()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        release()
        if (requestCode == REQ && resultCode == RESULT_OK && data != null) {
            CaptureService.start(this, resultCode, data)
        }
        done()
    }

    override fun onDestroy() {
        if (!isChangingConfigurations) release()
        super.onDestroy()
    }

    private fun release() {
        if (mine) { CaptureService.requestStartedAt = 0L; mine = false }
    }

    private fun done() {
        finish()
        overridePendingTransition(0, 0)
    }
}
