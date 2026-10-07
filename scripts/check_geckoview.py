#!/usr/bin/env python3
"""Is Senda's engine up to date on security?

Compares the GeckoView version in app/build.gradle with the latest one published by Mozilla and with Firefox's
security advisories (MFSA). Exits with code 1 if there is a newer version that fixes vulnerabilities: a browser
with an outdated engine exposes its users to already public flaws even if everything else is fine.

Usage: python3 scripts/check_geckoview.py      (standard library only; needs network access)
"""
import re
import sys
import urllib.request
from pathlib import Path

MAVEN = "https://maven.mozilla.org/maven2/org/mozilla/geckoview/geckoview/maven-metadata.xml"
ADVISORIES = "https://www.mozilla.org/en-US/security/advisories/"
GRADLE = Path(__file__).resolve().parent.parent / "app" / "build.gradle"


def fetch(url: str) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": "senda-geckoview-check"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read().decode("utf-8", "replace")


def version_key(v: str):
    # 157.0.20260924084938 → (157, 0, 20260924084938); 157.0.1.2026… → (157, 0, 1, 2026…)
    return tuple(int(x) for x in v.split("."))


def main() -> int:
    m = re.search(r"org\.mozilla\.geckoview:geckoview:([\d.]+)", GRADLE.read_text())
    if not m:
        print("Could not find the GeckoView version in app/build.gradle")
        return 2
    current = m.group(1)

    versions = re.findall(r"<version>([\d.]+)</version>", fetch(MAVEN))
    latest = max(versions, key=version_key)
    newer = sorted((v for v in versions if version_key(v) > version_key(current)), key=version_key)

    # "fixed in Firefox N" advisories with N greater than the current version (includes N.0.x)
    cur_major = version_key(current)[:2]
    pending = []
    for mfsa, level, fixed in re.findall(
        r'href="/en-US/security/advisories/(mfsa\d{4}-\d+)/"><span class="level ([a-z]+)">[^<]*</span>\s*'
        r'Security Vulnerabilities fixed in Firefox ([\d.]+)</a>', fetch(ADVISORIES)
    ):
        parts = tuple(int(x) for x in fixed.split("."))
        if (parts + (0,))[:2] > cur_major or (len(parts) > 2 and parts[:2] == cur_major):
            pending.append((mfsa.upper(), level, fixed))

    print(f"GeckoView in Senda: {current}")
    print(f"Latest published: {latest}")
    if not newer:
        # Already on the latest GeckoView: dot-release advisories of this major version are taken as fixed
        # (the build number does not say which Firefox dot release it is). Advisories for a newer major
        # version mean Firefox has fixes that GeckoView has not published yet
        ahead = [x for x in pending if tuple(int(v) for v in x[2].split("."))[:1] > cur_major[:1]]
        if ahead:
            print("Firefox has security fixes not yet published as GeckoView:")
            for mfsa, level, fixed in ahead:
                print(f"  {mfsa} ({level} impact): fixed in Firefox {fixed} — {ADVISORIES}{mfsa.lower()}/")
            return 1
        print("Up to date.")
        return 0
    if pending:
        print("Firefox security advisories newer than Senda's version:")
        for mfsa, level, fixed in pending:
            print(f"  {mfsa} ({level} impact): fixed in Firefox {fixed} — {ADVISORIES}{mfsa.lower()}/")
        print(f"UPDATE NEEDED: there are {len(newer)} newer version(s) and advisories not yet fixed in Senda.")
        return 1
    print(f"There are {len(newer)} newer version(s) with no associated security advisories yet: review them.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
