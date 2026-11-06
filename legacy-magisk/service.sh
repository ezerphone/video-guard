#!/system/bin/sh
# רץ בכל אתחול (late_start service) עם הרשאות root.
#  1) מסיר מפענחי וידאו גם מ-/odm ומ-APEX של מקודדי התוכנה (bind mount), ומפעיל מחדש את mediaserver
#  2) מסתיר את סמל ההקלטה/הטלה בשורת המצב
#  3) שומר קבוע: מחזיר הרשאות, מבטל השבתה, ומפעיל מחדש את ההגנה אם נעצרה בכפייה

MODDIR=${0%/*}
PKG=online.ezerphone.videoguard
. "$MODDIR/strip_codecs.sh"

# ---------- 1) מקודדי תוכנה (APEX) ו-/odm ----------
# הקבצים נקראים מחדש מהמקור בכל אתחול (כך גם עדכון APEX של גוגל מטופל).
# ה-mount חייב להיעשות במרחב ה-mount של init (pid 1) — שם רץ mediaserver.
# mount רגיל מתוך הסקריפט נשאר במרחב אחר ו-mediaserver לא רואה אותו.
bind_mount() {
  nsenter -t 1 -m -- mount -o bind "$1" "$2" 2>/dev/null ||
    busybox nsenter -t 1 -m -- mount -o bind "$1" "$2" 2>/dev/null ||
    mount -o bind "$1" "$2"
}

OVR="$MODDIR/boot_override"
rm -rf "$OVR"; mkdir -p "$OVR"
n=0; mounted=0
for dir in /odm/etc /apex/com.android.media.swcodec/etc; do
  [ -d "$dir" ] || continue
  for f in "$dir"/media_codecs*.xml; do
    [ -f "$f" ] || continue
    out="$OVR/$n.xml"; n=$((n+1))
    if strip_video_decoders "$f" "$out"; then
      ctx=$(ls -Z "$f" 2>/dev/null | awk '{print $1}')
      chmod 0644 "$out"
      chcon "${ctx:-u:object_r:system_file:s0}" "$out" 2>/dev/null
      bind_mount "$out" "$f" && mounted=1
    else
      rm -f "$out"
    fi
  done
done
# mediaserver מחזיק את רשימת המקודדים בזיכרון — הפעלה מחדש כדי שיקרא את הקבצים המעובדים
[ "$mounted" = 1 ] && setprop ctl.restart media

# ---------- המתנה לסיום האתחול ----------
until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 2; done
sleep 5

# ---------- 2) הסתרת סמל ההטלה (MediaProjection מציג אותו כל עוד הלכידה פעילה) ----------
cur=$(settings get secure icon_blacklist 2>/dev/null)
[ "$cur" = "null" ] && cur=""
case ",$cur," in
  *,cast,*) ;;
  *) settings put secure icon_blacklist "${cur:+$cur,}cast" ;;
esac

# ---------- 3) שומר ----------
i=0
until pm path "$PKG" >/dev/null 2>&1; do
  i=$((i+1)); [ "$i" -gt 30 ] && break; sleep 2
done

grant() {
  #  PROJECT_MEDIA      -> אישור MediaProjection אוטומטי בלי חלון
  #  GET_USAGE_STATS    -> זיהוי האפליקציה בחזית
  #  SYSTEM_ALERT_WINDOW-> חלון החסימה מעל אפליקציות אחרות
  for OP in PROJECT_MEDIA GET_USAGE_STATS SYSTEM_ALERT_WINDOW; do
    cmd appops get "$PKG" "$OP" 2>/dev/null | grep -q ': allow' || cmd appops set "$PKG" "$OP" allow 2>/dev/null
  done
}

while true; do
  # בוטלה השבתה ידנית של האפליקציה
  cmd package list packages -d 2>/dev/null | grep -qx "package:$PKG" && pm enable "$PKG" >/dev/null 2>&1
  grant
  # השירות לא רץ (אתחול, עצירה בכפייה) -> להפעיל. רק כשהמסך דלוק ולא נעול, כדי שהבקשה לא תיתקע.
  if ! dumpsys activity services "$PKG" 2>/dev/null | grep -q CaptureService; then
    st=$(dumpsys deviceidle 2>/dev/null)
    case "$st" in
      *mScreenOn=false*|*mScreenLocked=true*) ;;
      *) am start --user 0 -n "$PKG/.RequestActivity" >/dev/null 2>&1 ;;
    esac
  fi
  sleep 15
done
