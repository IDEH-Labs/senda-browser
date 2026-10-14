# Automated end-to-end tests: design

Status: **implemented** on October 12, 2026 (groups A-E, H, I, J; F with its own procedure). Designed on October 11, 2026, after a day in which manual testing on one
phone found four old bugs that no test had caught: Tor was not started again after Android stopped it, switching to a
direct connection did not take effect, restored tabs could be lost, and uBlock Origin's quick panel never showed the
site in front. Senda's 36 automated tests are JVM tests of internal logic; none of them drives the browser.

The goal of this suite is to catch that kind of bug before a release, on a real phone, without touching anyone's data
and without contacting anything the person running it did not agree to.

## Rules

1. **Never on the real Senda.** The suite runs against a separate app, `org.senda.browser.e2e` (its own data, its own
   tabs, its own settings), installed next to Senda. Every test starts by checking the package name ends in `.e2e`
   and refuses to run otherwise. The existing instrumented tests in `app/src/androidTest` run inside the real app with
   its real data; they stay as they are, guarded by `DestructiveTestGuard`.
2. **Offline by default.** Web pages come from a small HTTP server inside the test process, on `127.0.0.1`. The
   "proxy" used to test network modes is also local and records every request it receives. Tests that need the real
   Tor network (they contact Tor relays and check.torproject.org) only run with `-e network 1`, and the report lists
   the hosts they contacted.
3. **No logs from the phone.** Results come from JUnit only. Nothing is read from logcat. On a failure, the test saves
   a screenshot of its own app inside the test app's storage; screens only show the local test pages.
4. **Ask before running.** Running the suite takes the phone's screen for several minutes: it is started by hand, after
   checking there is no call in progress and the person is not using the phone.
5. **Honest results.** No automatic retries that hide failures. A test that fails and then passes is reported as
   flaky, with how many runs it failed out of how many. Every regression test names the commit that fixed its bug.
6. **Leave nothing behind.** At the end, the e2e app is uninstalled; port forwards and servers are stopped.

## Structure

- **`e2e` build type** in `app/build.gradle`: `debuggable`, `applicationIdSuffix '.e2e'`, debug signing, app name
  "Senda e2e". Minified like release (`minifyEnabled true`, same ProGuard rules) so code shrinking problems show up,
  as they could in a release.
- **`:e2e` test module** (`com.android.test` plugin, `targetProjectPath ':app'`, `targetVariant 'e2e'`), running in
  its own process (`android.experimental.self-instrumenting`, to be checked against the AGP version in use before
  relying on it). A separate process is required: several tests force-stop and restart the app, which would kill
  tests running inside it.
- **UI Automator** drives the app the way a person does: menu, settings, tab list. It reads page text through
  accessibility (the same way `uiautomator dump` did during manual testing), so tests check what is shown on screen.
- **Fixtures** (`e2e/src/main/assets/pages/`): the pages used by hand on October 11, 2026, plus a few more:
  - `nonfree.html`: a nontrivial inline script that writes `JS-RAN-3`; the page shows `NOJS` otherwise.
  - `free.html`: the same script inside `@license` / `@license-end` (GPL-3.0-or-later magnet link).
  - `external.html` + `ext.js`: the same script from a file.
  - `slow.html`: answered after a configurable delay (10 s by default), to recreate a page that is still loading, as
    with Tor starting, without needing Tor.
  - `whoami`: answers which connection reached the server (direct, or through the local proxy).

## Test catalogue

Priority 1 are regressions of bugs fixed in October 2026; they must pass before any release.

### A. Tabs and restarts (priority 1)

| Id | What it checks | Guards |
|---|---|---|
| A1 | Two normal tabs survive two real restarts (`am force-stop` after Senda goes to the background) | `5c1b1e5`, `2de2a69` |
| A2 | A restored tab opened while its page is still loading (`slow.html`) shows its address at once, and still exists if Senda is stopped 1 s later | `2de2a69` |
| A3 | Startup modes: clean home page in front (previous tabs kept in the list), continue in the last tab, start without previous tabs | — |
| A4 | Private tabs are never saved: after a restart, none is left | — |
| A5 | A NEW tab opened from another app's link while `slow.html` loads shows its address, not the home page, and survives a stop 1 s later | Seen on 2026-10-11 with Tor |

### B. Network modes (priority 1; offline with the local proxy)

| Id | What it checks | Guards |
|---|---|---|
| B1 | Started cold with a custom HTTP proxy: every page request reaches the server through the proxy | `ce2f040` |
| B2 | Switching from that proxy to a direct connection restarts Senda by itself, reopens the same tab, and the next request reaches the server directly | `5c1b1e5` |
| B3 | Switching from direct to the proxy works without a restart | — |
| B4 | With the proxy on and the proxy stopped, a page fails instead of going out directly | — |
| B5 (`network`) | Tor: started cold, check.torproject.org reports Tor; after Senda spends 90 s in the background, Tor works again on return | `de65914` |
| B6 (`network`) | Tor: a circuit per site. check.torproject.org and ipv4.myip.wtf (both IPv4 only: Tor exits may use IPv6, as in Tor Browser) show different exits; check.torproject.org again shows its first exit. It measures once check.torproject.org gives the same exit twice in a row: Tor replaces its first circuits for a while after it starts, even for one SOCKS username. Two circuits can end at the same exit (about 1-2 % of runs): retried once by hand | `ab8d5d1` |
| B7 (`network`) | Another app listens on 9050 before Senda starts Tor: the page loads through Tor and that app receives no connection (pages go through Tor's socket file in a folder only Senda can open, as in Tor Browser for Android) | port fix, 2026-10-13 |

### C. JavaScript setting and GNU LibreJS (priority 1)

| Id | What it checks | Guards |
|---|---|---|
| C1 | "All JavaScript": the three fixtures show `JS-RAN-3` | — |
| C2 | "Free only": `nonfree` and `external` show `NOJS`, `free` shows `JS-RAN-3` | `b4695a8` |
| C3 | "Off": all three show `NOJS`; the LibreJS menu entry is gone | `b4695a8` |
| C4 | Switching between the three options without restarting, and after a cold start | `b4695a8` |
| C5 | LibreJS panel from the menu shows the page in front; "Whitelist" + "Reload" runs the script; "Forget" blocks it again | `b4695a8` |
| C6 | "Free only" also applies in a private tab | `b4695a8` |
| C7 | The Settings summary shows the option just chosen | `b4695a8` |

### D. Extensions (priority 1)

| Id | What it checks | Guards |
|---|---|---|
| D1 | uBlock Origin's quick panel shows the host of the page in front (`127.0.0.1`), not `about:blank` or its own page | `b4695a8` |
| D2 | A page of an extension shows that extension's name in the address bar | `b4695a8` |
| D3 | "Open the dashboard" (uBlock) and "Settings…" (LibreJS) open in a normal tab | `b4695a8` |

### E. Data that must survive (priority 2)

| Id | What it checks |
|---|---|
| E1 | Bookmarks and history survive a restart and an app update (install the same e2e build over itself) |
| E2 | Encrypted backup: create, restore into a clean e2e app, same bookmarks and settings; a wrong passphrase is rejected |

### F. Network privacy (priority 1 on every GeckoView update)

The existing procedure, [network-audit.md](network-audit.md), run against the e2e app instead of the real one (same
engine and code, no personal data). Only host names and ports are recorded.

| Id | What it checks |
|---|---|
| F1 | 75 minutes (35 idle, 4 neutral pages, 34 idle) through the recording proxy: no Google host that a page did not request |
| F2 | Nothing goes around the proxy during F1 (`socket_watch.sh`) |
| F3 (`network`) | Cold start with Tor, 2 minutes: every address is a Tor relay |
| F4 | With "free JavaScript only", LibreJS adds no host of its own compared with F1 |

### G. Engine security (priority 1 before every release)

| Id | What it checks |
|---|---|
| G1 | `scripts/check_geckoview.py` (run on the computer): Senda's GeckoView against the latest one and Mozilla's security advisories |
| G2 | No DRM: a page asking for Widevine, as a streaming site does, is refused by Gecko itself (EME off), not only because Senda does not approve it |

### H. User interface (priority 1)

| Id | What it checks |
|---|---|
| H1 | Every Settings entry opens its screen or dialog and closes with Back, without the app closing |
| H2 | The 8 languages: menu, Settings, tabs and home page in each, without crashes (screenshots for a person to review) |
| H3 | Accessibility: every clickable element has a text or a content description |
| H4 | Touch targets of at least 48 dp; smaller ones are listed with their size |
| H5 | Light and dark theme, system font at 130 % (screenshots for a person to review) |
| H6 | Rotating the screen keeps the tab, its address and an open dialog |
| H7 | Toolbar at the top, bottom and floating: Back, reload, menu and tabs work in each |
| H8 | The security indicator does not say "connection not secure" on an extension's own pages (it did on 2026-10-11) |

### I. Performance (priority 2, catches large regressions)

| Id | What it checks |
|---|---|
| I1 | Cold start, 10 times (`am start -W`); reference: the e2e app on 2026-10-12/13, median 1302–1552 ms (a release, 0.2.0-beta.1: 361–406 ms) |
| I2 | Memory at rest after 3 local pages (`dumpsys meminfo`); reference: the e2e app, 405–431 MB main process (a release: 281 MB) |
| I3 | Release APK size, below Codeberg's 100 MB limit |

### J. Stability (priority 2)

| Id | What it checks |
|---|---|
| J1 | 5,000 random events with a fixed seed (`monkey -p org.senda.browser.e2e`), with the app set to a stopped local proxy so nothing reaches the internet: no crash, no freeze |

### K. With the user present (manual)

The password vault must not open without a fingerprint or PIN, so it is not automated around that: the person running
the session uses their fingerprint, with made-up accounts on a local page in the e2e app.

| Id | What it checks |
|---|---|
| K1 | Save and fill a made-up account; it is offered only on that site |
| K2 | Export always asks for the fingerprint; cancelling exports nothing |
| K3 | App lock asks for the fingerprint after more than 30 s in the background |

## Running it

```bash
./gradlew :app:assembleE2e :e2e:assembleE2e
adb install -r app/build/outputs/apk/e2e/app-e2e.apk
adb install -r e2e/build/outputs/apk/e2e/e2e-e2e.apk
# Everything automated (about 25 minutes); one class with -e class org.senda.browser.e2e.test.TabsTest
adb shell am instrument -w org.senda.browser.e2e.test/androidx.test.runner.AndroidJUnitRunner
# Screenshots of H2/H5 and the numbers of I1/I2 are left in the test app's folder:
adb pull /sdcard/Android/data/org.senda.browser.e2e.test/files/
adb uninstall org.senda.browser.e2e.test; adb uninstall org.senda.browser.e2e
```

Never `connectedAndroidTest`: it uninstalls apps when it ends. Every release candidate runs priority 1 three times in a
row; the release notes say how many runs passed.

How the suite keeps its rules:

- Every test clears the e2e app (`pm clear`) and saves its starting settings with `run-as` (the e2e build is
  debuggable): the test proxy, plus what the test needs. Nothing of the person's Senda is read or written.
- The e2e app's proxy is the test proxy, which answers only for the test pages and refuses anything else. Gecko never
  sends `127.0.0.1` through a proxy, so the network mode tests (B) serve the pages on the phone's own Wi-Fi address;
  those connections stay inside the phone and the pages answer only connections from the phone itself. B2 and B3 leave
  the e2e app in direct mode for a few seconds.
- J1 does not use Android's `monkey`: its random touches can reach the status bar and the phone's quick settings. It
  taps only elements of the e2e app, never Settings, proxy, share, print, cast or home screen shortcut entries, and
  stops if another app comes to the front.
- H6 and H5 change the phone's rotation and text size: their values are written down first and put back exactly.
- Crashes are read from Android's record of the app's own exits (`dumpsys activity exit-info`), not from the system log.
- Not automated: E2 (Android's file picker), K (fingerprint), B5, B6, B7 and F3 (the real Tor network). Each Senda's Tor has its
  own socket file and lets Tor pick its TCP port, so the person's Senda can stay open while they run.

## Limits

- One phone (moto g34 5G, Android 15) until testers with other phones join; results say so.
- UI Automator reads the screen through accessibility. If a text cannot be read that way, the test says so instead of
  guessing; it does not fall back to screen coordinates.
- Tor tests depend on the Tor network: a single failure is retried by hand once and reported as such, never hidden.
- GeckoView tells accessibility services the page's elements about 190 px higher than they are on screen (found on
  2026-10-12, test H3b); the same happens in extension panels. Tests that would have to tap inside a page or a panel by
  position (C5, LibreJS's "Settings...") fail for that reason until it is fixed.
- The e2e app is debuggable, so its start time and memory (I1, I2) are higher than a release's and only show large
  changes between runs of the suite itself.
