# CI

Same pipeline as the other wraith-* apps: push a branch and it is gated,
merged and released without a human in the loop. Four workflows, three
runner labels, two images (ours, and F-Droid's for the reproducibility
check).

## The runner image

Android CI runs on **`ghost-runner-android`**, not the `ghost-runner` label
the Python repos use. Google ships `aapt2` and `d8` as glibc binaries and
`ghost-runner` is Alpine/musl, so the Android SDK cannot run there. The image
carries JDK 17, Android platform 36, build-tools 35.0.0, `mkdocs`, and the
check tools at pinned versions: gitleaks, semgrep, Trivy and fdroidserver.

`ANDROID_HOME` is set in the image. `local.properties` is gitignored and absent
in CI, which is the same state F-Droid's build server sees.

The coverage badge is rendered by `bin/coverage_badge.py` (stdlib only),
because `genbadge` cannot read Kover's JaCoCo XML. There is no coverage gate.
Without an emulator the testable surface is the pure logic plus anything that
can be driven with MockWebServer: the SSE parser, the host policy, error-body
parsing, the cookie rules, login and TOTP, the think splitter and turn
reducer, stream resume without doubled text, the toggle rules, unread paging
and merging, reply headers, the start-route and return-after-login rules, and
the update check. The Compose screens themselves are checked on a device.

## Workflows

| File | Trigger | Runner | Does |
|---|---|---|---|
| `test.yml` | push to any branch but `main` | `ghost-runner-android` | unit tests + coverage, Android Lint, F-Droid lint, `assembleDebug`, security scan, then auto-merge |
| `release.yml` | push to `main` | `ghost-runner`, `ghost-runner-android`, then F-Droid's image | version bump, tag, build the tag, verify signing, publish release, check F-Droid reproduces it |
| `scan.yml` | Mondays 06:00, or by hand | `ghost-runner-android` | the security scan against `main`, for CVEs that land without a push |
| `pages.yml` | push to `main` | `ghost-runner-android` | coverage badge, mkdocs build, publish to ghost-site |

### Security scan

`bin/security-scan.sh` runs three checks and fails the job on any hit:

- **gitleaks** over every commit. `.gitleaksignore` lists the one fake test
  token.
- **semgrep** with the Kotlin, Java, mobsfscan, secrets and OWASP Top 10
  rule packs over `app/src/main`. The exported launcher activity is the one
  expected hit and is excluded.
- **Trivy** over the release runtime classpath, which is the libraries in the
  APK. `bin/lock-release.gradle` writes a throwaway `gradle.lockfile` for it.
  Its databases come from `ghcr.io`, not the default `mirror.gcr.io`.

The tools are baked into the image at pinned versions (gitleaks and Trivy
checked against pinned SHA-256s); only Trivy's databases are fetched at run
time, since they go stale in days. Run it locally the same way as the build below, with
`bin/security-scan.sh` as the command.

### F-Droid lint

`bin/fdroid-lint.sh` runs `fdroid lint` (fdroidserver, baked into the image
at a pinned version) on `docs/fdroid/com.thomaspeoples.wraithodysseus.yml`,
checking categories against fdroiddata's live list. It also fails if
`fastlane/.../changelogs/<versionCode>.txt` is missing for the current
versionCode.

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
6. `fdroid-repro` runs `bin/fdroid-repro.sh` in F-Droid's own
   `buildserver-trixie` image, pulled from GitLab's registry: the same
   `fdroid build` fdroiddata's CI runs, against the APK just published. F-Droid
   publishes nothing for a release that does not reproduce, so a red job here
   means F-Droid users will not get this version. It runs after the release
   because it needs the published APK; it warns, it cannot block.

A branch pushed to Gitea is auto-merged and released, so work-in-progress
branches stay local.

## Versioning

`.cz.yaml` uses `version_provider: scm`: the current version is the latest
`android-X.Y.Z` tag, and `versionName` in `app/build.gradle.kts` is the
only line commitizen rewrites itself. The tag shape is load-bearing:
`UpdateChecker.kt` and Obtainium both match it exactly.

Commit subjects are `<type>/TJP-NNNNN: <summary>`. `feature` bumps minor,
`fix`/`bug` bump patch, `break` bumps major, and `chore` bumps nothing.
A chore branch must also end in a commit whose subject has `[no release]`:
the merge commit takes that subject, and `release.yml` then skips the bump
(otherwise `cz bump` finds nothing to bump and the job fails). Not
`[skip ci]`, which would also skip the branch's tests and auto-merge.
Keep `feature/`, `fix/`, `bug/` and `break/` with a ticket out of the branch
name too: the merge commit names the branch, and that alone would bump.

`versionCode` is `major * 10000 + minor * 100 + patch` (minimum 1), written
as a literal so F-Droid's `checkupdates` can read it at each tag. The
commitizen `pre_bump_hooks` entry, `bin/fdroid-bump.py`, rewrites it on every
bump and writes `fastlane/.../changelogs/<versionCode>.txt` from the new
`CHANGELOG.md` section (features first, cut at F-Droid's 500 characters). The
hook refuses minor or patch of 100 or more, since 1.100.0 would collide with
2.0.0, and `VersionCodeTest` fails if versionCode and versionName disagree.

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

The full local gate before a branch goes anywhere adds coverage and a release
build (unsigned unless the `ANDROID_KEYSTORE_*` variables are set):
`clean testDebugUnitTest koverXmlReport lintDebug assembleDebug assembleRelease`.
