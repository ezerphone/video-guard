#!/system/bin/sh
# VideoGuard boot guard. Runs from vg_swcodec.rc in Magisk's SELinux domain (u:r:magisk:s0);
# init refuses to exec a script without an explicit seclabel.
#  pre: runs at post-fs-data.
#       - If a partition change is "pending" (set before the reboot that follows editing
#         media_codecs files) and the system never reached boot_completed, after 3 boots it
#         switches to "restored" mode.
#       - In restored mode (every boot, until the app's "Cancel all" deletes /data/vg) the
#         original media_codecs files are bind-mounted over the edited ones, and the original
#         swcodec XML over our filtered copy, so the APEX bind in the rc shows the original too.
#  ok : runs after sys.boot_completed=1 and clears the pending marker.
D=/data/vg
L=$D/boot_guard.log
BK=/system/etc/vg/backup
mkdir -p $D 2>/dev/null
case "$1" in
pre)
  if [ -f $D/pending ] && [ ! -f $D/restored ]; then
    n=$(cat $D/attempts 2>/dev/null); [ -z "$n" ] && n=0
    n=$((n+1)); echo $n > $D/attempts
    echo "pre: boot attempt $n with pending changes" >> $L
    if [ "$n" -ge 3 ]; then
      echo "pre: 3 failed boots, switching to restored mode" >> $L
      touch $D/restored
      rm -f $D/pending $D/attempts
    fi
  fi
  if [ -f $D/restored ]; then
    echo "pre: restored mode, bind-mounting originals" >> $L
    for part in vendor odm product system_ext system; do
      for f in $BK/$part/media_codecs*.xml; do
        [ -f "$f" ] || continue
        mount -o bind "$f" "/$part/etc/${f##*/}" >> $L 2>&1
      done
    done
    if [ -f $BK/swcodec.orig.xml ] && [ -f /system/etc/vg/swcodec.xml ]; then
      mount -o bind $BK/swcodec.orig.xml /system/etc/vg/swcodec.xml >> $L 2>&1
    fi
  fi
  ;;
ok)
  [ -f $D/pending ] && echo "ok: boot completed, clearing pending" >> $L
  rm -f $D/pending $D/attempts
  ;;
esac
exit 0
