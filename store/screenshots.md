# Taking the store screenshots

Five 1280×800 PNGs, shot against [`screenshot-data.csv`](screenshot-data.csv). The Web Store does
not scale images: 1280×800 exactly, and no browser chrome in frame.

This is scripted — see [`tools/screenshots`](../tools/screenshots), which drives the built
extension with Playwright in a throwaway Chrome profile and produces exactly these five PNGs (plus
some bonus onboarding/FAQ shots beyond the store listing). It also runs in CI
([`.github/workflows/screenshots.yml`](../.github/workflows/screenshots.yml)) on every tag push,
as a downloadable artifact for review.

## Usual path

```sh
./gradlew :extension:jsBrowserDistribution     # the build the shots are taken against
cd tools/screenshots
npm install && npx playwright install chromium
node capture.mjs                                # the 5 shots below, into tools/screenshots/output/
```

Then, after looking at the output:

```sh
cp tools/screenshots/output/0{1,2,3,4,5}-*.png store/screenshots/
```

Copying in is a manual step on purpose — these are marketing assets, and this directory shouldn't
change without someone looking at the diff first. See
[`tools/screenshots/README.md`](../tools/screenshots/README.md) for what each shot does, how to add
one, and the couple of things (native drag-and-drop, extension loading flags, the on-device AI
model's availability) that made this fiddlier than a plain page screenshot.

## The five shots

| # | File | What is in frame |
| --- | --- | --- |
| 1 | `01-collection.png` | **Work → Frontend** open: the sections sidebar on the left, the card grid with its Docs / Tools / Design headings in the middle, the tab pane on the right. This one is the listing's thumbnail and does most of the persuading — it should look populated and nothing should be mid-interaction. |
| 2 | `02-tabs.png` | The tab pane, mid-save: a tab dragged over a collection and held there. |
| 3 | `03-search.png` | The search box with `kotlin` typed into it, showing all three kinds of hit at once — a card from the Backend collection, an open tab, and a history entry. |
| 4 | `04-assistant.png` | The assistant answering over the open collection — *"Which of these links would help me compress an image?"* asked of **Work → Frontend**, which its cards answer (Squoosh). Needs Chrome 138+ with the on-device model already downloaded; the script skips this shot rather than fail if that isn't the case, same as the manual fallback below — four screenshots is a complete listing. |
| 5 | `05-settings.png` | Settings → Appearance on ☾ Dark: theme and language. |

## Manual fallback

If the script can't run somewhere (no Chrome, no Node, a Chrome feature the script doesn't yet
drive), the old-fashioned way still works — a throwaway profile, the unpacked build, and DevTools'
own capture:

1. **A profile with nothing in it.** A separate profile keeps your own collections out of the
   shots, and keeps the demo import out of your collections:
   ```powershell
   & "C:\Program Files\Google\Chrome\Application\chrome.exe" `
     --user-data-dir="$env:TEMP\stramus-shots" --no-first-run --no-default-browser-check
   ```
   Delete `%TEMP%\stramus-shots` afterwards and the profile is gone. Do not sign into Google or
   into stramus in it.
2. **The extension.** `chrome://extensions` → **Developer mode** on → **Load unpacked** →
   `extension/build/dist/js/productionExecutable`.
3. **The data.** Open a new tab, then **⚙ Settings** → *Import* → **⤓ Choose a file** →
   `store/screenshot-data.csv`. Visit a handful of the demo sites (`kotlinlang.org`, `github.com`,
   `figma.com`, `developer.mozilla.org`, `news.ycombinator.com`) so shot 3 has history to find, and
   leave about eight tabs open so shots 1–2's tab pane isn't empty.
4. **Capturing at exactly 1280×800.** `F12` → `Ctrl+Shift+M` → size **1280 × 800**, zoom **100%** →
   device toolbar's **⋮** → **Add device pixel ratio** → **DPR = 1** → `Ctrl+Shift+P` →
   **Capture screenshot**.
5. Put the files in `store/screenshots/` as `01-collection.png` … `05-settings.png`, per the table
   above.
