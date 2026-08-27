#!/usr/bin/env bash
set -euo pipefail

cd "${CI_PROJECT_DIR:-.}"

if [[ -f "config.yml" ]]; then
  chmod 600 config.yml || true
fi

# Fail fast on the specific schema violation seen in CI logs.
if grep -RIn --include="*.yml" -E '^[[:space:]]*UpdateCheckMode:[[:space:]]*Manual[[:space:]]*$' metadata >/dev/null 2>&1; then
  echo "ERROR: UpdateCheckMode: Manual is invalid for F-Droid metadata."
  echo "Use one of: None, Static, HTTP, Tags, RepoManifest, RepoManifest/..., Tags <pattern>."
  echo ""
  echo "Offending lines:"
  grep -RIn --include="*.yml" -E '^[[:space:]]*UpdateCheckMode:[[:space:]]*Manual[[:space:]]*$' metadata || true
  exit 1
fi

# Print the effective update check modes for visibility in CI logs.
echo "UpdateCheckMode values found:"
grep -RIn --include="*.yml" -E '^[[:space:]]*UpdateCheckMode:[[:space:]]*' metadata || true

