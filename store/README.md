# Chrome Web Store submission

Everything the Web Store form asks for, written down once so that a submission is copy-paste rather
than composition. The listing itself lives in `listing-{lang}.md`, one per language the extension
supports — [`listing-en.md`](listing-en.md), [`listing-ru.md`](listing-ru.md),
[`listing-de.md`](listing-de.md), [`listing-fr.md`](listing-fr.md), [`listing-es.md`](listing-es.md),
[`listing-pt-BR.md`](listing-pt-BR.md), [`listing-ja.md`](listing-ja.md), [`listing-ko.md`](listing-ko.md),
[`listing-zh-CN.md`](listing-zh-CN.md), [`listing-it.md`](listing-it.md) and
[`listing-tr.md`](listing-tr.md); this file is the checklist and the answers to the review questions.

The name, the short description and the toolbar tooltip are **not** here: they come from the extension
itself (`extension/src/jsMain/resources/_locales/{en,ru,de,fr,es,pt_BR,ja,ko,zh_CN,it,tr}/messages.json`),
and the Web Store reads them out of the uploaded ZIP. Editing them there changes both Chrome's UI and the
listing at once, which is why the store's own "name" and "short description" fields will be pre-filled
and greyed out for a language that ships in the ZIP.

## Before uploading

- [ ] A developer account, with the one-off $5 registration fee paid (`https://chrome.google.com/webstore/devconsole`).
- [ ] `version` in `extension/src/jsMain/resources/manifest.json` bumped — the store rejects a re-upload
      of a version it already has.
- [ ] A tag pushed: `git tag v1.0.0 && git push origin v1.0.0`. The `release` workflow builds the
      extension and attaches `stramus-extension-1.0.0.zip` to a GitHub Release; that ZIP is what gets
      uploaded. (It also refuses to build if the tag and the manifest disagree about the version.)
- [ ] Screenshots taken (see below) — the one asset that cannot be generated from the repository.
- [ ] Everything keyed on the extension's ID repointed at the *published* ID — see below. Publishing
      assigns the ID of the store item, which is not the ID an unpacked build gets.

### The extension ID, and the three things that depend on it

An unpacked build's ID comes from the folder it was loaded from; a published one's is the store item's,
fixed for good at the first upload. Sign-in and sync are both keyed on it, so all three of these have to
name the published ID or they silently break for everyone but the developer:

| What | Where | Symptom if it still names the old ID |
| --- | --- | --- |
| `STRAMUS_ALLOWED_ORIGINS` on the server | the VPS's `compose.yaml` | **Sync fails outright** — CORS refuses `chrome-extension://<published id>` |
| Application ID of the "Chrome Extension" OAuth client | Google Cloud Console | `chrome.identity.getAuthToken` fails; sign-in falls back to a window instead of being silent |
| Authorized redirect URI `https://<published id>.chromiumapp.org/` on the Web application client | Google Cloud Console | the fallback flow fails too, so Google sign-in is gone entirely |

The alternative to repointing them at each new ID is to pin the store item's public key as `key` in the
manifest, which gives an unpacked build the same ID as the published one. See
[`docs/sync-and-auth.md`](../docs/sync-and-auth.md) for which client is which.

The **OAuth consent screen must be "In production"**, not "Testing": while it is in testing only the
accounts explicitly listed as test users can sign in, which for a published extension means almost
nobody. The scopes asked for (`openid`, `email`) are non-sensitive, so this needs no review from Google.

### The manifest ships without `localhost`

`http://localhost:8090/*` is deliberately *not* in `host_permissions`: a published extension has no
business reaching a local server, and the store build talks to `https://api.stramus.space` (baked in
from `STRAMUS_SERVER_URL`, a repository variable the release workflow passes to the build). Developing
against a local server means adding the line back in your own working copy — and not committing it.

## Listing

| Field | Value |
| --- | --- |
| Category | Productivity → Workflow & Planning |
| Language | English (default), Russian, German, French, Spanish, Portuguese (Brazil), Japanese, Korean, Chinese (Simplified), Italian, Turkish |
| Detailed description | one `listing-{lang}.md` per language above, listed at the top of this file |
| Homepage URL | `https://stramus.space/` |
| Support URL | `https://github.com/olluorg/stramus/issues` |
| Privacy policy URL | `https://stramus.space/privacy.html` |

### Graphic assets

| Asset | Size | Where it comes from |
| --- | --- | --- |
| Store icon | 128×128 PNG | [`store-icon-128.png`](store-icon-128.png) ✔ — made from `logo.png`, not the same file as the extension's own `logo-128.png` (see below) |
| Screenshots (1–5, at least 1) | 1280×800 PNG | **to be taken** — see below |
| Small promo tile (optional) | 440×280 PNG | to be made, if the listing is to be eligible for featuring |
| Marquee (optional) | 1400×560 PNG | only needed for the store's front page |

The store icon is its own file rather than the extension's `logo-128.png`, which is drawn to fill its
canvas: its star sits 2px from the left edge and 18px from the right. That is right for a toolbar, where
padding only makes an icon smaller, and wrong for the store, which asks for the graphic to sit inside
roughly 96×96 with room around it and draws the result on a card next to other people's icons.
`store-icon-128.png` is the 600px `logo.png` scaled into that 96×96 and positioned so its alpha-weighted
centre lands in the middle of the canvas — centring the glow by its bounding box leaves it visibly
lopsided, because the glow is not symmetrical.

[`screenshots.md`](screenshots.md) is the procedure — a throwaway profile, the unpacked build, and
DevTools' own capture, which is the only way to get exactly 1280×800 with no browser chrome in frame.
[`screenshot-data.csv`](screenshot-data.csv) is the contents to shoot against: 66 links in three
sections and seven collections, most of them grouped under headings, so the grid looks like something
somebody uses rather than a blank slate.

Screenshots worth having, in this order — the first one is the listing's thumbnail and does most of the
persuading:

1. A full new tab page: sections in the sidebar, a collection of cards open, the whole thing populated.
2. The tab pane on the right, mid-save: open tabs about to become a collection.
3. The search box open, with results from cards, tabs and history at once.
4. The assistant answering in its window over the collection — about the links in the collection that
   is open, which is what it is given. (The window is badged "AI" whichever assistant is chosen; that
   it is the on-device one is visible in Settings → AI, not here.)
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
  The store only holds icons for pages this browser has *visited*, so for anything else — an imported
  link, a collection restored on a new machine — the icon is fetched by our own server on the user's
  behalf (`GET /v1/favicon?host=…`, anonymous, no user recorded), and only if that server cannot be
  reached at all does the extension fall back to asking `favicone.com` or `google.com/s2/favicons`
  directly. A site with no icon anywhere is drawn as a coloured letter tile, not as a blank square.
- **identity** — signing in with Google, and nothing else. `chrome.identity.getAuthToken` asks for a
  token for the account already signed into Chrome (scopes `openid` and `email`, so an email address
  and nothing more), and `chrome.identity.launchWebAuthFlow` is the fallback where that is unavailable.
  Both happen only when the user presses "Sign in with Google"; an account is optional and the extension
  is fully usable without one.

**Host permissions:** `https://api.stramus.space/*` — our own server, and the only host the extension
talks to. It is asked for two things. Synchronisation, which happens only while the user is signed in:
an extension with no account never syncs anything. And `GET /v1/favicon?host=…`, the icon proxy
described under **favicon** above, which is anonymous, carries no account and is asked only for a host
the browser's own favicon store has nothing for — so a signed-out extension does reach this host, and
what it says when it does is the name of a site, with nothing attached to identify whose it is.
Nothing else is requested: there is no `<all_urls>`, no content script and no injection into any page
the user visits.

**Remote code:** No. The extension executes no code it did not ship with; everything in the ZIP is
compiled from this repository. The `wasm-unsafe-eval` in the CSP is for the SQLite WebAssembly module
that ships *inside* the extension, not for anything fetched at runtime.

**Data usage.** An account is optional, and without one nothing about the user is collected: the only
thing that leaves the machine is the icon proxy's anonymous question about a host, which is attached to
no account and to no identifier. Signing in is what turns synchronisation on. So the categories to
tick, each with the note the form allows:

| Category | Why | Only when |
| --- | --- | --- |
| Personally identifiable information | the email address the account is | signed in |
| Authentication information | the sign-in session token, and the salted hash of a section PIN, which travels with the section row it belongs to | signed in |
| Website content | the user's own saved cards: links, note text, files, and their titles | signed in |
| Web history | the counters that rank the search box are the pages the user opened *from stramus*, with their titles — kept on the machine and **off** by default, synced only if "Sync browsing statistics" is switched on | signed in **and** switched on |

Not collected, and not to be ticked: health, financial and payment information, personal
communications, location, user activity (no keystroke, click or mouse tracking of any kind). The
browser's own history, which the `history` permission reads, is never among what is uploaded — it is
read as the user searches and no copy is kept.

All of it is collected for the extension's own functionality (synchronising the user's collections
between their browsers) and nothing else. Certify all three: the data is not sold to third parties, not
used for purposes unrelated to the single purpose, and not used to determine creditworthiness or for
lending — all three hold.

Two more things a reviewer may reasonably ask about, both of which are user-initiated navigations
rather than data collection, and both of which are described in the privacy policy:

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
