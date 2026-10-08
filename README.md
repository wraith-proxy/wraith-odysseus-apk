[![Documentation](https://img.shields.io/badge/docs-live-brightgreen)](https://www.thomaspeoples.com/gitea-repos/wraith-odysseus-apk/)
![Coverage](https://www.thomaspeoples.com/gitea-repos/wraith-odysseus-apk/coverage.svg)
[![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)
[![Get it on Obtainium](https://img.shields.io/badge/get%20it%20on-Obtainium-1f2429?logo=android&logoColor=3ddc84)](https://git.thomaspeoples.com/thomaspeoples/wraith-odysseus-apk/releases)
![Platform](https://img.shields.io/badge/platform-Android%2010%2B-3ddc84)

# 👻 wraith-odysseus-apk
### *Your self-hosted Odysseus, on the phone*

An unofficial native Android client for a self-hosted
[Odysseus](https://github.com/odysseus-dev/odysseus) AI server. It is full native Jetpack Compose,
not a WebView wrapper. It talks to a stock Odysseus 1.0.3 using only the routes
its own web UI uses, so the server needs no changes. Not affiliated with the
Odysseus project.

---

## 📲 Installing

There is no Play Store build, deliberately. CI builds and signs every release
on merge to `main`. The APK is `wraith-odysseus.apk` on the
[Releases](https://git.thomaspeoples.com/thomaspeoples/wraith-odysseus-apk/releases)
page, tagged `android-X.Y.Z`.

- **Obtainium:** point it at this repo's URL and tell it the host is Gitea. It
  tracks releases automatically.
- **Direct sideload:** download the APK from Releases and open it. The app also
  checks for a newer release itself on launch.
- **F-Droid:** not yet. See [docs/ROADMAP.md](docs/ROADMAP.md).

Then enter your server's address and log in with your Odysseus username,
password and (if enabled) 2FA code.

## 🗺️ What it does

```
your Odysseus server  (stock 1.0.3, cookie session, no server changes)
  |
  +-- Chats -------------------------------------------------+
  |   session list, history, streaming replies with thinking  |
  |   model picker, agent / web / research, tool approvals    |
  |   attachments, search, stop -- survives screen-off        |
  +-----------------------------------------------------------+
  |
  +-- Email -------------------------------------------------+
  |   every unread message across every account, merged      |
  |   read, mark unread, send, reply                         |
  +-----------------------------------------------------------+
```

## 🧩 The Hard Decisions Baked In

**Only the session cookie is stored, and it is sealed.** Login is the same
username, password and TOTP flow as the web UI. The resulting cookie is
encrypted with a Keystore key that cannot leave the device. The password is
never written anywhere.

**Plain http only for private IP addresses.** Cleartext is allowed so a LAN or
Tailscale `100.x` address works, but the app refuses http for any public host
or any hostname when the address is entered. The cookie is also never sent over
http if the server marked it Secure, and redirects are never followed.

**`versionCode` is derived, not stored.** `major * 10000 + minor * 100 +
patch`, computed in `app/build.gradle.kts` from the one `versionName` string
commitizen rewrites, so it cannot be forgotten on a bump.

**No Google anything.** No Play Services, no Firebase, no downloadable fonts.
Fira Code is bundled. The only permission is `INTERNET`.

## 🛠️ Building

```bash
git clone https://git.thomaspeoples.com/thomaspeoples/wraith-odysseus-apk.git
cd wraith-odysseus-apk

cp local.properties.example local.properties   # set sdk.dir
./gradlew assembleDebug
```

There is no server URL in the build: it is typed into the app. Set
`updateCheck=false` in `local.properties` for a build that never checks Gitea
for updates.

CI builds the releases on a purpose-built image; see [docs/ci.md](docs/ci.md).

## License

MIT. See LICENSE. Fonts: Fira Code, OFL-1.1
(`app/src/main/assets/licenses/FiraCode-OFL.txt`).

---

<div align="center">
<sub><i>"Sovereignty is found in the Source."</i></sub>
</div>
