#!/bin/sh
# Install the guardrail hook into this clone (copy, so an existing graphify
# post-commit hook keeps working — core.hooksPath would disable it).
set -e
here=$(cd "$(dirname "$0")" && pwd); root=$(git rev-parse --show-toplevel)
cp "$here/pre-commit" "$root/.git/hooks/pre-commit"; chmod +x "$root/.git/hooks/pre-commit"
echo "Installed pre-commit guardrail: code changes now require a version bump."
