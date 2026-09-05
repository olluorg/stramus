#!/usr/bin/env bash
# Builds the extension and packs the loadable/uploadable ZIP — the same ZIP the release workflow
# attaches to a GitHub Release and uploads to the Web Store, produced by the same script, so that
# "it works locally" and "it works in CI" mean the same thing.
#
#   tools/package-extension.sh                 # build, then pack into ./build/release/
#   tools/package-extension.sh --skip-build    # pack what is already in extension/build/dist
#   tools/package-extension.sh --out /tmp      # somewhere else
#
# Progress goes to stderr and the path of the finished ZIP to stdout, so a caller can do
#   ZIP=$(tools/package-extension.sh --skip-build)
# and get a path rather than a transcript.
set -euo pipefail
# Kept before the cd, so that a relative --out means what it says where it was typed rather than
# somewhere under the repository root.
INVOKED_FROM="$PWD"
cd "$(dirname "$0")/.."

BUILD=1
OUT="$PWD/build/release"

while [ $# -gt 0 ]; do
    case "$1" in
        --skip-build) BUILD=0 ;;
        --out)
            [ $# -ge 2 ] || { echo "package-extension.sh: --out needs a directory" >&2; exit 2; }
            case "$2" in /*) OUT="$2" ;; *) OUT="$INVOKED_FROM/$2" ;; esac
            shift
            ;;
        -h|--help) awk 'NR>1 && /^#/ { sub(/^# ?/, ""); print; next } NR>1 { exit }' "$0"; exit 0 ;;
        *) echo "package-extension.sh: unknown argument $1" >&2; exit 2 ;;
    esac
    shift
done

DIST="extension/build/dist/js/productionExecutable"
VERSION=$(python3 -c 'import json;print(json.load(open("extension/src/jsMain/resources/manifest.json"))["version"])')

if [ "$BUILD" = 1 ]; then
    echo "Building the production extension bundle (version $VERSION)…" >&2
    ./gradlew :extension:jsBrowserDistribution --quiet
fi

[ -d "$DIST" ] || { echo "package-extension.sh: $DIST is not there — run without --skip-build" >&2; exit 1; }

# The manifest names its strings out of _locales; without them Chrome refuses to load the extension at
# all ("Localization used, but default_locale wasn't specified"). stramus.js.LICENSE.txt is not build
# litter either: it carries the copyright notices of what webpack compiled in (React's, MIT), and MIT
# asks that those travel with the copies. Their absence would mean the build changed under us.
for required in manifest.json _locales/en/messages.json _locales/ru/messages.json stramus.js.LICENSE.txt; do
    [ -f "$DIST/$required" ] || { echo "Missing from the bundle: $required" >&2; exit 1; }
done

# The bundle's manifest is a copied resource, so a stale build is a real possibility — and a ZIP named
# after one version containing another is rejected by the store under the name it carries, or worse,
# accepted.
BUILT=$(python3 -c 'import json;print(json.load(open("'"$DIST"'/manifest.json"))["version"])')
if [ "$BUILT" != "$VERSION" ]; then
    echo "The built bundle says version $BUILT, the source manifest says $VERSION — rebuild." >&2
    exit 1
fi

mkdir -p "$OUT"
OUT=$(cd "$OUT" && pwd)
ZIP="$OUT/stramus-extension-$VERSION.zip"
rm -f "$ZIP"

# Packed by Python rather than by `zip`: python3 is already needed above to read the manifest, and
# `zip` is not on every machine that can build this (a bare WSL install has neither). Files go in in
# sorted order, so the same bundle packs to the same archive twice running. Source maps are dropped —
# they are most of the size and none of the extension.
python3 - "$DIST" "$ZIP" <<'PACK'
import os, sys, zipfile

dist, out = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as archive:
    for folder, dirs, files in os.walk(dist):
        dirs.sort()
        for name in sorted(files):
            if name.endswith(".map"):
                continue
            path = os.path.join(folder, name)
            archive.write(path, os.path.relpath(path, dist))
PACK

echo "Packed $(du -h "$ZIP" | cut -f1) → $ZIP" >&2
echo "$ZIP"
