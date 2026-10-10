#!/usr/bin/env python3
"""commitizen pre_bump_hook: keep what F-Droid reads in step with the bump.

cz has already rewritten versionName and CHANGELOG.md by the time this runs.
This rewrites the literal versionCode next to it and writes the release
notes F-Droid shows, fastlane/.../changelogs/<versionCode>.txt, from this
version's CHANGELOG.md section. cz commits with -a, which skips new files,
so the changelog file is staged here.

Run by hand: CZ_PRE_NEW_VERSION=1.2.3 bin/fdroid-bump.py
"""
import os
import re
import subprocess
import sys

GRADLE = "app/build.gradle.kts"
CHANGELOGS = "fastlane/metadata/android/en-US/changelogs"
LIMIT = 500  # F-Droid truncates longer changelogs


def version_code(version):
    major, minor, patch = (int(p) for p in version.split("."))
    # 1.100.0 would collide with 2.0.0, so the scheme stops at 99.
    if minor >= 100 or patch >= 100:
        sys.exit(f"FATAL: versionCode scheme needs minor and patch < 100: {version}")
    return max(1, major * 10000 + minor * 100 + patch)


def notes(version):
    text = open("CHANGELOG.md").read()
    m = re.search(r"^## (?:android-)?%s\b.*?\n(.*?)(?=^## |\Z)" % re.escape(version), text, re.S | re.M)
    # Features before fixes: a release-notes reader wants what is new first.
    sections = re.split(r"(?m)^(?=### )", m.group(1) if m else "")
    sections.sort(key=lambda s: s.startswith("### Bug Fixes"))
    lines = [l for s in sections for l in s.splitlines() if l.strip()]
    out = ""
    for line in lines:
        line = line[4:] + ":" if line.startswith("### ") else line
        if len(out) + len(line) + 1 > LIMIT - 1:
            return out + "…\n"
        out += line + "\n"
    return out or f"Release {version}.\n"


def main():
    version = os.environ["CZ_PRE_NEW_VERSION"]
    code = version_code(version)

    gradle = open(GRADLE).read()
    gradle, n = re.subn(r"(?m)^(\s*versionCode = )\d+$", rf"\g<1>{code}", gradle)
    if n != 1:
        sys.exit(f"FATAL: expected one versionCode line in {GRADLE}, found {n}")
    open(GRADLE, "w").write(gradle)

    os.makedirs(CHANGELOGS, exist_ok=True)
    path = f"{CHANGELOGS}/{code}.txt"
    open(path, "w").write(notes(version))
    subprocess.run(["git", "add", GRADLE, path], check=True)
    print(f"fdroid-bump: {version} -> versionCode {code}, {path}")


if __name__ == "__main__":
    main()
