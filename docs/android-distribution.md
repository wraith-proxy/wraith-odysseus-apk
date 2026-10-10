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

### One-tap Obtainium preset

On the phone, with Obtainium installed, open this link to add the app with
the settings above already filled in:

[Add to Obtainium](obtainium://app/%7B%22id%22%3A%22com.thomaspeoples.wraithodysseus%22%2C%22url%22%3A%22https%3A%2F%2Fgit.thomaspeoples.com%2Fthomaspeoples%2Fwraith-odysseus-apk%22%2C%22author%22%3A%22thomaspeoples%22%2C%22name%22%3A%22Wraith%20for%20Odysseus%22%2C%22preferredApkIndex%22%3A0%2C%22additionalSettings%22%3A%22%7B%5C%22apkFilterRegEx%5C%22%3A%5C%22%5Ewraith-odysseus%5C%5C%5C%5C.apk%24%5C%22%2C%5C%22versionExtractionRegEx%5C%22%3A%5C%22android-%28%5C%5C%5C%5Cd%2B%5C%5C%5C%5C.%5C%5C%5C%5Cd%2B%5C%5C%5C%5C.%5C%5C%5C%5Cd%2B%29%5C%22%2C%5C%22matchGroupToUse%5C%22%3A%5C%221%5C%22%2C%5C%22includePrereleases%5C%22%3Afalse%7D%22%2C%22overrideSource%22%3A%22Codeberg%22%7D)

It is an `obtainium://app/{json}` link. The JSON it carries, before URL
encoding:

```json
{"id":"com.thomaspeoples.wraithodysseus",
 "url":"https://git.thomaspeoples.com/thomaspeoples/wraith-odysseus-apk",
 "author":"thomaspeoples","name":"Wraith for Odysseus","preferredApkIndex":0,
 "additionalSettings":"{\"apkFilterRegEx\":\"^wraith-odysseus\\\\.apk$\",\"versionExtractionRegEx\":\"android-(\\\\d+\\\\.\\\\d+\\\\.\\\\d+)\",\"matchGroupToUse\":\"1\",\"includePrereleases\":false}",
 "overrideSource":"Codeberg"}
```

`overrideSource` is Obtainium's Forgejo/Gitea source (named after Codeberg),
which is what a self-hosted Gitea needs. The preset has not yet been tried on a
device; the first install through it is part of the device checklist, and the
thing to confirm there is that Obtainium reports the app as up to date rather
than offering a false update.

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
the only permission (the merged manifest also carries AndroidX's own
signature-level `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, which every
AndroidX app has and which grants nothing), a checksummed Gradle wrapper, no
AGP dependency-info block (`release.yml` fails if one appears), and the
`UPDATE_CHECK` switch.

The fdroiddata recipe is
[`docs/fdroid/com.thomaspeoples.wraithodysseus.yml`](https://git.thomaspeoples.com/thomaspeoples/wraith-odysseus-apk/src/branch/main/docs/fdroid/com.thomaspeoples.wraithodysseus.yml).
It builds the tag with `updateCheck=false`, so an F-Droid build never contacts
anything but the user's own server. CI runs `fdroid lint` on it; it has not
been through `fdroid build`.

After the one merge request, releases are automatic: the recipe sets
`AutoUpdateMode: Version` and `UpdateCheckMode: Tags`, so F-Droid finds each
new `android-*` tag and reads the literal `versionCode` and `versionName` in
`app/build.gradle.kts` there. The listing text, screenshots and release notes
come from `fastlane/metadata/android/en-US/`; the bump hook writes
`changelogs/<versionCode>.txt` each release.

To submit: fork `gitlab.com/fdroid/fdroiddata`, copy the recipe to
`metadata/com.thomaspeoples.wraithodysseus.yml`, and open a merge request.
Reproducible builds and `AllowedAPKSigningKeys` are left out on purpose; see
[the roadmap](ROADMAP.md).

An F-Droid build is signed with F-Droid's key, not this project's, so it cannot
be installed over a copy from Obtainium or Releases (or the other way round)
without uninstalling first, which also logs you out.

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
