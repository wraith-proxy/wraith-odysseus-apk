# CI

Same pipeline as the other wraith-* apps: push a branch and it is gated,
merged and released without a human in the loop. Three workflows, two
runner labels, one image.

## The runner image

Android CI runs on **`ghost-runner-android`**, not the `ghost-runner` label
the Python repos use. Google ships `aapt2` and `d8` as glibc binaries and
`ghost-runner` is Alpine/musl, so the Android SDK cannot run there. The image
carries JDK 17, Android platform 36, build-tools 35.0.0 and `mkdocs`.

`ANDROID_HOME` is set in the image. `local.properties` is gitignored and absent
in CI, which is the same state F-Droid's build server sees.

The coverage badge is rendered by `bin/coverage_badge.py` (stdlib only),
because `genbadge` cannot read Kover's JaCoCo XML. There is no coverage gate.
Without an emulator the testable surface is the pure logic: the SSE parser,
the host policy, error-body parsing, the cookie rules and the update check.

## Workflows

| File | Trigger | Runner | Does |
|---|---|---|---|
| `test.yml` | push to any branch but `main` | `ghost-runner-android` | unit tests + coverage, Android Lint, `assembleDebug`, then auto-merge |
| `release.yml` | push to `main` | `ghost-runner` then `ghost-runner-android` | version bump, tag, build the tag, verify signing, publish release |
| `pages.yml` | push to `main` | `ghost-runner-android` | coverage badge, mkdocs build, publish to ghost-site |

### Release, step by step

1. `bump` runs `cz bump`. The bump commit ends in `[skip ci]`, so the push
   it makes does not start a second release.
2. `release-apk` checks out `main` (not the triggering SHA, which is the
   commit *before* the bump), resolves the newest `android-*` tag, then
   **checks out that tag** and requires a clean tree. The APK is built from
   exactly the tagged source.
3. `assembleRelease` with the keystore from secrets. The APK is named
   exactly. An `app-release-unsigned.apk` means the secrets did not apply, and
   the job fails instead of shipping it.
4. `apksigner verify`, then a stdlib Python walk of the APK Signing Block that
   fails on any entry other than v2 (`0x7109871a`), v3 (`0xf05368c0`) and
   verity padding (`0x42726577`). That catches AGP's dependency-info block
   (`0x504b4453`), which F-Droid rejects.
5. The release body is this version's section of `CHANGELOG.md`, which is
   what Obtainium shows. The asset is `wraith-odysseus.apk`.

A branch pushed to Gitea is auto-merged and released, so work-in-progress
branches stay local.

## Versioning

`.cz.yaml` uses `version_provider: scm`: the current version is the latest
`android-X.Y.Z` tag, and `appVersionName` in `app/build.gradle.kts` is the
only line commitizen rewrites. The tag shape is load-bearing:
`UpdateChecker.kt` and Obtainium both match it exactly.

Commit subjects are `<type>/TJP-NNNNN: <summary>`. `feature` bumps minor,
`fix`/`bug` bump patch, `break` bumps major, and `chore` bumps nothing.

`versionCode` is derived, `major * 10000 + minor * 100 + patch` (minimum 1).
The build fails if minor or patch reaches 100, since 1.100.0 would collide
with 2.0.0. `VersionCodeTest` pins the built value to that scheme.

## Secrets

| Secret | Used by | For |
|---|---|---|
| `giteatoken` | all | API auth, pushes, releases |
| `giteaurl` | `test.yml`, `release.yml`, `pages.yml` | Gitea base URL |
| `ANDROID_KEYSTORE_B64` | `release.yml` | base64 of the release keystore |
| `ANDROID_KEYSTORE_PASSWORD` | `release.yml` | keystore password |
| `ANDROID_KEY_ALIAS` | `release.yml` | signing key alias |
| `ANDROID_KEY_PASSWORD` | `release.yml` | key password; must equal the store password for PKCS12 |

## Building locally without an SDK

The host has no Android SDK, so local builds use the same image:

```bash
docker run --rm -u 1000:1000 -e HOME=/tmp -e GRADLE_USER_HOME=/gradle \
  -v ~/.cache/wraith-gradle:/gradle -v "$PWD":"$PWD" -w "$PWD" \
  --entrypoint sh git.thomaspeoples.com/thomaspeoples/ci-actions/ghost-runner-android:latest \
  -c './gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug'
```
