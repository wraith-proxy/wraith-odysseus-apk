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

## 0.2.0 to 0.6.0: the web UI, on the phone

One release at a time, each tried on a phone before it ships. Everything
uses the server's existing API; nothing here needs a server change.

- [ ] 0.2.0: the web UI's sidebar as a drawer from the right, in place of the
      tabs; hold a message for its actions (copy, edit, resend, regenerate,
      fork, rewrite, delete); AI reply in email; the ghost-sail icon.
- [ ] 0.3.0: chats as rich as the web: sources, tool cards, timings, the
      context ring, reply variants, tables, the full composer and header.
- [ ] 0.4.0: the chat list: hold a chat to rename, favourite, copy, move to a
      folder, archive or delete; folders, date groups, select, archived chats.
- [ ] 0.5.0: email as the web has it: accounts, folders, filters, search,
      HTML with images on request, attachments, reply all, forward, translate,
      schedule send.
- [ ] 0.6.0: the tools: notes, calendar, memory and skills, deep research,
      tasks, gallery, library, theme.

Compare and Cookbook stay in the web UI; their drawer rows open it.

## CI

- [ ] Bake gitleaks, semgrep and Trivy into the `ghost-runner-android` image,
      then drop the pinned downloads from `bin/security-scan.sh`.
- [ ] A weekly scheduled Trivy run, since new CVEs land without a push.

## F-Droid

Ready now: no proprietary dependencies, `INTERNET` only, checksummed wrapper,
no dependency-info block, `UPDATE_CHECK` switch, a linted recipe with
automatic updates, a literal versionCode and per-versionCode changelogs kept
by the bump hook, and fastlane screenshots.

- [ ] Open the one fdroiddata merge request (by hand: it is a fork and MR on
  gitlab.com).
- [ ] Bake fdroidserver into the runner image, like the scan tools.

Deferred until after the fdroiddata merge request:

- **Reproducible builds.** These need an unsigned build and the build-tools
  34 apksigner (an image rebuild). They also need back the build settings the
  bootstrap left out: `aaptOptions.cruncherEnabled = false`,
  `generatedDensities()`, `vcsInfo` off, and the ArtProfile task disabled.
- `AllowedAPKSigningKeys` and `Binaries:`, so F-Droid ships this project's
  signature. Needs the reproducible build above.
- An F-Droid-signed build cannot replace an Obtainium install without an
  uninstall. The listing says so; the signature fix is the item above.
