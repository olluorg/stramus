# Screenshot automation

Drives the built extension with [Playwright](https://playwright.dev) to reproduce the UI states in
[`../../store/screenshots.md`](../../store/screenshots.md) and save them as PNGs — the five required
for the Chrome Web Store listing, plus a handful of bonus feature shots for onboarding/FAQ material.
Runs the real, built extension in a real (throwaway) Chrome profile via `--load-extension`; nothing
here talks to a mock or a stub.

## Setup

```sh
cd tools/screenshots
npm install
npx playwright install chromium
```

Then build the extension it will screenshot (from the repo root):

```sh
./gradlew :extension:jsBrowserDistribution
```

## Use

```sh
node capture.mjs                      # the 5 store shots (the default)
node capture.mjs --all                # every shot, including the bonus ones
node capture.mjs --shot 03-search     # one shot by id — see shots.mjs for the list
node capture.mjs --shot 01-collection --shot 05-settings
node capture.mjs --video              # also record a .webm per shot, in output/video/
node capture.mjs --headless           # for CI — see below
node capture.mjs --out /tmp/shots     # somewhere other than ./output
```

PNGs land in `output/`, at exactly 1280×800 (the Web Store does not scale them — Playwright's own
`viewport`/`deviceScaleFactor: 1` produces that size directly, no DevTools capture step needed).

Promoting a run's output into the actual listing is a manual step, on purpose — these are marketing
assets, and `store/screenshots/*.png` shouldn't change without someone looking at the diff first:

```sh
cp output/0{1,2,3,4,5}-*.png ../../store/screenshots/
```

## Adding a shot

1. Add `scenarios/NN-name.mjs`, exporting `description` and an async `run({ page, context, newTabUrl })`
   that arranges the UI into the state you want captured. `lib/dom.mjs` has the interaction helpers
   (settings, search, drag-and-drop, section/collection navigation); `lib/seed.mjs` seeds demo data,
   browsing history and open tabs.
2. Add a line for it in `shots.mjs`.
3. If the shot legitimately can't always be taken (needs an on-device model, a feature flag, network
   access to a specific site…), `throw new SkipShot('why')` from `lib/dom.mjs` instead of failing —
   the runner reports it as skipped, not failed.

Surfaces mapped but not yet scripted (rename/lock flow variants, the account/sign-in dialog, tab
triage, the file viewer, per-language listing shots): same pattern, just not written yet.

## The promo video's clips

[`capture-promo-clips.mjs`](capture-promo-clips.mjs) is a separate one-off script (not in
`shots.mjs`) that records real interaction footage — a live search, a tab held over a drop
target, a light→dark theme switch — for `../promo-video`. Same `launchExtension`/`lib/dom.mjs`
helpers, just timed for how the clips get trimmed and cut in Remotion rather than for one
representative frame. See `../promo-video/README.md` for what happens to the output.

## Notes on how this works

- **Extension loading**: `--load-extension`/`--disable-extensions-except` load the unpacked build
  without needing chrome://extensions' Developer Mode toggle — that's only required when loading
  through the UI by hand. A handful of other flags (`--no-first-run`,
  `--disable-search-engine-choice-screen`, …) exist purely to keep stock-Chrome onboarding prompts
  from sitting on top of the app and breaking selectors; none of them relax an *extension* permission.
- **`chrome-extension://` navigation**: real automation tooling (Playwright, driving Chrome over
  CDP) navigates these URLs fine — that's how every shot gets from a blank profile to the app. The
  "cannot open chrome:// or chrome-extension:// URLs" limitation `screenshots.md` used to describe was
  specific to the interactive browser tool available to Claude in conversation, not to scripted
  automation.
- **Drag-and-drop**: the tab pane / sidebar / card grid use native HTML5 DnD (`draggable` +
  `dragstart`/`dragover`/`drop`), not pointer events — Playwright's built-in `locator.dragTo()`
  synthesizes mouse movement, which these handlers don't listen for. `lib/dom.mjs`'s `dragHold`/
  `dragDrop` dispatch real `DragEvent`/`DataTransfer` objects from inside the page instead.
- **Resolving the extension's ID**: the manifest has no `background.service_worker` — the new-tab
  override is a plain page, not a script Chrome runs in the background — so there is no
  `serviceworker` context event to wait for (waiting for one, as an earlier version of
  `lib/launch.mjs` did, just times out). `resolveExtensionId` instead navigates to
  `chrome://newtab/`, which Chrome itself redirects to the overriding extension's page, and reads
  the (per-launch random) ID back off the resolved URL.
- **Headless / CI**: Chromium's headless mode has historically been unreliable with loaded
  extensions depending on version; the CI workflow runs headed under `xvfb` instead, which is the
  well-trodden path. `--headless` here is for local convenience only — try it, and fall back to
  dropping `--headless` (or wrapping the command in `xvfb-run` yourself) if a shot silently comes back
  blank.
- **The AI assistant shot** requires Chrome 138+ with the on-device model already downloaded (not
  just downloadable — see `checkLocalAiAvailable` in `lib/dom.mjs`), same requirement
  `screenshots.md` documents for the manual procedure. It's the one shot most likely to skip in a
  fresh CI runner.
