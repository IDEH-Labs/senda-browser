#!/bin/bash
# Records every socket of an Android app that goes to an address other than loopback, every 2 seconds.
# Usage: socket_watch.sh <uid of the app> <seconds> >> bypass.log
# With Senda set to use hostname_proxy.py (through `adb reverse`), the result must be empty: any line is a connection
# that did not go through the proxy. With Tor on, every line must be a Tor relay.
set -euo pipefail
uid=$1
end=$(( $(date +%s) + $2 ))
while [ "$(date +%s)" -lt "$end" ]; do
  adb shell "cat /proc/net/tcp /proc/net/tcp6 /proc/net/udp /proc/net/udp6" 2>/dev/null |
    awk -v uid="$uid" -v t="$(date +%T)" '$8 == uid && $3 !~ /:0000$/ &&
      $3 !~ /^(0100007F|0000000000000000FFFF00000100007F|00000000000000000000000001000000):/ { print t, $3, $4 }'
  sleep 2
done
