#!/sbin/sh
# VideoGuard Magisk module installer
# 1) מתקין את אפליקציית המערכת (כבר נמצאת ב-system/app/VideoGuard/)
# 2) מסיר את מקודדי הווידאו מקובצי media_codecs (הגנה כפולה)
#    /odm ו-APEX של מקודדי התוכנה מטופלים בכל אתחול ב-service.sh (bind mount),
#    כי magic mount של Magisk לא מכסה אותם.

SKIPUNZIP=0

ui_print "- VideoGuard: מתקין הגנת וידאו חזותית + הסרת מקודדים"

# ---------- ודא שקובץ ה-APK קיים ----------
APK="$MODPATH/system/app/VideoGuard/VideoGuard.apk"
if [ ! -f "$APK" ]; then
  ui_print "  ! חסר VideoGuard.apk בחבילה — בונים ב-GitHub Actions ואורזים אוטומטית"
  ui_print "  ! אם בניתם ידנית, שימו את ה-APK ב-system/app/VideoGuard/VideoGuard.apk"
else
  ui_print "  + אפליקציית VideoGuard הותקנה כאפליקציית מערכת"
fi

# ---------- הסרת מקודדי וידאו (magic mount) ----------
. "$MODPATH/strip_codecs.sh"

# מקור -> יעד בתוך המודול.
# הערה: בעדכון מודול, הקבצים הנקראים כאן הם כבר הגרסה המעובדת של המודול הקודם —
# העיבוד אידמפוטנטי, ולכן שומרים כל קובץ שעובד (גם אם לא השתנה), כדי שלא יחזור המקור.
process_dir() {
  srcdir="$1"; destdir="$2"
  [ -d "$srcdir" ] || return 0
  for f in "$srcdir"/media_codecs*.xml; do
    [ -f "$f" ] || continue
    strip_video_decoders "$f" "$destdir/$(basename "$f")"
    case $? in
      0) ui_print "  + הוסרו מפענחי וידאו: $f" ;;
      2) ;;
      *) ui_print "  ! לא עובד: $f" ;;
    esac
  done
}

ui_print "- מסיר מקודדי וידאו (מערכתיים)"
process_dir /vendor/etc        "$MODPATH/system/vendor/etc"
[ -L /system/vendor ] || process_dir /system/vendor/etc "$MODPATH/system/vendor/etc"
process_dir /product/etc       "$MODPATH/system/product/etc"
process_dir /system_ext/etc    "$MODPATH/system/system_ext/etc"
process_dir /system/etc        "$MODPATH/system/etc"

# הרשאות לכל הקבצים שנוצרו
set_perm_recursive "$MODPATH/system" 0 0 0755 0644
[ -f "$APK" ] && set_perm "$APK" 0 0 0644 u:object_r:system_file:s0

ui_print "- סיום. אתחל את המכשיר."
ui_print "  לאחר האתחול ההגנה תתחיל אוטומטית (appops + שומר ב-service.sh)."
