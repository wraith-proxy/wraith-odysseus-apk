#!/usr/bin/env bash
# Does F-Droid's build reproduce this release? fdroiddata's "fdroid build" CI
# job for one tag: build it in F-Droid's buildserver image and compare with
# the APK on the Gitea release (Binaries:). F-Droid ships nothing for a
# release that fails this, so release.yml runs it right after publishing.
#
# Runs as root inside registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie,
# from a clone of this repo with tags. Locally:
#   docker run --rm -v "$PWD:/src" -w /src --entrypoint bash \
#     registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie \
#     bin/fdroid-repro.sh android-0.5.0
set -euo pipefail

TAG=$1
APPID=com.thomaspeoples.wraithodysseus
src=$PWD
source /etc/profile.d/bsenv.sh
git config --global --add safe.directory "$src"  # a bind mount is not root's

# What fdroiddata's CI sets up before "fdroid build", fdroidserver from master.
apt-get update -q && apt-get install -qy sudo openjdk-21-jdk-headless > /dev/null
update-alternatives --set java /usr/lib/jvm/java-21-openjdk-amd64/bin/java
sdkmanager "platform-tools" "build-tools;31.0.0" > /dev/null
rm -rf "$fdroidserver"
git clone -q --depth 1 https://gitlab.com/fdroid/fdroidserver.git "$fdroidserver"
git -C "$home_vagrant/gradlew-fdroid" pull -q || true

# The recipe with one build: this tag's versionName/versionCode at its full
# commit hash, as checkupdates would add it in fdroiddata.
mkdir -p "$home_vagrant/metadata" "$home_vagrant/tmp" "$home_vagrant/build" \
  "$home_vagrant/.gradle" "$home_vagrant/.android"
gradle=$(git -C "$src" show "$TAG:app/build.gradle.kts")
name=$(sed -nE 's/^\s*versionName = "([^"]+)"$/\1/p' <<< "$gradle")
code=$(sed -nE 's/^\s*versionCode = ([0-9]+)$/\1/p' <<< "$gradle")
commit=$(git -C "$src" rev-parse "$TAG^{commit}")
python3 - "$src/docs/fdroid/$APPID.yml" "$home_vagrant/metadata/$APPID.yml" "$name" "$code" "$commit" <<'PY'
import re, sys
src, dst, name, code, commit = sys.argv[1:]
s = open(src).read()
s = re.sub(r"(?ms)^Builds:\n.*?(?=^\S)", f"""Builds:
  - versionName: {name}
    versionCode: {code}
    commit: {commit}
    subdir: app
    gradle:
      - yes

""", s)
s = re.sub(r"(?m)^CurrentVersion: .*$", f"CurrentVersion: {name}", s)
s = re.sub(r"(?m)^CurrentVersionCode: .*$", f"CurrentVersionCode: {code}", s)
open(dst, "w").write(s)
PY
chown -R vagrant "$home_vagrant"

# Exits non-zero when the built APK and the release APK differ.
cd "$home_vagrant"
run="sudo --preserve-env --user vagrant env PATH=$fdroidserver:$PATH PYTHONPATH=$fdroidserver:$fdroidserver/examples PYTHONUNBUFFERED=true HOME=$home_vagrant GRADLE_USER_HOME=$home_vagrant/.gradle"
$run fdroid fetchsrclibs "$APPID:$code" --verbose
(unset CI; $run fdroid build --verbose --test --refresh-scanner --on-server --no-tarball "$APPID:$code")
echo "ok: F-Droid reproduces $TAG ($code)"
