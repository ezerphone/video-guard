#!/system/bin/sh
# VideoGuard boot guard.
#  pre: runs at post-fs-data. If a partition change is "pending" (set before the reboot that
#       follows editing media_codecs files) and the system never reached boot_completed,
#       after 3 boots the originals are bind-mounted back over the edited files.
#  ok : runs after sys.boot_completed=1 and clears the pending marker.
D=/data/vg
L=$D/boot_guard.log
mkdir -p $D 2>/dev/null
case "$1" in
pre)
  if [ -f $D/pending ]; then
    n=$(cat $D/attempts 2>/dev/null); [ -z "$n" ] && n=0
    n=$((n+1)); echo $n > $D/attempts
    echo "pre: boot attempt $n with pending changes" >> $L
    if [ "$n" -ge 3 ]; then
      echo "pre: restoring originals via bind mount" >> $L
      for part in vendor odm product system_ext system; do
        for f in /system/etc/vg/backup/$part/media_codecs*.xml; do
          [ -f "$f" ] || continue
          mount -o bind "$f" "/$part/etc/${f##*/}" >> $L 2>&1
        done
      done
      touch $D/restored
      rm -f $D/pending
    fi
  fi
  ;;
ok)
  [ -f $D/pending ] && echo "ok: boot completed, clearing pending" >> $L
  rm -f $D/pending $D/attempts
  ;;
esac
exit 0
