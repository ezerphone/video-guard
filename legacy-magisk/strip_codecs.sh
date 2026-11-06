# פונקציה משותפת ל-customize.sh ול-service.sh (נטענת עם "."), רצה תחת busybox של Magisk.
#
# strip_video_decoders SRC DEST
#   כותב ל-DEST עותק של SRC בלי בלוקי <MediaCodec> של וידאו בתוך <Decoders>.
#   מזהה וידאו גם כש-type= נמצא בשורה אחרת של התגית וגם בצורה <Type name="video/...">.
#   שומר את כל מקודדי האודיו ואת כל ה-Encoders.
#   מחזיר: 0 = נכתב ושונה, 2 = נכתב אך אין מה להסיר, 1 = שגיאה (DEST לא נוצר).

strip_video_decoders() {
  src="$1"; dest="$2"
  [ -f "$src" ] || return 1
  mkdir -p "$(dirname "$dest")" || return 1
  awk '
    {
      line = $0
      if (!blk) {
        if (line ~ /<Decoders[ \t>]/ || line ~ /<Decoders$/) dec = 1
        if (line ~ /<\/Decoders>/ || line ~ /<Encoders[ \t>]/ || line ~ /<Encoders$/) dec = 0
        if (dec && (line ~ /<MediaCodec[ \t>]/ || line ~ /<MediaCodec$/)) {
          blk = 1; instart = 1; done = 0; buf = ""
        } else { print line; next }
      }
      buf = buf line "\n"
      if (instart) {
        s = line
        p = index(s, "<MediaCodec"); if (p > 0) s = substr(s, p)
        q = index(s, ">")
        if (q > 0) { instart = 0; if (q > 1 && substr(s, q - 1, 1) == "/") done = 1 }
      }
      if (!instart && !done && line ~ /<\/MediaCodec>/) done = 1
      if (done) {
        if (buf !~ /type=.video\// && buf !~ /<Type[ \t]+name=.video\//) printf "%s", buf
        buf = ""; blk = 0; done = 0
      }
    }
    END { if (blk) printf "%s", buf }
  ' "$src" > "$dest.tmp" 2>/dev/null || { rm -f "$dest.tmp"; return 1; }
  # בדיקת שפיות: הפלט לא ריק ועדיין קובץ XML
  if [ ! -s "$dest.tmp" ] || ! grep -q '<' "$dest.tmp"; then rm -f "$dest.tmp"; return 1; fi
  mv -f "$dest.tmp" "$dest" || return 1
  cmp -s "$src" "$dest" && return 2
  return 0
}
