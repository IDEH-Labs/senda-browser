#!/bin/bash
# Network audit (docs/network-audit.md) on the separate e2e app (org.senda.browser.e2e), never the person's Senda.
# Usage: scripts/network-audit/audit_e2e.sh <output folder> [free]
#   free: with JavaScript set to "free only" (GNU LibreJS), to compare with a normal run (F4).
# 75 minutes: 35 idle, four neutral pages (90 s each), idle again. Only host names and ports are recorded.
# The pages are opened only when no call is in progress and the launcher or the e2e app is in front.
set -u
OUT=$1; MODE=${2:-normal}; mkdir -p "$OUT"
APP=org.senda.browser.e2e; HERE=$(dirname "$0")
free() {
  c=$(adb shell dumpsys telephony.registry | grep -c 'mCallState=[12]')
  top=$(adb shell dumpsys activity activities | grep -m1 topResumedActivity)
  [ "$c" = 0 ] && echo "$top" | grep -qE "$(adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME | grep / | cut -d/ -f1 | paste -sd'|')|$APP/"
}
waitfree() { n=0; until free; do sleep 15; n=$((n+1)); [ $n -gt 240 ] && { echo "$(date +%T) phone busy for an hour, stopped" >> "$OUT/run.txt"; exit 3; }; done; }
echo "$(date +%T) start, mode $MODE" > "$OUT/run.txt"
python3 -I "$HERE/hostname_proxy.py" "$OUT/hosts.log" & PROXY=$!
trap 'kill $PROXY 2>/dev/null; adb reverse --remove tcp:8899 2>/dev/null; adb shell am force-stop $APP' EXIT
sleep 1; adb reverse tcp:8899 tcp:8899 >/dev/null
waitfree
adb shell am force-stop $APP; adb shell pm clear $APP >/dev/null
JS=""; [ "$MODE" = free ] && JS='<string name="javascript_mode">FREE</string>'
printf '%s\n' "umask 077; mkdir -p shared_prefs; printf '%s' '<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\" ?><map><string name=\"proxy_mode\">CUSTOM_HTTP</string><string name=\"proxy_host\">127.0.0.1</string><int name=\"proxy_port\" value=\"8899\" />$JS</map>' > shared_prefs/senda_preferences.xml" | adb shell run-as $APP sh
UID_=$(adb shell run-as $APP id -u | tr -d '\r')
# Without the uid the bypass watch would record nothing and look like a pass
case "$UID_" in ''|*[!0-9]*) echo "$(date +%T) no uid, stopped" >> "$OUT/run.txt"; exit 4;; esac
echo "$(date +%T) uid $UID_" >> "$OUT/run.txt"
adb shell am start -W -n $APP/org.senda.browser.MainActivity >/dev/null
bash "$HERE/socket_watch.sh" "$UID_" 4500 >> "$OUT/bypass.log" & WATCH=$!
sleep 2100
for u in https://es.wikipedia.org https://www.fsf.org https://codeberg.org https://www.gnu.org; do
  waitfree; echo "$(date +%T) open $u" >> "$OUT/run.txt"
  adb shell am start -W -a android.intent.action.VIEW -d "$u" -n $APP/org.senda.browser.MainActivity >/dev/null
  sleep 20
  # Control: the watch sees this uid's sockets (to the proxy, through loopback), so an empty bypass.log means no bypass
  n=$(adb shell "cat /proc/net/tcp /proc/net/tcp6" | awk -v uid="$UID_" '$8 == uid' | wc -l)
  echo "$(date +%T) control: $n sockets of uid $UID_" >> "$OUT/run.txt"
  sleep 70
done
wait $WATCH
echo "$(date +%T) end" >> "$OUT/run.txt"
