# tools

Everything here is run by hand or by a workflow, and never by Gradle. They are the parts of working on
stramus that are procedure rather than code: cutting a release, keeping the version and the eleven
locales in step, generating the icon libraries, taking the store screenshots, rendering the promo
video, running the sync server locally.

| | What it does | When |
| --- | --- | --- |
| [`preflight.py`](preflight.py) | Checks the version, the locales, the listings and the store assets against each other | Before a tag; also on every CI run |
| [`package-extension.sh`](package-extension.sh) | Builds and packs the Web Store ZIP | Locally, to load or inspect a release build; the release workflow calls the same script |
| [`release.sh`](release.sh) | Bump → check → test → pack → tag → push | Cutting a release |
| [`screenshots/`](screenshots/README.md) | Drives the built extension with Playwright for the listing screenshots and the store icon | When the UI moves |
| [`promo-video/`](promo-video/README.md) | Renders the promo video (Remotion) | When the pitch changes |
| [`icon-data/generate.py`](icon-data/generate.py) | Writes `IconLibrary.kt` and `EmojiLibrary.kt` from Lucide and Twemoji | When the icon set changes |
| [`dev-server.sh.example`](dev-server.sh.example) | Template for the local sync server run | Once per checkout — copy it, fill in the key |

Only Python 3 and Bash, both stdlib-only, for the first three: they run before the JDK is installed in
CI and on a machine that has just cloned this.

## Cutting a release

```sh
tools/release.sh 1.5.0        # bumps manifest.json + About.kt, checks, tests, packs the ZIP
# read the diff, write the "what's new" lines in store/listing-*.md, commit
tools/release.sh --tag --push # tags v1.5.0 and pushes it
```

The push is the point of no return: the release workflow builds the ZIP, attaches it to a GitHub
Release, and uploads it to the Web Store as a *draft* — the listing text is still pasted and the item
still submitted by a person, in the developer console. [`../store/README.md`](../store/README.md) is
the long form of what that person does.

`release.sh` stops rather than guesses at everything a script has no business deciding: whether the
screenshots still show the current UI, whether the privacy policy still describes what the build does,
whether the permission list grew a warning that will disable the extension for every existing user
until they accept it.

## What preflight checks

The things nothing else checks, and that only announce themselves after a release has gone out:

- `version` in `manifest.json` and `APP_VERSION` in `About.kt` agree — and, with `--tag`, that the tag
  agrees with both. Their own comments say they are kept in step by hand; this is the hand.
- No `localhost` in the committed manifest — that belongs in a working copy.
- The eleven `_locales` carry the same keys, none of them empty, and every `__MSG_…__` the manifest
  names resolves in all of them. A missing key is not an error in Chrome: it quietly falls back to
  English, and the German user simply reads English.
- Names and short descriptions inside the store's limits (132 characters is the one that bites — the
  store refuses the upload rather than truncating).
- The `Lang` enum, `_locales` and `store/listing-*.md` describe the same set of languages.
- Every listing mentions the version being released, so a bumped manifest cannot ship a store page
  describing the previous release.
- The four store screenshots exist and are still 1280×800, and the store icon is still 128×128.

Warnings (a listing not yet rewritten) are warnings on a working branch and failures under `--tag` or
`--release`, which is the difference between "not finished yet" and "about to be published".

```sh
python3 tools/preflight.py              # anywhere, any time — a second, no build
python3 tools/preflight.py --tag v1.5.0
```
