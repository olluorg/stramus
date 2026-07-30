# Taking the store screenshots

Five 1280×800 PNGs, shot against [`screenshot-data.csv`](screenshot-data.csv) in a throwaway Chrome
profile. The Web Store does not scale images: 1280×800 exactly, and no browser chrome in frame.

Everything here has to be done by hand — the automation available to Claude cannot open
`chrome://` or `chrome-extension://` URLs, so the new tab page can be neither driven nor captured
from outside the browser.

## 1. A profile with nothing in it

A separate profile keeps your own collections out of the shots, and keeps the demo import out of your
collections. `--user-data-dir` makes one that is a directory rather than an entry in your profile list:

```powershell
& "C:\Program Files\Google\Chrome\Application\chrome.exe" `
  --user-data-dir="$env:TEMP\stramus-shots" --no-first-run --no-default-browser-check
```

Delete `%TEMP%\stramus-shots` afterwards and the profile is gone. Do not sign into Google in it, and do
not sign into stramus: an account would put the demo data on the server and then on your other
browsers, and the account button would put your email address in the shot.

## 2. The extension

`chrome://extensions` → **Developer mode** on → **Load unpacked** →

```
C:\projects\stramus\extension\build\dist\js\productionExecutable
```

This is the build the release ZIP is made from — version 1.2.0, without the localhost host permission.
Sign-in will not work in this profile (the OAuth client is registered for a different extension ID),
which does not matter here: the shots are of a signed-out app.

## 3. The data

Open a new tab (the extension is now what a new tab is), then **⚙ Settings** → *Import* →
**⤓ Choose a file** → `C:\projects\stramus\store\screenshot-data.csv`. It should report 66 links added,
laid out as three sections in the sidebar — Work, Reading, Home.

**Then give the profile some history**, or the search box in shot 3 will have none to find: visit a
handful of the sites from the demo set — `kotlinlang.org`, `github.com`, `figma.com`,
`developer.mozilla.org`, `news.ycombinator.com` — and open two or three of them from a card in
stramus, which is what teaches it what to rank first.

Leave about eight tabs open in the window, of the same flavour as the collections. They are what the
tab pane shows in shots 1 and 2, and an empty pane makes the product look empty.

Theme: **☀ Light** for shots 1–3 (it reads better as a thumbnail in the store's grid), and it is worth
making shot 5 **☾ Dark** so the listing shows both.

## 4. Capturing at exactly 1280×800

Resizing the window by hand gives the wrong number — the frame and the tab strip are part of it.
DevTools captures the viewport alone, at the size you set:

1. `F12` → `Ctrl+Shift+M` (device toolbar).
2. In the size boxes at the top: **1280 × 800**. Set the zoom next to them to **100%**.
3. The **⋮** menu of the device toolbar → **Add device pixel ratio** → set **DPR = 1**. Without this a
   HiDPI screen writes a 2560×1600 file, which the store rejects.
4. `Ctrl+Shift+P` → type `screenshot` → **Capture screenshot**. The PNG lands in Downloads, 1280×800,
   with no browser chrome in it.

Keep DevTools docked to the side rather than undocked, and do not close the device toolbar between
shots — the layout the app chooses depends on the viewport width, and reopening it can change it.

Put the files in `store/screenshots/` as `01-collection.png` … `05-settings.png`.

## 5. The five shots

| # | File | What is in frame |
| --- | --- | --- |
| 1 | `01-collection.png` | **Work → Frontend** open: the sections sidebar on the left, the card grid with its Docs / Tools / Design headings in the middle, the tab pane on the right. This one is the listing's thumbnail and does most of the persuading — it should look populated and nothing should be mid-interaction. |
| 2 | `02-tabs.png` | The tab pane, mid-save: the window's tabs listed, one of them dragged over a collection (hold it there), or the window's ⤓ hovered so the "save these tabs" affordance is visible. |
| 3 | `03-search.png` | The search box with `kotlin` typed into it, showing all three kinds of hit at once — cards from the Backend collection, an open tab, a page from history. If history rows do not appear, the profile has not visited enough of the demo sites yet. |
| 4 | `04-assistant.png` | The assistant answering over the collection, with its "On-device" badge. **Only if this Chrome has the on-device model** — it needs Chrome 138+ with the model downloaded. If the window does not appear, skip this shot; four screenshots is a complete listing. |
| 5 | `05-settings.png` | Settings open on ☾ Dark: theme, language, the assistant, ⤒ Export CSV — the row of things the listing claims. |

## 6. Afterwards

Tell Claude the files are in `store/screenshots/`; they are the last asset the submission is waiting
on. Then delete `%TEMP%\stramus-shots`, and remove the unpacked extension from that profile — or just
delete the profile, which does both.
