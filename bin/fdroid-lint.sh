#!/usr/bin/env bash
# F-Droid checks. test.yml runs this; locally, run it inside the runner image
# (docs/ci.md). fdroidserver is baked into the image at a pinned version
# (ci-images/Dockerfile.android).
set -euo pipefail

APPID=com.thomaspeoples.wraithodysseus

tools=$(mktemp -d)
trap 'rm -rf -- "${tools:?}"' EXIT

echo "== fdroid lint: the recipe, as fdroiddata would see it"
# Categories are validated against fdroiddata's live list, which changes;
# checking against master is checking against what the MR will face.
mkdir -p "$tools/fdroiddata/metadata" "$tools/fdroiddata/config"
curl -fsSL -o "$tools/fdroiddata/config/categories.yml" \
  https://gitlab.com/fdroid/fdroiddata/-/raw/master/config/categories.yml
sed -i '/^\s*icon:/d' "$tools/fdroiddata/config/categories.yml"  # lint copies icons it never checks
cp "docs/fdroid/$APPID.yml" "$tools/fdroiddata/metadata/"
(cd "$tools/fdroiddata" && fdroid lint "$APPID")

# F-Droid shows changelogs/<versionCode>.txt as the release notes. The bump
# hook writes it; this catches a bump that ran without the hook.
echo "== fastlane changelog for the current versionCode"
code=$(sed -nE 's/^\s*versionCode = ([0-9]+)$/\1/p' app/build.gradle.kts)
test -s "fastlane/metadata/android/en-US/changelogs/$code.txt" \
  || { echo "FATAL: no changelogs/$code.txt (run CZ_PRE_NEW_VERSION=x.y.z bin/fdroid-bump.py)"; exit 1; }
echo "ok: $code"
