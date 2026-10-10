# Senda backup format (version 1)

A Senda backup (`*.senda`) is a standard **age v1** file (<https://age-encryption.org/v1>) encrypted with a
**passphrase** (scrypt recipient). Inside is a UTF-8 JSON document. Nothing else: no proprietary container,
no Senda server, no account. Any age implementation can open it, on any system:

```sh
age -d senda-backup-2026-10-09.senda > backup.json
```

## Encryption

- age v1 with a single `scrypt` stanza, as the specification requires.
- Senda writes work factor `16` (64 MiB of memory) so that phones with little memory can open their own
  backups; it reads files with work factor up to `18` (age's default). A higher factor is reported as
  unsupported on the phone, not as a damaged file.
- The payload is age's STREAM (ChaCha20-Poly1305, 64 KiB chunks). The whole file is verified before any data
  is used: a damaged or tampered backup restores nothing.
- Passphrases must have at least 12 characters. They are never stored. Without the passphrase nobody, Senda
  included, can open the backup.

Senda's implementation (`app/src/main/java/org/senda/browser/core/backup/Age.kt`) is tested against the
official C2SP CCTV test vectors and was checked in both directions against the reference `age` v1.3.2:
files made by Senda open with `age -d`, and files made with `age -p` open in Senda.

## JSON document

```json
{
  "format": "senda-backup",
  "version": 1,
  "created": 1760000000000,
  "app": "Senda 0.1.4-alpha (Android)",
  "bookmarks": [ { "title": "…", "url": "https://…", "created": 1760000000000 } ],
  "shortcuts": [ { "title": "…", "url": "https://…", "monogram": "W", "color": "#2A6FDB" } ],
  "history":   [ { "title": "…", "url": "https://…", "visited": 1760000000000 } ],
  "passwords": [ { "url": "https://…", "username": "…", "password": "…", "note": "" } ],
  "settings":  { "theme_mode": "DARK", "https_only_mode": "ALL", "font_scale_percent": 110 }
}
```

- Times are milliseconds since the Unix epoch (UTC).
- Each section is present only if the user chose it. `bookmarks` and `shortcuts` travel together.
- `passwords` holds the vault's website logins. They leave the vault only after a fingerprint or PIN, and only
  inside the encrypted file.
- History from private tabs is never recorded, so it can never be in a backup.

### Settings

Only portable, non-secret settings are written (the list is `portableSettingKeys` in
`PreferencesManager.kt`). Left out on purpose:

- Proxy server and custom DNS-over-HTTPS address: a backup must not be able to divert traffic.
- Custom CSS and scripts: a backup must not be able to inject code into pages.
- Remote debugging, open tabs, TV mode, wallpaper rotation state and the user's own wallpaper photo.

When restoring, a setting is applied only if its key is in that list and its value has the expected type; the
search engine address must be `https://`.

## Reading rules (for any implementation)

- Reject documents whose `format` is not `senda-backup`. Reject a `version` newer than the one you support,
  saying so.
- Ignore unknown fields and sections.
- Accept only `http://` and `https://` addresses without control characters for bookmarks, shortcuts, history
  and passwords. Drop anything else (`javascript:`, `file:`, `intent:`…).
- Cap sizes: Senda accepts at most 50,000 items per section, 8 KiB per text field and 64 MiB per file.
- Restoring adds what is missing and never deletes. Bookmarks and shortcuts are matched by address, history
  by address and time, and passwords by site (registrable domain) and username. An existing password is
  kept unless the user asks to replace it.

## Compatibility

New fields may be added within version 1. Changes that older readers could misread will bump `version`.
