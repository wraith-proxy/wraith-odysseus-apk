#!/usr/bin/env bash
# Secrets (gitleaks), code patterns (semgrep) and dependency CVEs
# (Trivy). test.yml runs this; locally, run it inside the runner image
# (docs/ci.md). gitleaks, semgrep and Trivy are baked into the image at
# pinned versions (ci-images/Dockerfile.android).
set -euo pipefail

tools=$(mktemp -d)
trap 'rm -rf -- "${tools:?}" app/gradle.lockfile' EXIT

echo "== gitleaks: every commit"
gitleaks git --no-banner --redact .

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
trivy fs --quiet --scanners vuln --exit-code 1 --skip-version-check \
  --cache-dir "$tools/trivy-cache" \
  --db-repository ghcr.io/aquasecurity/trivy-db:2 \
  --java-db-repository ghcr.io/aquasecurity/trivy-java-db:1 \
  app/gradle.lockfile
