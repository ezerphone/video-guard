package android.ezerphone.videoguard

import android.content.Context
import android.content.Intent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import java.io.File

/** מה המצב עכשיו, בלי root (כדי שפתיחת המסך לא תקפיץ בקשת הרשאה). */
object Status {
    /** החסימה במערכת בוצעה (הסימון נכתב רק בסוף כתיבה מוצלחת). */
    fun blocked() = File(Codecs.MARK).exists()
    /** יש גיבוי של קבצי המערכת (גם אחרי פעולה שנעצרה באמצע) — אפשר להחזיר. */
    fun canRestore() = blocked() || File(Codecs.BK).exists()
    /** בוצע שינוי בקבצים ועוד לא הייתה הפעלה מחדש מאז. */
    fun waitingReboot(c: Context) = Prefs.rebootBoot(c).let { it != -1 && it == TaskRunner.bootCount(c) }
    fun scanning(c: Context) = Prefs.serviceOn(c)
    fun protectedOn(c: Context) = Admin.isOwner(c)
}

class MainActivity : Guarded() {
    private lateinit var body: LinearLayout
    override val autoFit = true

    override fun build() {
        body = St.screen(this, "חוסם וידאו", gear = { startActivity(Intent(this, SettingsActivity::class.java)) })
    }

    override fun refresh() {
        if (TaskRunner.needsAttention()) { startActivity(Intent(this, TaskActivity::class.java)); return }
        if (Prefs.serviceOn(this) && !ScanService.running) ScanService.start(applicationContext)
        body.removeAllViews()

        val blocked = Status.blocked()
        val waiting = Status.waitingReboot(this)
        val scanning = Status.scanning(this)
        val prot = Status.protectedOn(this)
        val on = listOf(blocked, scanning, prot).count { it }

        // ── כרטיס מצב ──
        val (heroIcon, heroBg, heroTitle, heroSub) = when {
            on == 3 && !waiting -> listOf(R.drawable.ic_shield_on, St.OK_SOFT, "הווידאו חסום", "כל ההגנות פעילות")
            on == 0 -> listOf(R.drawable.ic_shield_off, St.OFF_SOFT, "החסימה כבויה", "הפעל את מה שאתה צריך")
            else -> listOf(R.drawable.ic_shield_part, St.PRIMARY_SOFT, "החסימה פועלת חלקית",
                if (waiting) "צריך להפעיל מחדש את הטלפון" else "אפשר להפעיל עוד הגנות")
        }
        val hero = St.row(this).apply {
            background = St.round(this@MainActivity, heroBg as Int, 24)
            setPadding(St.dp(this@MainActivity, 18), St.dp(this@MainActivity, 18), St.dp(this@MainActivity, 18), St.dp(this@MainActivity, 18))
            addView(St.icon(this@MainActivity, heroIcon as Int, 44))
            addView(View(this@MainActivity), St.lp(St.dp(this@MainActivity, 16), 1))
            addView(St.col(this@MainActivity).apply {
                addView(St.text(this@MainActivity, heroTitle as String, 22f, St.TEXT, true))
                addView(St.text(this@MainActivity, heroSub as String, 15f, St.MUTED))
            }, St.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        body.addView(hero)

        // הגובה של כל כרטיס זהה בכל מצב (אותו תיאור, אותו רוחב לכפתור/תגית, שורת האפליקציות תמיד
        // מוצגת), כדי שהפעלה של משהו לא תגדיל את התוכן ותקטין בגללה את כל המסך.

        // ── חסימה במערכת ──
        body.addView(card(R.drawable.ic_film, "חסימה במערכת", "משבית את נגני הווידאו של הטלפון",
            when {
                blocked && waiting -> St.chip(this, "ממתין", St.PRIMARY, St.PRIMARY_SOFT)
                blocked -> onChip()
                else -> St.button(this, "הפעל", small = true) {
                    St.sheet(this, "להפעיל חסימה במערכת?",
                        "נגני הווידאו המובנים בטלפון יושבתו. החסימה מתחילה אחרי הפעלה מחדש של הטלפון.\nאפשר לבטל בכל זמן מההגדרות.",
                        "הפעל") { TaskActivity.start(this, TaskRunner.Kind.BLOCK) }
                }
            }))

        // ── בדיקת אפליקציות ──
        body.addView(card(R.drawable.ic_apps, "בדיקת אפליקציות", "חוסם אפליקציות שפותחות וידאו",
            if (scanning) onChip() else St.button(this, "הפעל", small = true) {
                Prefs.setServiceOn(this, true); ScanService.start(applicationContext); refresh()
            }))
        val n = Prefs.suspended(this).size
        val l = St.list(this)
        l.addView(St.listRow(this, "אפליקציות חסומות: $n",
            end = St.icon(this, R.drawable.ic_chevron, 20)) { startActivity(Intent(this, AppsActivity::class.java)) })
        body.addView(l, spaced())

        // ── הגנה ──
        body.addView(card(R.drawable.ic_lock, "הגנה", "מונע את הסרת האפליקציה",
            if (prot) onChip() else St.button(this, "הפעל", small = true) {
                St.sheet(this, "להפעיל הגנה?",
                    "אי אפשר יהיה להסיר את האפליקציה, וייחסמו גם הדברים שבחרת בהגדרות.\n" +
                        "לפני כן צריך להסיר מהטלפון את כל החשבונות (כמו חשבון Google). אפשר להוסיף אותם בחזרה אחר כך." +
                        if (Prefs.lockAdb(this)) "\nחיבור למחשב (ADB) ייחסם מיד." else "",
                    "הפעל") { TaskActivity.start(this, TaskRunner.Kind.PROTECT) }
            }))

        // ── קרדיט ──
        body.addView(View(this), St.lp(h = 0, weight = 1f))
        body.addView(St.text(this, "פותח ע״י $EMAIL\nלהכשרת נגנים, צריבת רוט ועוד ניתן ליצור קשר במייל", 12f, St.MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, St.dp(this@MainActivity, 20), 0, St.dp(this@MainActivity, 4))
            setOnClickListener {
                try { startActivity(Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:$EMAIL"))) } catch (_: Exception) {}
            }
        })
    }

    private fun onChip() = St.chip(this, "פועל", St.OK, St.OK_SOFT)

    private fun spaced() = St.lp().apply { topMargin = St.dp(this@MainActivity, 10) }

    /** כרטיס: אייקון, כותרת ותיאור, ובצד כפתור או תגית ברוחב קבוע. */
    private fun card(icon: Int, title: String, desc: String, end: android.widget.TextView): View {
        val r = St.row(this)
        r.background = St.round(this, St.SURFACE, 20)
        r.setPadding(St.dp(this, 16), St.dp(this, 14), St.dp(this, 16), St.dp(this, 14))
        r.addView(St.iconBox(this, icon, box = 40, size = 22, radius = 12))
        r.addView(View(this), St.lp(St.dp(this, 14), 1))
        r.addView(St.col(this).apply {
            addView(St.text(this@MainActivity, title, 16f, St.TEXT, true))
            addView(St.text(this@MainActivity, desc, 13f, St.MUTED))
        }, St.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(View(this), St.lp(St.dp(this, 10), 1))
        end.gravity = Gravity.CENTER
        end.setPadding(0, end.paddingTop, 0, end.paddingBottom)
        r.addView(end, LinearLayout.LayoutParams(St.dp(this, 80), ViewGroup.LayoutParams.WRAP_CONTENT))
        r.layoutParams = spaced()
        return r
    }

    companion object { const val EMAIL = "ezerphone0@gmail.com" }
}

/** אפליקציות חסומות ומותרות. */
class AppsActivity : Guarded() {
    private lateinit var body: LinearLayout
    private var tab = 0

    override fun build() { body = St.screen(this, "אפליקציות", back = true) }

    override fun refresh() {
        body.removeAllViews()
        val blocked = Prefs.suspended(this).map { it to Scanner.label(this, it) }.sortedBy { it.second }
        val allowed = Prefs.exempt(this).map { it to Scanner.label(this, it) }.sortedBy { it.second }

        val tabs = St.row(this).apply { background = St.round(this@AppsActivity, St.OFF_SOFT, 24); setPadding(St.dp(this@AppsActivity, 4), St.dp(this@AppsActivity, 4), St.dp(this@AppsActivity, 4), St.dp(this@AppsActivity, 4)) }
        listOf("חסומות (${blocked.size})", "מותרות (${allowed.size})").forEachIndexed { i, t ->
            tabs.addView(St.text(this, t, 15f, if (i == tab) St.TEXT else St.MUTED, i == tab).apply {
                gravity = Gravity.CENTER
                setPadding(0, St.dp(this@AppsActivity, 10), 0, St.dp(this@AppsActivity, 10))
                if (i == tab) background = St.round(this@AppsActivity, St.SURFACE, 20)
                setOnClickListener { tab = i; refresh() }
            }, St.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        body.addView(tabs)

        val items = if (tab == 0) blocked else allowed
        if (items.isEmpty()) {
            body.addView(St.text(this, if (tab == 0) "אין אפליקציות חסומות" else "אין אפליקציות מותרות", 15f, St.MUTED)
                .apply { gravity = Gravity.CENTER; setPadding(0, St.dp(this@AppsActivity, 40), 0, St.dp(this@AppsActivity, 40)) })
        } else {
            val l = St.list(this)
            St.addRows(l, items.map { (p, label) ->
                if (tab == 0) St.listRow(this, label, "חסומה", lead = appIcon(p),
                    end = St.button(this, "התר", 'o', small = true) {
                        St.sheet(this, "להתיר את $label?", "היא לא תיחסם שוב. אפשר לחסום אותה מחדש בלשונית \"מותרות\".", "התר") {
                            work { Prefs.setExempt(applicationContext, p, true); Scanner.unsuspend(applicationContext, listOf(p)) }
                        }
                    })
                else St.listRow(this, label, "מותרת", lead = appIcon(p),
                    end = St.button(this, "חסום", 'o', small = true) {
                        work {
                            Prefs.setExempt(applicationContext, p, false)
                            Scanner.suspend(applicationContext, listOf(Scanner.Finding(p, label, listOf("נחסמה ידנית"))))
                        }
                    })
            })
            body.addView(l, St.lp().apply { topMargin = St.dp(this@AppsActivity, 12) })
            if (tab == 0) body.addView(St.text(this, "אפליקציה שתתיר לא תיחסם שוב.", 13f, St.MUTED)
                .apply { gravity = Gravity.CENTER; setPadding(0, St.dp(this@AppsActivity, 10), 0, 0) })
        }
        body.addView(View(this), St.lp(h = 0, weight = 1f))
        body.addView(St.button(this, "בדוק את כל האפליקציות עכשיו", 'o') { TaskActivity.start(this, TaskRunner.Kind.SCAN) },
            St.lp().apply { topMargin = St.dp(this@AppsActivity, 24) })
    }

    private fun appIcon(p: String): View = try {
        android.widget.ImageView(this).apply {
            setImageDrawable(packageManager.getApplicationIcon(p))
            layoutParams = LinearLayout.LayoutParams(St.dp(this@AppsActivity, 44), St.dp(this@AppsActivity, 44))
        }
    } catch (_: Exception) { St.iconBox(this, R.drawable.ic_apps, radius = 12) }

    private fun work(f: () -> Unit) = Root.bg("vg-apps") {
        try { f() } catch (t: Throwable) { VgLog.add("✗ ${t.message}") }
        runOnUiThread { if (!isFinishing) refresh() }
    }
}

class SettingsActivity : Guarded() {
    private lateinit var body: LinearLayout
    override val autoFit = true

    override fun build() { body = St.screen(this, "הגדרות", back = true) }

    override fun refresh() {
        body.removeAllViews()
        val chev = { St.icon(this, R.drawable.ic_chevron, 20) }

        body.addView(St.group(this, "קוד"))
        body.addView(St.list(this).also { l ->
            l.addView(St.listRow(this, "שינוי קוד", end = chev()) {
                startActivity(Intent(this, LockActivity::class.java).putExtra("change", true))
            })
        })

        body.addView(St.group(this, "בדיקת אפליקציות"))
        body.addView(St.list(this).also { l ->
            St.addRows(l, listOf(
                St.listRow(this, "בדיקת אפליקציות חדשות", end = St.toggle(this, Prefs.serviceOn(this)) { v ->
                    Prefs.setServiceOn(this, v)
                    if (v) ScanService.start(applicationContext) else ScanService.stop(applicationContext)
                }),
                St.listRow(this, "אפליקציות חסומות ומותרות", end = chev()) { startActivity(Intent(this, AppsActivity::class.java)) }
            ))
        })

        body.addView(St.group(this, "הגנה"))
        body.addView(St.list(this).also { l ->
            St.addRows(l, listOf(
                lockRow("מנע מצב בטוח", Prefs.lockSafeBoot(this)) { Prefs.setLockSafeBoot(this, it) },
                lockRow("מנע איפוס יצרן", Prefs.lockFactoryReset(this)) { Prefs.setLockFactoryReset(this, it) },
                lockRow("מנע חיבור למחשב (ADB)", Prefs.lockAdb(this)) { Prefs.setLockAdb(this, it) }
            ))
        })

        body.addView(St.group(this, "ביטול"))
        body.addView(St.list(this).also { l ->
            val rows = ArrayList<View>()
            if (Status.canRestore()) rows += St.listRow(this, "החזר את נגני הווידאו") {
                St.sheet(this, "להחזיר את נגני הווידאו?", "החסימה במערכת תבוטל אחרי הפעלה מחדש של הטלפון.", "החזר") {
                    TaskActivity.start(this, TaskRunner.Kind.RESTORE)
                }
            }
            if (Status.protectedOn(this)) rows += St.listRow(this, "בטל את כל ההגנה", "אפשר יהיה להסיר את האפליקציה", titleColor = St.BAD) {
                St.sheet(this, "לבטל את ההגנה?", "כל האפליקציות החסומות ישוחררו, ואפשר יהיה להסיר את האפליקציה. החסימה במערכת לא משתנה.",
                    "בטל הגנה", danger = true) { TaskActivity.start(this, TaskRunner.Kind.UNPROTECT) }
            }
            if (rows.isEmpty()) rows += St.listRow(this, "אין מה לבטל", titleColor = St.MUTED)
            St.addRows(l, rows)
        })

    }

    private fun lockRow(t: String, on: Boolean, set: (Boolean) -> Unit) = St.listRow(this, t, end = St.toggle(this, on) { v ->
        set(v)
        val app = applicationContext
        if (Admin.isOwner(app)) Root.bg { Admin.applyLocks(app) }
    })
}
