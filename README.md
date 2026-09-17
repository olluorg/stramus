# stramus

A tab manager in the spirit of Toby: save open tabs into collections, grouped into sections in a
sidebar. Runs as a standalone web app and as a Chrome extension that replaces the new-tab page.

The extension is in the Chrome Web Store:
<https://chromewebstore.google.com/detail/accjfifjflckbinniekamhehcjdampjh>. The web version is at
<https://stramus.space>, with nothing to install and no account to make.

Written in Kotlin/JS (React via kotlin-wrappers). Data lives in the browser's own IndexedDB, through
[Kormium](https://github.com/olluorg/korm)'s `kidx` — no SQLite and no WebAssembly on the client side;
the server is the only place a real SQLite file exists.
Everything is stored locally in the browser: the server and an account are an optional add-on that
gives you the same collections on a second device, and nothing more.

## What you need to build it

- **JDK 21** (Temurin 21 in CI).
- Nothing else: Gradle comes via the wrapper (`./gradlew`), and the Kotlin/JS plugin downloads its
  own Node and Yarn on the first build.

## Features

- **Sections → collections → cards.** Cards can be grouped further under headings (card sections)
  inside a collection. Everything is drag-and-drop, with undo.
- **Cards are not just links.** A card can be a link, a Markdown note, or a file (image, PDF,
  anything dropped on it) kept alongside the links it belongs with.
- **Save open tabs.** The tab pane lists every tab in every window; drag one into a collection, or
  save a whole window at once. Saving a tab can close it on the spot.
- **Search in one keystroke.** The search box searches cards, open tabs and browsing history at
  once, ranked by what you actually open. Text that isn't a match goes to your default search
  engine.
- **AI tab triage.** Sorts a window of open tabs into collections and sections for you, using
  Chrome's on-device model (the Prompt API) when available — nothing sent anywhere — or a chosen web
  assistant (ChatGPT, Gemini, Claude) opened with the question instead. See
  `core/src/commonMain/kotlin/stramus/core/ai/TabTriage.kt` for the batching and self-consistency
  checks that decide which of the model's answers are trusted.
  Not to be confused with the AI *title* suggestion for a single card, which existed briefly and was
  removed.
- **Topics, for a browser that is already full.** A fresh install opens on an invitation instead of an
  empty page: your open tabs, read into collections by the words their titles share and by what the
  addresses say you searched for. It crosses sites, which is the whole point — a repair spread over a
  shop, a search, a forum and a chatbot comes out as one collection named in your own words. Whatever
  no topic wanted is gathered by site, from four tabs up; search engines and chatbots never become a
  collection. The sidebar groups come from a short table of what a site is *for* (shopping, code,
  video, jobs), and Chrome's on-device model, where there is one, is asked for better ones over them —
  from inside the open window, so nothing is ever waited for on a blank screen. A topic that reads
  like a collection you already keep joins it, under the divider you made for it. No model is needed
  for any of this; without one you lose the abstraction and nothing else.
  See `core/src/commonMain/kotlin/stramus/core/topics/TitleTopics.kt`, whose opening comment also
  records what was tried first: the browser's own signals — which tab opened which, tab groups, the
  referrer chain — measured empty on a real window of 158 tabs.
- **Folder view.** Sections can be displayed as a flat card grid or as nested folders, per collection.
- **PIN-locked sections.** A section can be locked with a PIN so its contents aren't on screen when
  you share it — a shoulder-surfing guard, not encryption: the server sees the content of a synced
  PIN section as plainly as everything else.
- **Import / export.** Netscape bookmarks HTML (folders map to section → collection → card section)
  and CSV, both ways. Import also reads a OneTab text export (each saved group becomes a collection)
  and a Toby JSON export (each list becomes a collection, its first label the section).
- **Light/dark themes, English/Russian UI**, a favicon cache so links keep their icons offline, and
  collapsed-sidebar thumbnails for sections and collections.
- **No analytics, no telemetry, no tracking, no advertising**, with or without an account.

## Web app

Dev server with hot reload:

```bash
./gradlew :webapp:jsBrowserDevelopmentRun --continuous
```

The app comes up on <http://localhost:8080> and rebuilds on every save. Without `--continuous` the
server still starts, but edits won't be picked up.

Production bundle:

```bash
./gradlew :webapp:jsBrowserDistribution
```

The output is static files in `webapp/build/dist/js/productionExecutable/`, servable by any static
file server. This is the folder that ships to GitHub Pages, at <https://stramus.space>.

### Landing page and sign-in

stramus.space serves the same bundle, but the first screen is a landing page: what it is, what it
does, and two doors into the app. The rule that picks the screen lives in
`webapp/src/jsMain/kotlin/stramus/web/Main.kt`:

- `#about` always shows the landing page — the only way back to it from inside the app;
- a session (a refresh token in `localStorage`) goes straight to the app;
- otherwise the landing page, but only until the first sign-in: either door sets
  `stramus.landingSeen`, and after that the app opens directly.

The second door, "Try without an account", is no worse than the first: the app has never required a
server and still doesn't (see [`docs/sync-and-auth.md`](docs/sync-and-auth.md)). Signing in on the
landing page only gets a session; attaching it to the local database is the app's job (`App.kt`),
which is also where the one question sign-in can't answer gets asked: a browser with existing
collections is offered a choice — join them to the account, or replace them with what's on the
account. A fresh install isn't asked anything, since there's nothing there but our own welcome note.

Landing page copy lives in `webapp/src/jsMain/kotlin/stramus/web/LandingStrings.kt` (en/ru, switcher
in the header); styles in `webapp/src/jsMain/resources/landing.css`. The palette isn't its own — same
CSS variables as the app, so the landing page follows the user's chosen theme.

### Domain

`webapp/src/jsMain/resources/CNAME` is what keeps the domain on GitHub Pages: the file is copied into
the bundle, and a deploy without it would drop the domain from the site. DNS at the registrar: `A`/
`AAAA` records at the apex pointing at GitHub Pages (`185.199.108–111.153`), and `CNAME www →
olluorg.github.io`.

The server needs to know the domain too, or the browser won't let any request from the landing page
through: `STRAMUS_ALLOWED_ORIGINS` must include `https://stramus.space` (see `compose.yaml`).

## Chrome extension

```bash
./gradlew :extension:jsBrowserDistribution
```

Then in the browser: `chrome://extensions` → enable **Developer mode** → **Load unpacked** → point it
at

```
extension/build/dist/js/productionExecutable/
```

It already contains `manifest.json` (MV3). Once installed, the extension takes over the new-tab
page — open a new tab and you'll see stramus.

After a rebuild, reload the extension with the ↻ button on its card in `chrome://extensions`. The dev
build (`:extension:jsBrowserDevelopmentExecutableDistribution`, in `developmentExecutable/`) also
loads as unpacked — webpack is configured to use `source-map` instead of `eval` there, because MV3's
CSP forbids `unsafe-eval`.

The extension asks for `tabs` (save open tabs), `history` (import from browser history), `search`
(send a query to the user's own default search engine), `favicon` (site icons from the browser's
local store), and `identity` (Google sign-in via `chrome.identity`). The web version has neither of
the first two — links are added by hand there instead.

Site icons come from a chain that stops at the first step that answers: the browser's own icon store
(extension only, and only for sites you've visited) → `GET /v1/favicon?host=…` on our server, which
fetches it itself → public icon services directly, but only if our server is unreachable → a
letter tile. The point of the middle step is that icon services shouldn't be told which hosts a
particular visitor has bookmarked just because they ask by host; our server asks on its own behalf and
caches the answer for everyone. Details and rationale in `Favicon.kt` (client) and `Favicons.kt`
(server).

## Sync server

The production server's address isn't in the source: it comes from the `STRAMUS_SERVER_URL` variable
(GitHub Actions → Variables) and gets baked into the bundle at build time. It's empty in a local
build, and the client falls back to `http://localhost:8090` — which is where the server comes up like
this:

```bash
./gradlew :server:run          # port 8090; database is a stramus.db file alongside it
./gradlew :webapp:jsBrowserDevelopmentRun --continuous   # port 8080
```

`:server:run` on its own comes up with no Google client and no cloud model, which is enough for sync
and not enough for signing in or for cloud triage. Those want environment variables, and the ones that
don't change from machine to machine are already written down:
[`tools/dev-server.sh.example`](tools/dev-server.sh.example) — copy it to `tools/dev-server.sh` (the
copy is gitignored, since that is where the API key ends up) and run that instead.

The ports are kept apart on purpose — the web app's dev server takes 8080, so the sync server lives on
8090, and CORS by default allows exactly `http://localhost:8080`.

The sync icon sits in the toolbar next to search; it's also what opens sign-in. **Sign-in is
Google-only today**: email sign-in (password and one-time code) is written but disabled — the server
answers those four routes with 501, and there's no form for them in either client.
`STRAMUS_EMAIL_AUTH=1` opens the door back up, and in a dev build the **code is printed to the server
log** (`Sign-in code for … : 123456`). Accounts created by email are still there — signing in with
Google using the same address lands in the same account, not a new one.

A different server address is set from the browser console — also the only way to point the extension
at one:

```js
localStorage.setItem("stramus.server", "https://example.org")
```

### Signing in with Google

You need an OAuth client in Google Cloud Console (type "Web application"). Allowed redirect URIs:

- web app — `https://stramus.space/oauth.html` (and `http://localhost:8080/oauth.html` for
  development);
- extension — `https://<extension-id>.chromiumapp.org/`.

The same client ID is known to **both the server** (`STRAMUS_GOOGLE_CLIENT_ID`) **and the client**. In
the client it's **baked in at build time** (`:ui-shared:generateBuildDefaults`, the
`STRAMUS_GOOGLE_CLIENT_ID` environment variable, defaulting to the published one): this is a public
application identifier, not a secret, exactly like the extension's client ID in `manifest.json`. It
used to live only in `localStorage`, and in any browser nobody had set up by hand the sign-in button
simply did nothing — which is what happened in Edge. It can still be overridden from the console (a
fork, your own client, a test build):

```js
localStorage.setItem("stramus.googleClientId", "…apps.googleusercontent.com")
```

If the client ID is empty, there's simply no "Continue with Google" button — a button that opens
Google and comes back with "invalid client" is worse than no button at all. The server checks the
token's signature, its issuer, and that the token was issued to **this** application (`aud`): without
that last check, anyone with their own Google application could sign in as anyone.

**Outside Chrome (Edge and others).** The first, silent step — `chrome.identity.getAuthToken` — asks
for the account of the **browser itself**, which in Edge is a Microsoft account: it answers with a
refusal, emptiness, or nothing at all. So it runs under a timeout (4s), after which a
`launchWebAuthFlow` window always follows. Every step logs a `[stramus:google] …` line to the
console — including the redirect URI that needs allowing in Google Cloud Console if the extension's
ID differs in that browser.

The extension needs `host_permissions` for the server's address — `manifest.json` currently lists
`api.stramus.space` (production); add `http://localhost:8090/*` in your own working copy for local
development, but don't commit it (see [`store/submission.md`](store/submission.md)).

Server environment variables:

| Variable | What it's for |
| --- | --- |
| `PORT`, `STRAMUS_DB`, `STRAMUS_BLOBS` | port, database file, folder for file bytes |
| `STRAMUS_JWT_SECRET` | signs access tokens |
| `STRAMUS_ALLOWED_ORIGINS` | CORS, comma-separated |
| `STRAMUS_EMAIL_AUTH=1` | turns on email sign-in (password and code); off by default — Google only |
| `STRAMUS_SMTP_HOST` / `_PORT` / `_USER` / `_PASSWORD` / `STRAMUS_MAIL_FROM` | mail for one-time codes; without a host in production this sign-in door is just closed (501), not left open to the log |
| `STRAMUS_GOOGLE_CLIENT_ID` | Google sign-in; without it this door is simply absent |
| `STRAMUS_ENV=production` | production mode |

In production mode the server **refuses to start** if a dev secret is still set, or if `localhost` is
still in CORS. SMTP isn't required and currently isn't needed at all: email sign-in is off,
`/v1/auth/register`, `/login` and `/code/*` answer 501 — Google sign-in is what works. If SMTP is set
anyway (together with `STRAMUS_EMAIL_AUTH=1`), the connection must be encrypted.

Mail is plain SMTP, not a provider-specific API: Postmark, SES, Fastmail, or your own relay plug in by
changing the host, no code changes needed.

Orphaned files (a card was deleted, its bytes weren't) are swept by a garbage collector once a day.
The client can't do this itself: the same bytes might still be held by a card on another device, and
only the server sees every card at once.

### Running on a VPS

CI builds a ready image and pushes it to GHCR: `ghcr.io/olluorg/stramus-server:latest`
(multi-arch — amd64 and arm64). The quick path is [`compose.yaml`](compose.yaml) at the repo root:

```bash
# edit environment (secret, origins; SMTP is optional), then:
docker compose up -d
```

All state — the database and file bytes — lives in one `/data` volume; that's what to back up. The
server serves `/health` for an orchestrator, runs as an unprivileged user, and in production mode
won't start until the secret and origins are set — it fails loudly rather than quietly handing out
accounts.

Build the image locally: `docker build -t stramus-server .`

What's implemented: accounts (Google sign-in; password and email-code sign-in written but disabled,
JWT with refresh rotation), sync of sections, collections, card sections, cards and files, tombstones,
conflicting copies for notes, account export and deletion.

Files are addressed by content hash: the same PDF in two cards is one file on the server, and moving
or renaming a card doesn't resend a single byte. Limits: 10 MB per file, 500 MB per account
(`STRAMUS_BLOBS` names the folder for the bytes — they aren't in the database, so backups stay small).

**Usage stats aren't synced by default.** Collections are what someone chose to keep; usage stats are
a trace of where they've been, and those are different in sensitivity. Turned on in settings ("Sync
browsing usage stats"); turning it on does a full re-fetch from the server, since rows were skipped by
the cursor while the option was off.

Still missing: favicons are deliberately not synced (a cache that rebuilds itself), "forget this page"
from history doesn't travel to the server, and `privacy.html` hasn't been rewritten to say plainly
that the server sees the content of synced cards.

Design rationale: [`docs/sync-and-auth.md`](docs/sync-and-auth.md).

## Publishing to the Chrome Web Store

The extension is published:
<https://chromewebstore.google.com/detail/accjfifjflckbinniekamhehcjdampjh>. The next release is a
bump of `version` in the manifest, a `vX.Y.Z` tag, and the ZIP the release workflow builds and
attaches to it, uploaded to the developer console; the order of it is written out in
[`store/README.md`](store/README.md).

The mechanical half of that is one command:

```bash
tools/release.sh 1.5.0        # bumps manifest.json and About.kt, checks, tests, packs the ZIP
tools/release.sh --tag --push # after the bump is committed: tags v1.5.0 and pushes it
```

`tools/preflight.py` is the checking half, and it runs on its own in CI too: the version in the two
files that carry it, the eleven `_locales` against each other and against the manifest's
`__MSG_…__` placeholders, the store's length limits, the listings against the version being released,
the screenshots' dimensions. See [`tools/README.md`](tools/README.md).

Everything the Web Store form asks for lives in [`store/`](store/README.md): a checklist, listing
copy in two languages, permission justifications and the data-use answers. The privacy policy is
`webapp/src/jsMain/resources/privacy.html`, shipped to Pages with the web version, at
<https://stramus.space/privacy.html>.

The name, short description and toolbar tooltip come not from `manifest.json` but from
`extension/src/jsMain/resources/_locales/{en,ru}/messages.json` — the Web Store reads them out of the
uploaded ZIP, so editing them there changes Chrome's UI and the store listing at once.

## Modules

| Module | What's in it |
| --- | --- |
| `core` | Models, DB schema, repositories over Kormium, the sync engine, PIN hashing, AI tab triage |
| `ui-shared` | The whole React UI — shared between the web app and the extension |
| `webapp` | `main()` for the web version + `index.html` and styles |
| `extension` | `main()` for the extension, `manifest.json`, wrappers over the Chrome APIs |
| `protocol` | Sync protocol DTOs, shared by client and server |
| `server` | Ktor + Kormium: accounts and `/v1/sync` |

Different entry points supply different implementations of `TabCapture` and `HistoryAccess` from
`core`: in the extension they call the Chrome APIs, in the web app they're stubs.

## Collection icons

A collection can be marked with a glyph and a colour, or with an emoji, picked from a popup that hangs
off the mark itself. Both libraries are inline SVG committed to the source tree — `IconLibrary.kt`
(Lucide, ISC) and `EmojiLibrary.kt` (Twemoji, graphics CC-BY 4.0) — written by
`tools/icon-data/generate.py`, which is run by hand and never by Gradle: a build that reaches for a CDN
is a build that fails on a train.

The emoji are shipped as drawings rather than left to the system font on purpose. A character is drawn
by whatever the machine happens to have — a different picture on every OS, sometimes none at all — and
the same reasoning already governs the app's own glyphs (see `Icon.kt`). The picker's search matches
CLDR keywords in every UI language that has them (Turkish falls back to English); glyph keywords are
Lucide's own and exist in English only.

## Checking the build

```bash
./gradlew :core:jvmTest :server:test                      # 122 tests
./gradlew :webapp:jsBrowserDistribution :extension:jsBrowserDistribution
python3 tools/preflight.py                                # version, locales, listings, store assets
```

Tests run on the JVM: `core` builds for both `js` (the app) and `jvm` (tests only) — so card ordering,
the triage's prompt and plan logic, and the merge rules are checked there rather than by eye in a
browser, against the server's real SQLite. What only exists in the browser — the store itself and its
migrations — is `:core:jsNodeTest`, under `fake-indexeddb`. The most important one is
`EndToEndSyncTest`: two client stores and a real server over HTTP.

## Kormium from a sibling checkout

If a `../korm` folder sits next to this repository, it's picked up as a composite build, and edits to
Kormium are reflected immediately, with nothing to publish. If it's absent (as in CI or a fresh clone),
the published `io.github.kormium:*:0.11.0` is resolved from Maven Central instead. Nothing to do by
hand — the condition lives in `settings.gradle.kts`.

## CI/CD

- **CI** (`ci.yml`) — runs `tools/preflight.py` (a second, no JDK), then the tests, then builds both
  bundles, on every PR and push to `main`.
- **Server image** (`docker.yml`) — builds the sync server's container image and pushes it to GHCR
  (`ghcr.io/olluorg/stramus-server`) on pushes to `main` and version tags that touch the server; also
  runnable by hand.
- **Pages** (`pages.yml`) — deploys the web version (landing page + app) to
  <https://stramus.space> on push to `main`.
- **Release** (`release.yml`) — builds the extension ZIP on a version tag and attaches it to a GitHub
  Release. That same ZIP is what gets uploaded to the Web Store, packed by `tools/package-extension.sh`
  — the same script that packs a local one. The tag has to match `version` in `manifest.json`, which
  `tools/preflight.py --tag` checks before anything is built:

  ```bash
  git tag v1.2.1 && git push origin v1.2.1
  ```
