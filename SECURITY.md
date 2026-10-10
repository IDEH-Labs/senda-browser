# Security policy

Senda is a beta browser maintained by a very small team (IDEH Labs). It has **not had an external security audit**
yet, and independent review is very welcome.

## Reporting a vulnerability

Please report security problems **privately**, not in a public issue:

- **GitHub**: [report a vulnerability](https://github.com/IDEH-Labs/senda-browser/security/advisories/new)
  (private vulnerability reporting).
- **GitLab**: open an issue at <https://gitlab.com/ideh-labs/senda-browser/-/issues/new> and tick
  **"This issue is confidential"** before submitting.

Include, if you can: the Senda version (Settings → About), the Android version and phone model, the steps to
reproduce, and what an attacker could achieve. Never include your own passwords or personal data.

## What to expect

- An acknowledgement within 7 days.
- An honest assessment: whether we can reproduce it, how serious we think it is, and what we plan to do.
- A fix in a new release as soon as we can. Security fixes in GeckoView (Mozilla's engine) are adopted within days of
  each Mozilla security advisory.
- Credit in the release notes, if you want it, once the fix is published. Please give us a reasonable time to fix the
  problem before disclosing it publicly; we will agree on a date with you.

There is no bug bounty.

## Scope

In scope: the Senda app (this repository) and the signed APKs published in its releases and in the IDEH Labs F-Droid
repository.

Out of scope, please report upstream: vulnerabilities in GeckoView/Firefox itself
([Mozilla](https://www.mozilla.org/security/bug-bounty/)), uBlock Origin, Tor, or Android.

## Supported versions

Only the latest release receives fixes.

## Verifying releases

Release APKs are signed with the certificate whose SHA-256 is
`431d64a9a39e299956aef5880b88c4830fc81add2acdd9571c76d5323c775309`, and release tags are GPG-signed. See the
release notes of each version for its APK checksum.
