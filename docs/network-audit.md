# Network audit: what Senda connects to on its own

This is how Senda's claim "it does not contact Google on its own" is checked. It should be repeated on every GeckoView
update, because a new Firefox version can bring new background connections (on October 11, 2026, Firefox 157's Safe
Browsing "global cache" kept downloading a Google list with Safe Browsing turned off).

Only host names and ports are recorded, never content. Do not browse with Senda while the proxy is set: your own
browsing would be recorded too.

## Setup

1. On the computer: `python3 -I scripts/network-audit/hostname_proxy.py hosts.log` (listens on `127.0.0.1:8899`).
2. `adb reverse tcp:8899 tcp:8899`, so the phone reaches it through USB without touching the phone's network settings.
3. In Senda: menu → Tor & Proxy → **HTTP/HTTPS proxy**, host `127.0.0.1`, port `8899`, Save. Gecko applies the proxy
   from startup, so every Gecko request, including background ones, goes through it.
4. Find Senda's uid: `adb shell dumpsys package org.senda.browser | grep userId=`.

## Measurement

1. Force-stop and start Senda, then leave it idle for at least 35 minutes (startup plus one 30-minute Safe Browsing
   update cycle), while running `scripts/network-audit/socket_watch.sh <uid> 2100 >> bypass.log`.
2. Open a few neutral pages that use no Google services (for example es.wikipedia.org, www.fsf.org, codeberg.org,
   www.gnu.org) and watch for 90 seconds each.
3. Leave it idle again until at least 75 minutes in total.
4. Optionally, in `about:url-classifier`, check that the `google4` and `google5` providers have no recent update.

## Pass criteria

- `hosts.log` contains no Google host (`google`, `googleapis`, `gstatic`, `1e100.net`, `googleusercontent`) that was
  not requested by a page you opened.
- `bypass.log` is empty: nothing went around the proxy.
- With Tor on (a separate cold start, no proxy), every address in `socket_watch.sh` output is a Tor relay
  (for example, check it with `https://onionoo.torproject.org/summary?search=<ip>`).

## Cleanup

Set Senda's connection back to what it was, `adb reverse --remove tcp:8899`, stop the proxy and delete `hosts.log` and
`bypass.log`.

## Results

| Date | Senda / GeckoView | Duration | Google hosts | Bypasses | Notes |
|---|---|---|---|---|---|
| 2026-10-11 | 0.2.0-beta.8 + Safe Browsing removed (commit 5e78ca9) / GeckoView 157.0.1, moto g34 | 75 min (35 idle, 4 pages, 34 idle) + 2 min with Tor | 0 | 0 | 30 connections, all to the four pages opened; `about:url-classifier` lists only the `mozilla` provider; with Tor, 3 connections, all Tor relays |
