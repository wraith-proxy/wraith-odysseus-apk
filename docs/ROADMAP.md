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
- [x] Fastlane screenshots (`images/phoneScreenshots/1-7.png`), taken on the
      device with a throwaway account and fake mail.
- [x] Owner steps: repo public, keystore minted, secrets set.
- [ ] Device checklist on a real phone, then 1.0.0.

## 0.2.0: the web UI, on the phone

Planned as five releases, shipped together as 0.2.0. Everything uses the
server's existing API; nothing here needs a server change.

- [x] The web UI's sidebar as a drawer from the right, in place of the
      tabs; hold a message for its actions (copy, edit, resend, regenerate,
      fork, rewrite, delete); AI reply in email; the ghost-sail icon.
- [x] Chats as rich as the web: sources, tool cards, timings, the
      context ring, reply variants, tables, the full composer and header.
- [x] The chat list: hold a chat to rename, favourite, copy, move to a
      folder, archive or delete; folders, date groups, select, archived chats.
- [x] Email as the web has it: accounts, folders, filters, search,
      HTML with images on request, attachments, reply all, forward, translate,
      schedule send.
- [x] The tools: notes, calendar, memory and skills, deep research,
      tasks, gallery, library, theme.

Compare and Cookbook stay in the web UI; their drawer rows open it.

## CI

- [x] Bake gitleaks, semgrep and Trivy into the `ghost-runner-android` image,
      then drop the pinned downloads from `bin/security-scan.sh`.
- [x] A weekly scheduled Trivy run, since new CVEs land without a push.

## F-Droid

In place: no proprietary dependencies, `INTERNET` only, checksummed wrapper,
no dependency-info block, `UPDATE_CHECK` switch, a linted recipe with
automatic updates, a literal versionCode and per-versionCode changelogs kept
by the bump hook, and fastlane screenshots.

- [x] The one fdroiddata merge request:
  [fdroiddata!51956](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/51956).
- [x] Bake fdroidserver into the runner image, like the scan tools.

From the fdroiddata review:

- [x] Reproducible builds: F-Droid's build image reproduces the CI APK, so
  the recipe ships this project's signature (`Binaries:`,
  `AllowedAPKSigningKeys`).
- [x] A CI job that runs F-Droid's build image against each release, so a
  release that stops reproducing is caught here, not by F-Droid.
- [x] 0.6.1: add Aurora Droid (`com.aurora.adroid`) and the Privileged
      Extension (`org.fdroid.fdroid.privileged`) to `FDROID_INSTALLERS`, so
      those installs skip the update check too.

0.7.0:

- [ ] Demo mode: try the app with no server, on canned chats, mail and
      tools, so testers can check it without an Odysseus install.
