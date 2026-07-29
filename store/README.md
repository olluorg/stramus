# Chrome Web Store submission

Everything the Web Store form asks for, written down once so that a submission is copy-paste rather
than composition. The listing itself lives in [`listing-en.md`](listing-en.md) and
[`listing-ru.md`](listing-ru.md); this file is the checklist and the answers to the review questions.

The name, the short description and the toolbar tooltip are **not** here: they come from the extension
itself (`extension/src/jsMain/resources/_locales/{en,ru}/messages.json`), and the Web Store reads them
out of the uploaded ZIP. Editing them there changes both Chrome's UI and the listing at once, which is
why the store's own "name" and "short description" fields will be pre-filled and greyed out.

## Before uploading

- [ ] A developer account, with the one-off $5 registration fee paid (`https://chrome.google.com/webstore/devconsole`).
- [ ] `version` in `extension/src/jsMain/resources/manifest.json` bumped — the store rejects a re-upload
      of a version it already has.
- [ ] A tag pushed: `git tag v1.0.0 && git push origin v1.0.0`. The `release` workflow builds the
      extension and attaches `stramus-extension-1.0.0.zip` to a GitHub Release; that ZIP is what gets
      uploaded. (It also refuses to build if the tag and the manifest disagree about the version.)
- [ ] Screenshots taken (see below) — the one asset that cannot be generated from the repository.

## Listing

| Field | Value |
| --- | --- |
| Category | Productivity → Workflow & Planning |
| Language | English (default), Russian |
| Detailed description | [`listing-en.md`](listing-en.md), [`listing-ru.md`](listing-ru.md) |
| Homepage URL | `https://stramus.space/` |
| Support URL | `https://github.com/olluorg/stramus/issues` |
| Privacy policy URL | `https://stramus.space/privacy.html` |

### Graphic assets

| Asset | Size | Where it comes from |
| --- | --- | --- |
| Store icon | 128×128 PNG | `extension/src/jsMain/resources/logo-128.png` ✔ |
| Screenshots (1–5, at least 1) | 1280×800 PNG | **to be taken** — see below |
| Small promo tile (optional) | 440×280 PNG | to be made, if the listing is to be eligible for featuring |
| Marquee (optional) | 1400×560 PNG | only needed for the store's front page |

Screenshots worth having, in this order — the first one is the listing's thumbnail and does most of the
persuading:

1. A full new tab page: sections in the sidebar, a collection of cards open, the whole thing populated.
2. The tab pane on the right, mid-save: open tabs about to become a collection.
3. The search box open, with results from cards, tabs and history at once.
4. The assistant answering in its window over the collection (the on-device model, with its badge).
5. Settings: theme, language, the assistant, export.

Chrome does not scale them for you: 1280×800 exactly, and the browser chrome should not be in frame.

## Privacy practices tab

**Single purpose.** stramus replaces the new tab page with a place to save, organise and re-open the
pages you use: your open tabs become collections of cards, and a search box finds them again — along
with your tabs and your browsing history — from the same keystroke.

**Permission justifications** (one per permission the manifest asks for):

- **tabs** — stramus shows the user their open tabs, so they can save a tab into a collection, drag it
  to another position, sort a window's tabs, and close a tab once it is saved. All of it is the visible
  work of the tab pane; none of it happens without the user asking.
- **history** — the history pane lists recently visited pages so that one can be saved as a card, and
  the search box searches visited pages alongside saved ones. The history is read from the browser when
  the user searches it; no copy of it is kept and nothing is transmitted.
- **search** — a query typed into the search box that is meant for the web is handed to
  `chrome.search`, which puts it to whichever search engine the *user* has chosen as their default,
  exactly as the address bar would. The alternative is hardcoding a search engine on their behalf.
- **favicon** — site icons for saved links and open tabs are read from the browser's own favicon store
  (`_favicon/`). This is a privacy measure, not a convenience one: the alternative is asking a public
  icon service on the internet for each host, which would tell that service which sites the user keeps.

**Host permissions:** none requested.

**Remote code:** No. The extension executes no code it did not ship with; everything in the ZIP is
compiled from this repository. The `wasm-unsafe-eval` in the CSP is for the SQLite WebAssembly module
that ships *inside* the extension, not for anything fetched at runtime.

**Data usage.** Declare that stramus does **not** collect or transmit user data, and certify all three:
the data is not sold to third parties, not used for purposes unrelated to the single purpose, and not
used to determine creditworthiness or for lending.

Two things a reviewer may reasonably ask about, both of which are user-initiated navigations rather
than data collection, and both of which are described in the privacy policy:

- Choosing ChatGPT, Gemini or Claude as the assistant means a question typed by the user *opens a chat
  with that service in a new tab*, with the question in it. The question travels — because the user
  asked it there — and nothing else does. The default, where the browser has an on-device model, is
  that model, which answers locally and sends nothing.
- A web search from the search box goes to the user's own default search engine.

## After the review

The first review of a new extension usually takes a few days. Once it is published:

- [ ] Put the store link in `README.md` and in the web app's header.
- [ ] Subsequent releases are the same loop: bump the manifest version, tag, upload the ZIP from the
      GitHub Release, submit for review.
