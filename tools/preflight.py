#!/usr/bin/env python3
"""
Checks the things about this repository that nothing else checks, and that only announce themselves
after a release has gone out: a version bumped in one file and not the other, a locale missing a key
the manifest names, a listing still advertising the previous release's "what's new", a screenshot
that is no longer 1280x800.

    python3 tools/preflight.py                  # everything, warnings stay warnings
    python3 tools/preflight.py --tag v1.5.0     # also: the tag must agree with the manifest
    python3 tools/preflight.py --release        # warnings become failures

Standard library only, and no build: it runs in a second, which is why CI can afford it before the
JDK is even installed, and why it is worth running by hand before a tag rather than after one.

The rules here are the ones written down in store/README.md and in the comments of the files
themselves — this file is not a second source of truth, it is the part of those documents a machine
can read. Where a limit belongs to the Chrome Web Store rather than to us, the comment says so.
"""

import argparse
import json
import os
import re
import struct
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

EXT_RES = os.path.join(ROOT, "extension", "src", "jsMain", "resources")
MANIFEST = os.path.join(EXT_RES, "manifest.json")
LOCALES = os.path.join(EXT_RES, "_locales")
ABOUT_KT = os.path.join(ROOT, "ui-shared", "src", "jsMain", "kotlin", "stramus", "ui", "About.kt")
I18N_KT = os.path.join(ROOT, "ui-shared", "src", "jsMain", "kotlin", "stramus", "ui", "I18n.kt")
SHOTS_MJS = os.path.join(ROOT, "tools", "screenshots", "shots.mjs")
STORE = os.path.join(ROOT, "store")

# Chrome Web Store limits. The short description is the one that bites: the store truncates nothing,
# it refuses the upload.
MAX_SHORT_DESCRIPTION = 132
MAX_NAME = 75
MAX_SHORT_NAME = 12
MAX_DETAILED_DESCRIPTION = 16000

STORE_SHOT_SIZE = (1280, 800)
STORE_ICON_SIZE = (128, 128)


class Report:
    """Collects what each check found, so the run says everything wrong at once rather than the
    first thing wrong. A release that fails on one line, is fixed, and fails on the next line is a
    slower way to learn the same list."""

    def __init__(self, release):
        self.release = release
        self.failures = []
        self.warnings = []
        self.section = None

    def start(self, name):
        self.section = name
        print(f"\n{name}")

    def ok(self, message):
        print(f"  ok    {message}")

    def fail(self, message):
        print(f"  FAIL  {message}")
        self.failures.append(f"{self.section}: {message}")

    def warn(self, message):
        """Something that is wrong before a tag and merely unfinished before that — a listing not yet
        rewritten for the version being prepared. --release (and --tag, which implies it) makes these
        count."""
        if self.release:
            print(f"  FAIL  {message}")
            self.failures.append(f"{self.section}: {message}")
        else:
            print(f"  warn  {message}")
            self.warnings.append(f"{self.section}: {message}")


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def read_json(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def png_size(path):
    """Width and height out of the IHDR chunk, without an image library — the header is at a fixed
    offset in every PNG, and this is the only thing we need to know about these files."""
    with open(path, "rb") as f:
        header = f.read(24)
    if header[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("not a PNG")
    return struct.unpack(">II", header[16:24])


def locale_dirs():
    return sorted(d for d in os.listdir(LOCALES) if os.path.isdir(os.path.join(LOCALES, d)))


def listing_tag(locale):
    """`_locales` spells a region with an underscore, the listing files with a hyphen: pt_BR is
    listing-pt-BR.md. One rule, in one place, so the two sets can be compared at all."""
    return locale.replace("_", "-")


def app_version():
    match = re.search(r'const val APP_VERSION = "([^"]+)"', read(ABOUT_KT))
    return match.group(1) if match else None


def ui_languages():
    """The ids of the `Lang` enum — the languages the UI itself speaks. Parsed rather than duplicated:
    a language added there and nowhere else is exactly what this file exists to notice."""
    source = read(I18N_KT)
    body = re.search(r"enum class Lang\b.*?\n(.*?)\n\s*;", source, re.S)
    if not body:
        return None
    return [m.group(1) for m in re.finditer(r'^\s*[A-Z][A-Z_0-9]*\("([^"]+)"', body.group(1), re.M)]


def store_shot_ids():
    """The shots the listing is made of, read out of the screenshot tool's own catalogue so that
    dropping a shot there does not leave this file demanding a file nobody generates any more."""
    match = re.search(r"export const STORE_SHOT_IDS = \[(.*?)\]", read(SHOTS_MJS), re.S)
    if not match:
        return None
    return re.findall(r"['\"]([^'\"]+)['\"]", match.group(1))


def check_versions(report, manifest, tag):
    report.start("Version")
    manifest_version = manifest.get("version")
    about = app_version()

    if not manifest_version:
        report.fail("manifest.json has no version")
    elif not re.fullmatch(r"\d{1,5}(\.\d{1,5}){0,3}", manifest_version) or any(
        int(part) > 65535 or (len(part) > 1 and part.startswith("0")) for part in manifest_version.split(".")
    ):
        # The store's own rule: one to four dot-separated integers, each 0-65535, no leading zeros.
        report.fail(f"manifest version {manifest_version!r} is not a version the Web Store accepts")
    else:
        report.ok(f"manifest.json: {manifest_version}")

    if about is None:
        report.fail("APP_VERSION not found in About.kt")
    elif about != manifest_version:
        # The About pane is the only place a user can read which build they are running, and it is
        # kept in step by hand. This is the check its own comment says does not exist.
        report.fail(f"About.kt says {about}, manifest.json says {manifest_version}")
    else:
        report.ok(f"About.kt APP_VERSION agrees: {about}")

    if tag:
        wanted = tag[1:] if tag.startswith("v") else tag
        if wanted != manifest_version:
            report.fail(f"tag {tag} does not match manifest version {manifest_version}")
        else:
            report.ok(f"tag {tag} matches")

    return manifest_version


def check_manifest(report, manifest):
    report.start("Manifest")

    raw = read(MANIFEST)
    if "localhost" in raw:
        # store/README.md: developing against a local server means adding host_permissions back in
        # your own working copy and not committing it. A published extension reaching a local server
        # is a permission warning for nothing.
        report.fail("manifest.json mentions localhost — that belongs in a working copy, not in a commit")
    else:
        report.ok("no localhost in the manifest")

    short_name = manifest.get("short_name", "")
    if len(short_name) > MAX_SHORT_NAME:
        report.fail(f"short_name is {len(short_name)} characters; Chrome allows {MAX_SHORT_NAME}")
    else:
        report.ok(f"short_name fits ({len(short_name)}/{MAX_SHORT_NAME})")

    icons = list(manifest.get("icons", {}).values())
    icons += list(manifest.get("action", {}).get("default_icon", {}).values())
    missing = sorted({icon for icon in icons if not os.path.exists(os.path.join(EXT_RES, icon))})
    if missing:
        report.fail("icons named by the manifest are not in resources: " + ", ".join(missing))
    else:
        report.ok(f"{len(set(icons))} icon files present")

    default_locale = manifest.get("default_locale")
    if default_locale and not os.path.isdir(os.path.join(LOCALES, default_locale)):
        report.fail(f"default_locale is {default_locale!r}, and _locales/{default_locale} does not exist")
    elif default_locale:
        report.ok(f"default_locale {default_locale} exists")


def check_locales(report, manifest):
    report.start("Extension locales")

    dirs = locale_dirs()
    default_locale = manifest.get("default_locale", "en")
    messages = {}
    for locale in dirs:
        path = os.path.join(LOCALES, locale, "messages.json")
        if not os.path.exists(path):
            report.fail(f"_locales/{locale} has no messages.json")
            continue
        try:
            messages[locale] = read_json(path)
        except json.JSONDecodeError as error:
            report.fail(f"_locales/{locale}/messages.json is not valid JSON: {error}")

    if default_locale not in messages:
        return messages
    reference = set(messages[default_locale])
    report.ok(f"{len(dirs)} locales, {len(reference)} keys in {default_locale}")

    for locale, table in sorted(messages.items()):
        if locale == default_locale:
            continue
        missing = sorted(reference - set(table))
        extra = sorted(set(table) - reference)
        if missing:
            # Chrome falls back to the default locale for a missing key, silently: the German user
            # simply reads English and nobody hears about it.
            report.fail(f"_locales/{locale} is missing: {', '.join(missing)}")
        if extra:
            report.warn(f"_locales/{locale} has keys {default_locale} does not: {', '.join(extra)}")
    if all(set(table) == reference for locale, table in messages.items()):
        report.ok("every locale carries the same keys")

    for locale, table in sorted(messages.items()):
        for key, entry in sorted(table.items()):
            text = (entry or {}).get("message", "")
            if not text.strip():
                report.fail(f"_locales/{locale}: {key} is empty")

    # Placeholders the manifest resolves through _locales. A missing one is not a fallback: Chrome
    # refuses to load the extension at all.
    named = sorted(set(re.findall(r"__MSG_([A-Za-z0-9_]+)__", read(MANIFEST))))
    for key in named:
        absent = sorted(locale for locale, table in messages.items() if key not in table)
        if absent:
            report.fail(f"manifest names __MSG_{key}__, absent from: {', '.join(absent)}")
    if named and not any(key not in table for table in messages.values() for key in named):
        report.ok(f"every __MSG_…__ the manifest names resolves in all {len(messages)} locales ({len(named)} keys)")

    within_limits = True
    for locale, table in sorted(messages.items()):
        description = table.get("extDescription", {}).get("message", "")
        if len(description) > MAX_SHORT_DESCRIPTION:
            report.fail(
                f"_locales/{locale}: extDescription is {len(description)} characters; "
                f"the Web Store allows {MAX_SHORT_DESCRIPTION}"
            )
            within_limits = False
        name = table.get("extName", {}).get("message", "")
        if len(name) > MAX_NAME:
            report.fail(f"_locales/{locale}: extName is {len(name)} characters; the Web Store allows {MAX_NAME}")
            within_limits = False
    if within_limits:
        report.ok("names and short descriptions are within the store's limits")

    return messages


def check_languages(report, messages):
    report.start("Languages")

    ui = ui_languages()
    if ui is None:
        report.fail("could not read the Lang enum out of I18n.kt")
        return
    from_ui = {lang.replace("-", "_") for lang in ui}
    from_locales = set(messages)
    if from_ui - from_locales:
        report.fail("the UI speaks languages the extension does not name itself in: " + ", ".join(sorted(from_ui - from_locales)))
    if from_locales - from_ui:
        report.fail("_locales carries languages the UI does not have: " + ", ".join(sorted(from_locales - from_ui)))
    if from_ui == from_locales:
        report.ok(f"{len(ui)} UI languages, and _locales matches them")

    for locale in sorted(from_locales):
        path = os.path.join(STORE, f"listing-{listing_tag(locale)}.md")
        if not os.path.exists(path):
            report.fail(f"no store/listing-{listing_tag(locale)}.md for the {locale} listing")
    listings = {re.sub(r"^listing-|\.md$", "", f) for f in os.listdir(STORE) if f.startswith("listing-")}
    orphans = sorted(listings - {listing_tag(locale) for locale in from_locales})
    if orphans:
        report.warn("listings for languages the extension does not ship: " + ", ".join(orphans))
    if not orphans and len(listings) == len(from_locales):
        report.ok(f"{len(listings)} listings, one per shipped language")


def check_listings(report, version):
    report.start("Store listings")

    stale = []
    oversized = False
    for name in sorted(os.listdir(STORE)):
        if not name.startswith("listing-"):
            continue
        text = read(os.path.join(STORE, name))
        body = text.split("\n---\n", 1)[-1]
        if len(body) > MAX_DETAILED_DESCRIPTION:
            report.fail(f"{name}: {len(body)} characters of description; the Web Store allows {MAX_DETAILED_DESCRIPTION}")
            oversized = True
        if version and version not in text:
            stale.append(name)
    if stale:
        # Every listing carries a "New in X.Y.Z" line. A bumped manifest with an unbumped listing is
        # a store page describing the previous release.
        report.warn(f"no mention of {version} in: " + ", ".join(stale))
    elif not oversized:
        report.ok(f"every listing mentions {version} and fits the description limit")

    submission = os.path.join(STORE, "submission.md")
    if version and os.path.exists(submission) and version not in read(submission):
        report.warn(f"store/submission.md does not mention {version} — it names the ZIP and the tag to upload")


def check_assets(report):
    report.start("Store assets")

    ids = store_shot_ids()
    if ids is None:
        report.fail("could not read STORE_SHOT_IDS out of tools/screenshots/shots.mjs")
    else:
        intact = True
        for shot in ids:
            path = os.path.join(STORE, "screenshots", f"{shot}.png")
            if not os.path.exists(path):
                report.fail(f"store/screenshots/{shot}.png is missing — regenerate it (tools/screenshots)")
                intact = False
                continue
            size = png_size(path)
            if size != STORE_SHOT_SIZE:
                report.fail(f"store/screenshots/{shot}.png is {size[0]}x{size[1]}, not 1280x800")
                intact = False
        if intact:
            report.ok(f"{len(ids)} store screenshots, all 1280x800")

        listed = {f[:-4] for f in os.listdir(os.path.join(STORE, "screenshots")) if f.endswith(".png")}
        extra = sorted(listed - set(ids))
        if extra:
            report.warn("screenshots in store/ that the listing does not use: " + ", ".join(extra))

    icon = os.path.join(STORE, "store-icon-128.png")
    if not os.path.exists(icon):
        report.fail("store/store-icon-128.png is missing — node tools/screenshots/store-icon.mjs")
    elif png_size(icon) != STORE_ICON_SIZE:
        size = png_size(icon)
        report.fail(f"store/store-icon-128.png is {size[0]}x{size[1]}, not 128x128")
    else:
        report.ok("store icon is 128x128")


def main():
    parser = argparse.ArgumentParser(description="Consistency checks for a stramus release.")
    parser.add_argument("--tag", help="a version tag (v1.5.0) the manifest must agree with; implies --release")
    parser.add_argument(
        "--release",
        action="store_true",
        help="treat warnings as failures — what a tag build wants, and what a working branch does not",
    )
    args = parser.parse_args()

    report = Report(release=args.release or bool(args.tag))
    manifest = read_json(MANIFEST)

    version = check_versions(report, manifest, args.tag)
    check_manifest(report, manifest)
    messages = check_locales(report, manifest)
    check_languages(report, messages)
    check_listings(report, version)
    check_assets(report)

    print()
    if report.failures:
        print(f"{len(report.failures)} problem(s):")
        for failure in report.failures:
            print(f"  - {failure}")
        return 1
    if report.warnings:
        print(f"Nothing broken; {len(report.warnings)} thing(s) worth a look before a tag:")
        for warning in report.warnings:
            print(f"  - {warning}")
        return 0
    print("All checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
