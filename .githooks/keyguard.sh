#!/usr/bin/env bash
# Refuses a commit that adds something that looks like a secret key (L27: tasks/api.txt once held a
# real key and GitHub blocks pushes that contain one). Looks only at lines being ADDED.
# If it fires on something that is not a key, say so in your report; do not bypass with --no-verify.
# Usage: keyguard.sh            (checks the staged changes)
pattern='sk-or-v1-[A-Za-z0-9]{20,}|sk-ant-[A-Za-z0-9_-]{20,}|sk-[A-Za-z0-9]{40,}|ghp_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,}|AIza[0-9A-Za-z_-]{35}|AKIA[0-9A-Z]{16}|-----BEGIN ([A-Z]+ )?PRIVATE KEY-----'
hits=$(git diff --cached -U0 --no-color 2>/dev/null | grep -E '^\+[^+]' | grep -E "$pattern" | head -5)
staged_secret_file=$(git diff --cached --name-only 2>/dev/null | grep -E '(^|/)(api\.txt|\.env|[^/]*\.jks|[^/]*\.keystore)$' | head -3)
if [ -n "$hits" ] || [ -n "$staged_secret_file" ]; then
  echo "keyguard: this commit adds something that looks like a secret key or a key file." >&2
  [ -n "$staged_secret_file" ] && echo "  file(s): $staged_secret_file" >&2
  [ -n "$hits" ] && echo "  a line matches a key pattern (not shown here, so the key does not reach the log)." >&2
  echo "  Keep keys out of git: put them in local.properties or the environment, and rotate any that were committed." >&2
  exit 1
fi
exit 0
