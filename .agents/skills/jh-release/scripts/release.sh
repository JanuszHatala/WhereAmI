#!/bin/bash
# WhereAmI release steps. Each subcommand is one user-authorised action; run only what was asked.
#   release.sh check                      branch/version/clean-tree checks + build gate
#   release.sh install                    adb install -r the debug APK, print installed version
#   release.sh pr "<title>" <body.md>     push branch, open PR into master, watch required checks
#   release.sh merge <pr-number>          merge via PR (merge commit), delete branch, sync master, prune
# Needs JDK 17 (JAVA_HOME) and ANDROID_HOME for the gate.
set -euo pipefail
ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"
GRADLE="app/build.gradle.kts"
APK="app/build/outputs/apk/debug/app-debug.apk"
PKG="janush.tech.whereami"

version_of() { sed -nE "s/^[[:space:]]*$1 = \"?([^\"]+)\"?/\1/p" "${2:-$GRADLE}" | head -1; }

cmd_check() {
    local branch; branch="$(git branch --show-current)"
    [ "$branch" != "master" ] || { echo "On master: create a feat/ or fix/ branch first." >&2; exit 1; }
    [ -z "$(git status --porcelain)" ] || { echo "Uncommitted changes:" >&2; git status --short >&2; exit 1; }
    git fetch -q origin master
    local base_code head_code base_name head_name
    base_code="$(git show origin/master:$GRADLE | sed -nE 's/^[[:space:]]*versionCode = ([0-9]+).*/\1/p')"
    base_name="$(git show origin/master:$GRADLE | sed -nE 's/^[[:space:]]*versionName = "([^"]+)".*/\1/p')"
    head_code="$(version_of versionCode)"; head_name="$(version_of versionName)"
    echo "version: master $base_name ($base_code) -> branch $head_name ($head_code)"
    if git diff --name-only origin/master...HEAD | grep -q '^app/'; then
        [ "$head_code" -gt "$base_code" ] || { echo "versionCode not incremented (AGENTS.md section 4.4)." >&2; exit 1; }
        [ "$head_name" != "$base_name" ] || { echo "versionName not incremented." >&2; exit 1; }
    else
        echo "no app/ changes: version bump not required"
    fi
    git merge-base --is-ancestor origin/master HEAD || echo "WARNING: branch is behind origin/master; rebase or merge master first." >&2
    sh ./gradlew testDebugUnitTest assembleDebug 2>&1 | grep -E "FAILED|e: |BUILD"
}

cmd_install() {
    adb devices | grep -qw device || { echo "No ADB device connected." >&2; exit 1; }
    [ -f "$APK" ] || { echo "Missing $APK: run 'release.sh check' first." >&2; exit 1; }
    adb install -r "$APK"
    adb shell dumpsys package "$PKG" | grep -E "versionCode|versionName" | head -2
}

cmd_pr() {
    local title="$1" body="$2" branch; branch="$(git branch --show-current)"
    [ "$branch" != "master" ] || { echo "Refusing to push master." >&2; exit 1; }
    git push -u origin "$branch"
    gh pr create --base master --head "$branch" --title "$title" --body-file "$body"
    for _ in $(seq 1 30); do
        gh pr checks "$branch" >/dev/null 2>&1 && break   # checks register a few seconds after the push
        sleep 5
    done
    gh pr checks "$branch" --watch --interval 20
}

cmd_merge() {
    local pr="$1" branch
    branch="$(gh pr view "$pr" --json headRefName -q .headRefName)"
    gh pr checks "$pr" | awk -F'\t' '$2 != "pass" && $2 != "skipping" { bad=1 } END { exit bad }' \
        || { echo "Checks not green for PR $pr." >&2; gh pr checks "$pr" >&2; exit 1; }
    gh pr merge "$pr" --merge --delete-branch
    git checkout -q master && git pull -q origin master
    git branch -d "$branch" 2>/dev/null || true
    git remote prune origin
    git log --oneline -1
}

case "${1:-}" in
    check) cmd_check ;;
    install) cmd_install ;;
    pr) shift; cmd_pr "$@" ;;
    merge) shift; cmd_merge "$@" ;;
    *) sed -n '2,7p' "$0"; exit 2 ;;
esac
