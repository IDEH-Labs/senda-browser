# Senda privacy policy

_Last updated: October 9, 2026. Applies to Senda for Android, published by IDEH Labs._

Senda is a web browser. It has **no accounts, no servers of its own, no analytics, no telemetry and no
advertising**. Its developers receive nothing about you or about how you use it.

## What stays on your phone

Everything Senda keeps is stored only on your phone, in its private storage, and is never sent to IDEH Labs:

- Bookmarks, home page shortcuts, history (never for private tabs), downloads list and settings.
- Saved passwords, encrypted with AES-256-GCM using a key that lives in the phone's secure hardware and only
  works after your fingerprint or PIN.
- The assistant conversation, encrypted with its own key protected the same way. It is kept until you delete it.
- Downloaded wallpapers and your own wallpaper photo.

Uninstalling Senda deletes all of it. Android backups of Senda's data are disabled.

## Connections Senda makes on its own

Besides the pages you visit, Senda connects only to:

- **Google Safe Browsing**, on by default, to download lists of dangerous sites. It can be turned off in Settings.
- **uBlock Origin filter lists**, which it updates periodically from each list's servers.
- **News feeds** (MuyLinux, EFF, FSF), only in the home page layouts that show news.
- **Mozilla's translation models**, only the first time you translate a language.
- **Wallpapers**: only when you choose one that is not included, and after asking, from this project's release on
  GitHub (GitLab as a fallback). The file's fingerprint is checked.

If you use Tor or a proxy, these connections go through it too.

## Services you choose to use

- **AI assistant** (optional): when you write to it, your message (and the page, only if you include it) is sent to
  the service you connected: ChatGPT (OpenAI), Claude (Anthropic), Gemini (Google), Grok (xAI) or Mistral. Their
  own privacy policies apply to what you send them. Nothing is sent until you use it.
- **Passkeys**: when a site asks to create or use a passkey, Android's Credential Manager passes the request, with the
  site's address, to the password manager you choose on the phone (for example Bitwarden). Senda does not keep
  passkeys itself.
- **Search engine**: what you type in the address bar goes to the search engine you selected. Suggestions while
  typing come only from your bookmarks and history on the phone.

## Backups and exports

Encrypted backups and password or bookmark exports are created only when you ask, and saved only where you choose.
Senda never uploads them anywhere. Encrypted backups use the open age format with a passphrase only you know;
without it nobody, including IDEH Labs, can open them. Password exports (CSV) are not encrypted, and Senda warns you
about this before creating them.

## Permissions

- **Internet and network state**: to browse.
- **Fingerprint / screen lock**: to unlock the password vault and the assistant conversation.
- **Camera, microphone and location**: only for websites that ask for them (video calls, maps). Android asks you
  first, and Senda asks again for each site.
- **Notifications, display over other apps, foreground service, start at boot**: only for TV mode while your screen is
  mirrored to a TV, and to put the screen back to normal if the phone restarts while mirroring.
- **Credential Manager (set origin, query candidate credentials)**: to use passkeys from your password manager on
  the site you are visiting. Android does not ask for these; they only work when a site requests a passkey.
- **Write secure settings**: cannot be granted from the app. It can only be granted manually with ADB, to adapt the
  screen's aspect ratio for TV mode.

## Contact

Questions or reports: <https://github.com/IDEH-Labs/senda-browser/issues> or
<https://gitlab.com/ideh-labs/senda-browser/-/issues>. Please never include passwords or personal data in a report.
Security vulnerabilities: please report them privately, as described in [SECURITY.md](SECURITY.md).
