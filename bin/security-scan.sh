#!/usr/bin/env bash
# Secrets (gitleaks), code patterns (semgrep) and dependency CVEs
# (Trivy). test.yml runs this; locally, run it inside the runner image
# (docs/ci.md). Tools are downloaded at pinned versions and checked against
# pinned SHA-256s until they are baked into the image (docs/ROADMAP.md).
set -euo pipefail

GITLEAKS=8.21.2
GITLEAKS_SHA256=5bc41815076e6ed6ef8fbecc9d9b75bcae31f39029ceb55da08086315316e3ba
TRIVY=0.74.0
TRIVY_SHA256=2ae6fe3ee734b7fdf11335663e18c75ea12dccc76062f09f164a3b0f8be4371a
SEMGREP=1.99.0

tools=$(mktemp -d)
trap 'rm -rf -- "${tools:?}" app/gradle.lockfile' EXIT

curl -fsSL -o "$tools/gitleaks.tgz" \
  "https://github.com/gitleaks/gitleaks/releases/download/v$GITLEAKS/gitleaks_${GITLEAKS}_linux_x64.tar.gz"
echo "$GITLEAKS_SHA256  $tools/gitleaks.tgz" | sha256sum -c --quiet -
tar -xzf "$tools/gitleaks.tgz" -C "$tools" gitleaks

curl -fsSL -o "$tools/trivy.tgz" \
  "https://github.com/aquasecurity/trivy/releases/download/v$TRIVY/trivy_${TRIVY}_Linux-64bit.tar.gz"
echo "$TRIVY_SHA256  $tools/trivy.tgz" | sha256sum -c --quiet -
tar -xzf "$tools/trivy.tgz" -C "$tools" trivy

# No venv: the image lacks python3-venv.
pip3 install -q --disable-pip-version-check --target "$tools/py" "semgrep==$SEMGREP"
export PYTHONPATH="$tools/py" PATH="$tools/py/bin:$PATH"  # semgrep execs pysemgrep

echo "== gitleaks: every commit"
"$tools/gitleaks" git --no-banner --redact .

# The launcher activity has to be exported; that is the one expected hit.
echo "== semgrep"
semgrep scan --error --quiet --metrics=off \
  --config p/kotlin --config p/java --config p/mobsfscan \
  --config p/secrets --config p/owasp-top-ten \
  --exclude-rule java.android.security.exported_activity.exported_activity \
  app/src/main

echo "== trivy: libraries shipped in the APK"
./gradlew --no-daemon -q --init-script bin/lock-release.gradle :app:dependencies --configuration releaseRuntimeClasspath --write-locks > /dev/null
# DBs pinned to ghcr.io: Trivy's default is mirror.gcr.io (Google).
"$tools/trivy" fs --quiet --scanners vuln --exit-code 1 --skip-version-check \
  --cache-dir "$tools/trivy-cache" \
  --db-repository ghcr.io/aquasecurity/trivy-db:2 \
  --java-db-repository ghcr.io/aquasecurity/trivy-java-db:1 \
  app/gradle.lockfile
