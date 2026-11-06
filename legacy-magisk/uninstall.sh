#!/system/bin/sh
# רץ כשמוחקים את המודול ב-Magisk: מחזיר את סמל ההטלה לשורת המצב.
# (הקבצים והמקודדים חוזרים לבד — הם רק הוצגו מעל המקור.)
(
  until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 2; done
  cur=$(settings get secure icon_blacklist 2>/dev/null)
  [ "$cur" = "null" ] && exit 0
  new=$(echo ",$cur," | sed 's/,cast,/,/g; s/^,//; s/,$//')
  if [ -z "$new" ]; then settings delete secure icon_blacklist; else settings put secure icon_blacklist "$new"; fi
) &
