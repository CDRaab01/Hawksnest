#!/bin/bash
# SessionStart hook: install everything a Claude Code on the web session needs to run Hawksnest's
# checks — the web dashboard's npm deps and (in the remote env) the Android SDK so `:app` unit tests
# and the Sift design audit run without a device. Idempotent and non-interactive.
set -euo pipefail

cd "${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel)}"

# Web dashboard deps (idempotent — skipped once node_modules exists / is cached).
test -d node_modules || npm ci

# Android SDK only in the remote (web) environment; local dev manages its own SDK install.
if [ "${CLAUDE_CODE_REMOTE:-}" = "true" ]; then
  bash scripts/setup-android-sdk.sh
  # Persist ANDROID_HOME for the rest of the session so the Sift composite build (the included Sift
  # build) also finds the SDK; Gradle additionally reads android/local.properties, which the
  # installer writes.
  if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
    echo "export ANDROID_HOME=${ANDROID_HOME:-$HOME/android-sdk}" >> "$CLAUDE_ENV_FILE"
  fi
fi
