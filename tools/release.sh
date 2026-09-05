#!/usr/bin/env bash
# Cuts a release, or as much of one as a script has any business cutting: bumps the version in both
# places that carry it, runs the consistency checks and the tests, and packs the ZIP — then stops and
# says what is left, because what is left is reading the diff and deciding.
#
#   tools/release.sh 1.5.0            # bump to 1.5.0, check, test, pack
#   tools/release.sh                  # the same, against the version already in the manifest
#   tools/release.sh --tag            # …and tag it (the bump must be committed first)
#   tools/release.sh --tag --push     # …and push the tag, which is what actually publishes
#   tools/release.sh --skip-tests     # for a re-run, when nothing has changed since the last one
#
# Pushing the tag is the irreversible half: the release workflow builds the ZIP, attaches it to a
# GitHub Release, and uploads it to the Web Store as a draft. Hence two separate flags and, unless
# --yes, a question.
set -euo pipefail
cd "$(dirname "$0")/.."

MANIFEST="extension/src/jsMain/resources/manifest.json"
ABOUT="ui-shared/src/jsMain/kotlin/stramus/ui/About.kt"

VERSION=""
TAG=0
PUSH=0
TESTS=1
YES=0

while [ $# -gt 0 ]; do
    case "$1" in
        --tag) TAG=1 ;;
        --push) PUSH=1; TAG=1 ;;
        --skip-tests) TESTS=0 ;;
        --yes) YES=1 ;;
        -h|--help) awk 'NR>1 && /^#/ { sub(/^# ?/, ""); print; next } NR>1 { exit }' "$0"; exit 0 ;;
        -*) echo "release.sh: unknown argument $1" >&2; exit 2 ;;
        *) VERSION="$1" ;;
    esac
    shift
done

current() { python3 -c 'import json;print(json.load(open("'"$MANIFEST"'"))["version"])'; }

step() { printf '\n\033[1m== %s\033[0m\n' "$1"; }

if [ -n "$VERSION" ]; then
    if ! printf '%s' "$VERSION" | grep -qE '^[0-9]+(\.[0-9]+){0,3}$'; then
        echo "release.sh: $VERSION is not a version the Web Store accepts (one to four numbers)" >&2
        exit 2
    fi
    WAS=$(current)
    if [ "$WAS" = "$VERSION" ]; then
        step "Version is already $VERSION"
    else
        step "Bumping $WAS → $VERSION"
        # Both files, in one go: the manifest is what the store keys a submission by, About.kt is what
        # the user reads in the About pane, and they have drifted apart before.
        python3 - "$MANIFEST" "$VERSION" <<'BUMP'
import json, re, sys

path, version = sys.argv[1], sys.argv[2]
with open(path, encoding="utf-8") as f:
    source = f.read()
# Edited as text, not as a reserialised object: the manifest is hand-written and its formatting and
# comments-by-key-order are worth more than a tidy dump.
updated, count = re.subn(r'("version"\s*:\s*)"[^"]+"', lambda m: m.group(1) + json.dumps(version), source, count=1)
if count != 1:
    sys.exit(f"could not find the version in {path}")
with open(path, "w", encoding="utf-8") as f:
    f.write(updated)
BUMP
        sed -i -E "s/(const val APP_VERSION = )\"[^\"]+\"/\1\"$VERSION\"/" "$ABOUT"
        echo "  $MANIFEST"
        echo "  $ABOUT"
        echo "The listings' \"what's new\" lines are yours to write — store/listing-*.md."
    fi
fi

VERSION=$(current)

step "Consistency checks"
if [ "$TAG" = 1 ]; then
    python3 tools/preflight.py --tag "v$VERSION"
else
    python3 tools/preflight.py
fi

if [ "$TESTS" = 1 ]; then
    step "Tests"
    ./gradlew :core:jvmTest :server:test --quiet
    echo "  green"
fi

step "Packing the extension"
ZIP=$(tools/package-extension.sh)

if [ "$TAG" = 1 ]; then
    step "Tagging v$VERSION"
    if [ -n "$(git status --porcelain)" ]; then
        # The tag has to point at a commit that carries this version, or the workflow's own check
        # fails after the push — the expensive place to find out.
        echo "The working tree is dirty. Commit the version bump first, then re-run with --tag." >&2
        git status --short >&2
        exit 1
    fi
    if git rev-parse -q --verify "refs/tags/v$VERSION" >/dev/null; then
        echo "Tag v$VERSION already exists." >&2
        exit 1
    fi
    git tag -a "v$VERSION" -m "stramus $VERSION"
    echo "  created (not pushed)"
fi

if [ "$PUSH" = 1 ]; then
    step "Pushing v$VERSION"
    if [ "$YES" != 1 ]; then
        echo "This builds the release, attaches the ZIP to a GitHub Release, and uploads it to the"
        echo "Chrome Web Store as a draft."
        read -r -p "Push v$VERSION to origin? [y/N] " answer
        case "$answer" in [yY]*) ;; *) echo "Left the tag local. Push it with: git push origin v$VERSION"; exit 0 ;; esac
    fi
    git push origin "v$VERSION"
fi

step "What is left (store/README.md has the long form)"
cat <<NEXT
  - the ZIP, if you want to load it unpacked before it goes anywhere: $ZIP
  - screenshots, if the UI moved:   cd tools/screenshots && node capture.mjs   → store/screenshots/
  - the store icon, if the mark moved:  node tools/screenshots/store-icon.mjs
  - privacy.html read against what this build actually does
  - the permission list compared with the published version — a new warning disables the extension
    for every existing user until they accept it
  - after the tag: upload nothing by hand; the workflow leaves a draft in the developer console,
    where the listing text from store/listing-*.md is pasted and the item is submitted
NEXT
