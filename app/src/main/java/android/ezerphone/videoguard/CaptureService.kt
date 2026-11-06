package android.ezerphone.videoguard

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

/**
 * ליבת VideoGuard: שירות קדמי שמחזיק צילום מסך רציף (MediaProjection) ומריץ
 * את מנוע זיהוי־התנועה. כשמזהה וידאו — מציג חלון חסימה מעל המסך.
 *
 * אין כאן שירות נגישות: את מה שהנגישות נתנה ב-VideoStop מחליפים כאן ב:
 *  • צילום מסך רציף דרך MediaProjection (אישור ניתן אוטומטית ע"י appop PROJECT_MEDIA,
 *    שמוגדר ע"י מודול המג'יסק עם הרשאות root).
 *  • זיהוי האפליקציה בחזית דרך UsageStatsManager (במקום אירועי נגישות).
 *  • חלון חסימה מסוג TYPE_APPLICATION_OVERLAY (במקום TYPE_ACCESSIBILITY_OVERLAY).
 *  • חזרה למסך הבית ע"י Intent (במקום performGlobalAction).
 *
 * חיסכון: הלכידה מושהית לגמרי כשאין מה לבדוק (מסך כבוי/נעול, מסך הבית, אפליקציה
 * פטורה, חסימה מוצגת), והשאילתה לנתוני שימוש קוראת רק אירועים חדשים.
 */
class CaptureService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val wm by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private val audio by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private val power by lazy { getSystemService(POWER_SERVICE) as PowerManager }
    private val keyguard by lazy { getSystemService(KEYGUARD_SERVICE) as KeyguardManager }
    private val usage by lazy { getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager }

    private var projection: MediaProjection? = null
    private var grabber: ScreenGrabber? = null
    private val detector = MotionDetector()

    private var overlay: View? = null
    private var blockedPackage: String? = null
    private var focusRequest: AudioFocusRequest? = null

    private var homePkg: String? = null
    private var cooldownUntil = 0L
    private val blockMemory = HashMap<String, Long>()
    private val exemptCache = HashMap<String, Boolean>()

    // האפליקציה האחרונה שעלתה לחזית — נשמרת, כדי שהזיהוי לא ייכבה כשנשארים בה זמן רב
    private var lastFg: String? = null
    private var lastQuery = 0L

    private var reacquireDelay = REACQUIRE_MIN_MS

    // נגיעות: חלון שקוף בגודל פיקסל עם FLAG_WATCH_OUTSIDE_TOUCH מקבל ACTION_OUTSIDE על כל
    // נגיעה במסך (בלי מיקום) — כך יודעים שהמשתמש גולל, בלי שירות נגישות.
    private var touchView: View? = null
    @Volatile private var lastTouch = 0L
    private var lastLogPkg: String? = null

    private val tick = object : Runnable {
        override fun run() {
            var next = MOTION_INTERVAL_MS
            try { next = onTick() } catch (_: Exception) {}
            handler.postDelayed(this, next)
        }
    }

    /** חידוש הרשאת הצילום: מושהה, עם המתנה הולכת וגדלה, ונעצר ברגע שיש הרשאה. */
    private val reacquire = object : Runnable {
        override fun run() {
            if (hasProjection) return
            if (!requestInFlight && power.isInteractive && !keyguard.isKeyguardLocked) {
                try {
                    startActivity(Intent(this@CaptureService, RequestActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {}
                reacquireDelay = min(reacquireDelay * 2, REACQUIRE_MAX_MS)
            }
            handler.postDelayed(this, reacquireDelay)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        homePkg = resolveHome()
        startInForeground()
        addTouchWatcher()
    }

    private fun addTouchWatcher() {
        if (touchView != null) return
        val v = object : View(this) {
            override fun onTouchEvent(event: MotionEvent): Boolean {
                lastTouch = SystemClock.uptimeMillis()
                return false
            }
        }
        val lp = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        try { wm.addView(v, lp); touchView = v } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        if (data != null) setupProjection(code, data)
        if (!hasProjection) scheduleReacquire(REACQUIRE_FIRST_MS)
        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_STICKY
    }

    private fun setupProjection(code: Int, data: Intent) {
        // אם כבר יש הרשאה פעילה — להתעלם מטוקן נוסף. הפעלת הקרנה שנייה עוצרת את הראשונה,
        // וזה מה שיצר את לולאת ההבהוב. מנעול כפול: גם כאן וגם ב-RequestActivity.
        if (hasProjection && grabber != null) return
        try {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val p = mpm.getMediaProjection(code, data) ?: return
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    if (projection !== p) return
                    hasProjection = false
                    grabber?.release(); grabber = null
                    projection = null
                    scheduleReacquire(REACQUIRE_FIRST_MS)
                }
            }, handler)
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(m)
            // תמיד בכיוון אורך: התצוגה הווירטואלית קבועה, ותוכן לרוחב פשוט ממוזער לתוכה
            val w = min(m.widthPixels, m.heightPixels)
            val h = max(m.widthPixels, m.heightPixels)
            grabber?.release()
            grabber = ScreenGrabber(
                p, w, h, m.densityDpi,
                sysDimen("status_bar_height"), sysDimen("navigation_bar_height")
            )
            projection = p
            hasProjection = true
            reacquireDelay = REACQUIRE_MIN_MS
            handler.removeCallbacks(reacquire)
            detector.reset()
        } catch (_: Exception) {}
    }

    private fun scheduleReacquire(delay: Long) {
        handler.removeCallbacks(reacquire)
        handler.postDelayed(reacquire, delay)
    }

    // ───────────────────────── מחזור תקופתי ─────────────────────────

    /** מחזיר את ההשהיה עד הבדיקה הבאה. */
    private fun onTick(): Long {
        if (!power.isInteractive) { pauseCapture(); return IDLE_INTERVAL_MS }
        if (keyguard.isKeyguardLocked) { pauseCapture(); return MOTION_INTERVAL_MS }

        val pkg = currentForegroundPackage() ?: run { pauseCapture(); return MOTION_INTERVAL_MS }

        // אם כבר מוצגת חסימה — להסיר אותה רק כשיוצאים מהאפליקציה החסומה
        if (overlay != null) {
            pauseCapture()
            if (pkg != blockedPackage && pkg != packageName) hideOverlay()
            return MOTION_INTERVAL_MS
        }

        val now = SystemClock.uptimeMillis()
        if (now < cooldownUntil || pkg == packageName || pkg == homePkg ||
            isLauncherOrSysUi(pkg) || !Prefs.blockApplies(this, pkg)) {
            pauseCapture(); return MOTION_INTERVAL_MS
        }

        // חזרה לאפליקציה שנחסמה לאחרונה
        if (recentlyBlocked("pkg:$pkg", now)) { showOverlay(pkg, remember = false); return MOTION_INTERVAL_MS }

        // אפליקציות שכל מטרתן וידאו — חסימה מיידית (עובד גם בלי הרשאת צילום)
        if (pkg in VIDEO_APPS) { showOverlay(pkg); return MOTION_INTERVAL_MS }

        // אפליקציות שבהן תנועה לגיטימית (מצלמה, שיחות וידאו, ניווט, משחקים)
        if (isMotionExempt(pkg)) { pauseCapture(); return MOTION_INTERVAL_MS }

        val g = grabber ?: return MOTION_INTERVAL_MS
        if (g.resume()) detector.reset()
        val luma = g.grabLuma()
        val touchRecent = now - lastTouch < TOUCH_SCROLL_MS
        val video = if (luma == null) { detector.idle(); false }
            else detector.analyze(luma, g.gridW, g.gridH, touchRecent)
        if (pkg != lastLogPkg) { Log.d(TAG, "fg=$pkg"); lastLogPkg = pkg }
        Log.d(TAG, "${detector.lastInfo}${if (touchRecent) " touch" else ""}")
        if (video) showOverlay(pkg)
        return MOTION_INTERVAL_MS
    }

    private fun pauseCapture() {
        grabber?.pause()
        detector.reset()
    }

    // ───────────────────────── זיהוי אפליקציה בחזית ─────────────────────────

    /** קורא רק אירועים חדשים מאז הבדיקה הקודמת, וזוכר את האחרונה שעלתה לחזית. */
    private fun currentForegroundPackage(): String? {
        val now = System.currentTimeMillis()
        val from = if (lastQuery == 0L || lastQuery > now) now - FIRST_LOOKBACK_MS else lastQuery - 1_000L
        try {
            val events = usage.queryEvents(from, now)
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) lastFg = e.packageName
            }
            lastQuery = now
        } catch (_: Exception) {}
        return lastFg
    }

    private fun resolveHome(): String? = try {
        packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
        )?.activityInfo?.packageName
    } catch (_: Exception) { null }

    private fun isLauncherOrSysUi(pkg: String) =
        pkg == "com.android.systemui" || pkg.contains("launcher", ignoreCase = true)

    private fun isMotionExempt(pkg: String): Boolean = exemptCache.getOrPut(pkg) {
        if (pkg in MOTION_EXEMPT_APPS) return@getOrPut true
        if (EXEMPT_NAME_HINTS.any { pkg.contains(it, ignoreCase = true) }) return@getOrPut true
        try {
            val info = packageManager.getApplicationInfo(pkg, 0)
            @Suppress("DEPRECATION")
            info.category == ApplicationInfo.CATEGORY_GAME ||
                (info.flags and ApplicationInfo.FLAG_IS_GAME) != 0
        } catch (_: Exception) { false }
    }

    private fun sysDimen(name: String): Int = try {
        val id = resources.getIdentifier(name, "dimen", "android")
        if (id > 0) resources.getDimensionPixelSize(id) else 0
    } catch (_: Exception) { 0 }

    // ───────────────────────── זיכרון חסימות ─────────────────────────

    private fun rememberBlock(pkg: String) {
        blockMemory["pkg:$pkg"] = SystemClock.uptimeMillis() + REMEMBER_APP_MS
    }

    private fun recentlyBlocked(key: String, now: Long) = (blockMemory[key] ?: 0L) > now

    // ───────────────────────── חלון חסימה ─────────────────────────

    private fun params() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.OPAQUE
    ).apply { gravity = Gravity.CENTER }

    private fun showOverlay(pkg: String, remember: Boolean = true) {
        if (overlay != null) return
        val view = buildBlockView()
        try {
            wm.addView(view, params())
            view.requestFocus()
            overlay = view
            blockedPackage = pkg
            if (remember) rememberBlock(pkg)
            grabber?.pause()
            updateAudio()
        } catch (_: Exception) {
            overlay = null
        }
    }

    private fun buildBlockView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0B0B0F"))
            setPadding(dp(32), dp(32), dp(32), dp(32))
            isFocusableInTouchMode = true
        }
        val title = TextView(this).apply {
            text = getString(R.string.block_title)
            setTextColor(Color.WHITE); textSize = 24f; gravity = Gravity.CENTER
        }
        val sub = TextView(this).apply {
            text = getString(R.string.block_text)
            setTextColor(Color.parseColor("#AEB2C0")); textSize = 15f; gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(28))
        }
        val btn = Button(this).apply {
            text = getString(R.string.block_home)
            setOnClickListener { dismissToHome() }
        }
        root.addView(title)
        root.addView(sub)
        root.addView(btn)
        root.setOnKeyListener { _, keyCode, e ->
            if (keyCode == KeyEvent.KEYCODE_BACK && e.action == KeyEvent.ACTION_UP) { dismissToHome(); true }
            else keyCode == KeyEvent.KEYCODE_BACK
        }
        return root
    }

    private fun dismissToHome() {
        cooldownUntil = SystemClock.uptimeMillis() + COOLDOWN_MS
        hideOverlay()
        try {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
    }

    private fun hideOverlay() {
        overlay?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        overlay = null
        blockedPackage = null
        detector.reset()
        updateAudio()
    }

    /** מחזיק פוקוס שמע כל עוד מוצגת חסימה, כדי שרוב הנגנים יושהו. */
    private fun updateAudio() {
        val need = overlay != null
        if (need && focusRequest == null) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener { }
                .build()
            focusRequest = req
            audio.requestAudioFocus(req)
        } else if (!need && focusRequest != null) {
            focusRequest?.let { audio.abandonAudioFocusRequest(it) }
            focusRequest = null
        }
    }

    // ───────────────────────── שירות קדמי ─────────────────────────

    private fun startInForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.app_name), NotificationManager.IMPORTANCE_MIN)
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_block)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        running = false
        hasProjection = false
        handler.removeCallbacks(tick)
        handler.removeCallbacks(reacquire)
        hideOverlay()
        touchView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        touchView = null
        grabber?.release(); grabber = null
        val p = projection
        projection = null
        try { p?.stop() } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        @Volatile var running = false
        @Volatile var hasProjection = false      // יש כרגע הרשאת צילום פעילה
        @Volatile var requestStartedAt = 0L      // מתי התחילה בקשת ההרשאה הנוכחית (0 = אין)

        /** בקשת הרשאה בתהליך. פג אחרי REQUEST_TIMEOUT_MS, כדי שלא ייתקע לתמיד. */
        val requestInFlight: Boolean
            get() = requestStartedAt != 0L &&
                SystemClock.uptimeMillis() - requestStartedAt < REQUEST_TIMEOUT_MS

        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "videoguard_capture"
        private const val TAG = "VideoGuard"
        const val TOUCH_SCROLL_MS = 5_000L        // אחרי נגיעה: הזזה נחשבת גלילה עד 5 שניות

        const val MOTION_INTERVAL_MS = 350L
        const val IDLE_INTERVAL_MS = 2_000L        // מסך כבוי
        const val COOLDOWN_MS = 1500L
        const val REMEMBER_APP_MS = 60_000L
        const val FIRST_LOOKBACK_MS = 30 * 60_000L // בהפעלה: למצוא את האפליקציה שכבר בחזית
        const val REQUEST_TIMEOUT_MS = 20_000L
        const val REACQUIRE_FIRST_MS = 8_000L      // ניסיון ראשון אחרי שההרשאה נעצרה
        const val REACQUIRE_MIN_MS = 30_000L       // ואז 30ש', 60ש', ... עד 10 דקות
        const val REACQUIRE_MAX_MS = 600_000L

        fun start(c: Context, code: Int, data: Intent) {
            val i = Intent(c, CaptureService::class.java)
                .putExtra(EXTRA_CODE, code).putExtra(EXTRA_DATA, data)
            c.startForegroundService(i)
        }

        /** אפליקציות שמיועדות בעיקר לצפייה בוידאו — חסימה מיידית בכניסה. */
        val VIDEO_APPS = setOf(
            "com.google.android.youtube",
            "com.google.android.apps.youtube.music",
            "app.revanced.android.youtube",
            "com.netflix.mediaclient",
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill",
            "com.twitch.android.app",
            "tv.twitch.android.app",
            "com.disney.disneyplus",
            "com.amazon.avod.thirdpartyclient",
            "com.hbo.hbonow",
            "com.apple.atve.androidtv.appletv",
            "org.videolan.vlc",
            "com.mxtech.videoplayer.ad",
            "com.mxtech.videoplayer.pro",
            "com.google.android.apps.youtube.kids",
            "com.vimeo.android.videoapp",
            "com.dailymotion.dailymotion",
            "com.google.android.videos",
            // נגנים שמנגנים אופליין (עם מפענחים משלהם — שכבה 1 לא חוסמת אותם)
            "is.xyz.mpv",
            "org.xbmc.kodi",
            "com.brouken.player",
            "dev.anilbeesetti.nextplayer",
            "org.courville.nova",
            "com.bsplayer.bspandroid.free",
            "com.kmplayer",
            "com.stremio.one"
        )

        /** פטורות מזיהוי תנועה: מצלמה, שיחות וידאו, ניווט. */
        val MOTION_EXEMPT_APPS = setOf(
            "com.android.camera", "com.android.camera2", "com.google.android.GoogleCamera",
            "com.sec.android.app.camera", "com.motorola.camera3", "com.oneplus.camera",
            "com.miui.camera", "com.huawei.camera", "com.oppo.camera", "com.mediatek.camera",
            "us.zoom.videomeetings", "com.google.android.apps.tachyon",
            "com.microsoft.teams", "com.skype.raider", "com.cisco.webex.meetings",
            "org.jitsi.meet",
            "com.google.android.apps.maps", "com.waze"
        )

        val EXEMPT_NAME_HINTS = listOf("camera")
    }
}
