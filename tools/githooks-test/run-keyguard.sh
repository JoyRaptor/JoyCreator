#!/usr/bin/env bash
# L27: the key guard refuses a commit that adds a key, and lets ordinary changes through.
# Builds a throw-away repository, so nothing real is touched.
# Usage: bash tools/githooks-test/run-keyguard.sh
set -u
here="$(cd "$(dirname "$0")/../.." && pwd)"
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
cd "$T" && git init -q . && git config user.email t@t && git config user.name t
pass=0; fail=0
check() { if [ "$2" = "$3" ]; then pass=$((pass+1)); else fail=$((fail+1)); echo "FAIL $1 (wanted $2, got $3)"; fi; }
guard() { bash "$here/.githooks/keyguard.sh" >/dev/null 2>&1; echo $?; }

echo "hello world" > a.txt; git add a.txt
check "an ordinary change passes" 0 "$(guard)"; git reset -q

# keys are built at run time so this file never contains one
k1="sk-or-v1-$(printf 'a%.0s' $(seq 1 40))"; echo "key=$k1" > notes.txt; git add notes.txt
check "an OpenRouter-style key is refused" 1 "$(guard)"; git reset -q; rm notes.txt
k2="ghp_$(printf 'b%.0s' $(seq 1 36))"; echo "$k2" > n.txt; git add n.txt
check "a GitHub token is refused" 1 "$(guard)"; git reset -q; rm n.txt
k3="AIza$(printf 'c%.0s' $(seq 1 35))"; echo "$k3" > n.txt; git add n.txt
check "a Google API key is refused" 1 "$(guard)"; git reset -q; rm n.txt
mkdir -p tasks; echo "anything" > tasks/api.txt; git add -f tasks/api.txt
check "a file called api.txt is refused" 1 "$(guard)"; git reset -q
echo "-----BEGIN RSA PRIV""ATE KEY-----" > k.pem; git add k.pem
check "a private key block is refused" 1 "$(guard)"; git reset -q
echo "the sk-ra word and ghp is just text" > t.txt; git add t.txt
check "words that merely look similar pass" 0 "$(guard)"; git reset -q

# the ignore rule really covers the old file
cp "$here/.gitignore" .gitignore; echo x > tasks/api.txt
check "tasks/api.txt is git-ignored" 0 "$(git check-ignore -q tasks/api.txt; echo $?)"

# the hook is wired
grep -q 'keyguard.sh' "$here/.githooks/pre-commit"; check "pre-commit runs the key guard" 0 $?
echo "keyguard test: $pass passed, $fail failed"; [ "$fail" = 0 ]
