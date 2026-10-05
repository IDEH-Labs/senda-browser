#!/usr/bin/env python3
"""¿Está el motor de Senda al día en seguridad?

Compara la GeckoView de app/build.gradle con la última publicada por Mozilla y con los avisos de seguridad
de Firefox (MFSA). Sale con código 1 si hay una versión más nueva que corrige vulnerabilidades: un navegador
con el motor atrasado expone a sus usuarios a fallos ya públicos aunque todo lo demás esté bien.

Uso: python3 scripts/check_geckoview.py      (solo biblioteca estándar; necesita red)
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
        print("No encuentro la versión de GeckoView en app/build.gradle")
        return 2
    current = m.group(1)

    versions = re.findall(r"<version>([\d.]+)</version>", fetch(MAVEN))
    latest = max(versions, key=version_key)
    newer = sorted((v for v in versions if version_key(v) > version_key(current)), key=version_key)

    # Avisos «fixed in Firefox N» con N mayor que la versión actual (incluye N.0.x)
    cur_major = version_key(current)[:2]
    pending = []
    for mfsa, level, fixed in re.findall(
        r'href="/en-US/security/advisories/(mfsa\d{4}-\d+)/"><span class="level ([a-z]+)">[^<]*</span>\s*'
        r'Security Vulnerabilities fixed in Firefox ([\d.]+)</a>', fetch(ADVISORIES)
    ):
        parts = tuple(int(x) for x in fixed.split("."))
        if (parts + (0,))[:2] > cur_major or (len(parts) > 2 and parts[:2] == cur_major):
            pending.append((mfsa.upper(), level, fixed))

    print(f"GeckoView en Senda: {current}")
    print(f"Última publicada:   {latest}")
    if pending:
        print("Avisos de seguridad de Firefox posteriores a la versión de Senda:")
        for mfsa, level, fixed in pending:
            print(f"  {mfsa} (impacto {level}): corregido en Firefox {fixed} — {ADVISORIES}{mfsa.lower()}/")
    if newer and pending:
        print(f"ACTUALIZAR: hay {len(newer)} versión(es) más nueva(s) y avisos sin corregir en Senda.")
        return 1
    if newer:
        print(f"Hay {len(newer)} versión(es) más nueva(s) sin avisos de seguridad asociados todavía: revisar.")
        return 0
    print("Al día.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
