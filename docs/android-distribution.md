# Android app: distribution

This app is not on the Google Play Store, deliberately. This page covers the
ways to get it onto a device instead.

## Obtainium and direct download

Releases are cut by CI, not by hand. A merge to `main` runs `release.yml`,
which `cz bump`s the version, tags `android-X.Y.Z`, builds a signed
`wraith-odysseus.apk` and attaches it to a Gitea release.

- Add the app in Obtainium with the repo URL
  `https://git.thomaspeoples.com/thomaspeoples/wraith-odysseus-apk`, and tell it
  the host is Gitea.
- APK filter: `^wraith-odysseus\.apk$`. Version extraction:
  `android-(\d+\.\d+\.\d+)`.
- Anyone can also download `wraith-odysseus.apk` from the Releases page.

The repo must be public for any of this, including the in-app update check, to
reach it.

## Update check

On launch the app asks this repo's Gitea releases API whether a newer
`android-X.Y.Z` exists, and offers a link if so. That is the only request it
makes to anything other than your own server. Build with `updateCheck=false`
(`local.properties`, a `-PupdateCheck=false` Gradle property, or the
`updateCheck` environment variable) and the check is compiled out.

## F-Droid

Not submitted yet. Already in place: no proprietary dependencies, `INTERNET` as
the only permission, a checksummed Gradle wrapper, no AGP dependency-info block
(`release.yml` fails if one appears), and the `UPDATE_CHECK` switch. Deferred:
reproducible builds and an fdroiddata recipe. See [the roadmap](ROADMAP.md).

## Signing key

The release key is a PKCS12 keystore held in the `ANDROID_KEYSTORE_B64` secret,
with an offline backup. It is never rotated: a different key means every
existing install has to be uninstalled first. Its SHA-256 fingerprint gets
recorded here once it is minted.

## Third-party assets

| Asset | Source | SHA-256 |
|---|---|---|
| Fira Code 6.2 (Regular, SemiBold TTF), OFL-1.1 | `https://github.com/tonsky/FiraCode/releases/download/6.2/Fira_Code_v6.2.zip` | `0949915ba8eb24d89fd93d10a7ff623f42830d7c5ffc3ecbf960e4ecad3e3e79` (zip) |
| Fira Code licence text | `https://raw.githubusercontent.com/tonsky/FiraCode/6.2/LICENSE` | `1d41e10031ab125302780a05ec4c91d218e47db0c7e37cf315cce5e608cdc25c` |

The two TTFs are `ttf/FiraCode-Regular.ttf` and `ttf/FiraCode-SemiBold.ttf` from
that zip, renamed to `res/font/fira_code_regular.ttf` and
`res/font/fira_code_semibold.ttf` because Android resource names must be
lower-case. The zip does not include a licence, so the OFL text comes from the
same tag in the repo and ships in `app/src/main/assets/licenses/FiraCode-OFL.txt`.

| Gradle wrapper | SHA-256 |
|---|---|
| `gradle-8.13-bin.zip` (`distributionSha256Sum`) | `20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78` |
| `gradle/wrapper/gradle-wrapper.jar` (8.13) | `81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f` |

Both match [gradle.org/release-checksums](https://gradle.org/release-checksums/).
