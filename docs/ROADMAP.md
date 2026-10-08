# Roadmap

Where this is now, and what's next. Not a schedule, just the order things
need to happen in.

## 0.1.0: first release

- [x] Bootstrap: Gradle 8.13 with checksummed wrapper, house CI, theme, fonts,
      icon, and the network core (`Http`, `Sse`, `HostPolicy`,
      `SessionStore`) with unit tests.
- [x] Login: server URL and version probe, username + password + TOTP,
      logout, session-expired handling.
- [x] Chats: sessions, history, streaming with thinking, resume after a drop,
      stop, model picker, agent/web/research toggles, tool approvals,
      attachments, search.
- [x] Email: unread across every account, read, mark unread, send, reply.
- [x] About, FAQ, update dialog, fastlane text, F-Droid recipe draft,
      Obtainium preset link.
- [ ] Fastlane screenshots (`images/phoneScreenshots/1-4.png`), taken on the
      device with a throwaway account and fake mail.
- [ ] Owner steps: repo public, keystore minted, secrets set.
- [ ] Device checklist on a real phone, then 1.0.0.

## Later

- Email "All" view, HTML rendering, archive/flag/delete, folders, compose
  attachments, AI summary and reply.
- Calendar, notes and tasks.
- A per-session "running" dot and an unread badge.
- After Mark unread, put the message back in the inbox list without a manual
  refresh.
- Toggles that start from the session's own mode, instead of all off.

## F-Droid

Ready now: no proprietary dependencies, `INTERNET` only, checksummed wrapper,
no dependency-info block, `UPDATE_CHECK` switch.

Deferred until an fdroiddata merge request:

- **Reproducible builds.** These need an unsigned build and the build-tools
  34 apksigner (an image rebuild). They also need back the build settings the
  bootstrap left out: `aaptOptions.cruncherEnabled = false`,
  `generatedDensities()`, `vcsInfo` off, and the ArtProfile task disabled.
- `AllowedAPKSigningKeys`, a literal versionCode for `checkupdates`, and
  per-versionCode changelogs.
- The fastlane 512px `icon.png` (F-Droid falls back to the APK icon).
- An F-Droid-signed build cannot replace an Obtainium install without an
  uninstall. That will be documented in the listing.
