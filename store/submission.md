# The submission form, field by field

Copy-paste for the Web Store draft item `accjfifjflckbinniekamhehcjdampjh`, in the order the console
asks. The reasoning behind these answers is in [`README.md`](README.md); this file is just the answers.

The console cannot be filled in by automation — Chrome forbids extensions from acting on the Web Store
domain, and browser automation is an extension — so this is a manual pass.

## Package

Upload `stramus-extension-1.5.2.zip`, off the release the `v1.5.2` tag builds
(https://github.com/olluorg/stramus/releases/tag/v1.5.2).

**The permission list is unchanged from 1.4.0**, so this is an ordinary update: nobody is asked to
accept anything and nobody's extension is disabled waiting for them to. The justifications below stand
as they are; 1.4.0 is where the three that carry a warning (`storage`, `contextMenus`, `notifications`)
were added and explained.

1.2.0 was tagged and released but never uploaded: it declared no output language to Chrome's built-in
model, which put a console error in the extension's error list on `chrome://extensions` — the first
thing a reviewer opening the extension would see. 1.2.1 is that same build with the declaration.

The name, short description and toolbar tooltip come from the ZIP's `_locales` and will appear
pre-filled and greyed out. Do not retype them anywhere.

## Store listing

| Field | Value |
| --- | --- |
| Category | Productivity → Workflow & Planning |
| Language | English (default); add Russian as a second locale |
| Detailed description (en) | everything under the `---` in [`listing-en.md`](listing-en.md) |
| Detailed description (ru) | everything under the `---` in [`listing-ru.md`](listing-ru.md) |
| Homepage URL | `https://stramus.space/` |
| Support URL | `https://github.com/olluorg/stramus/issues` |
| Store icon | [`store-icon-128.png`](store-icon-128.png) — upload it; the console asks for this one separately and does not take it from the package |
| Screenshots | `store/screenshots/01-collection.png`, `02-tabs.png`, `03-search.png`, `05-settings.png` — four, in that order; see README.md for why the assistant shot is not among them |
| Promo tiles | leave empty — optional, and only needed to be eligible for featuring |

## Privacy practices

**Single purpose** (one paragraph, paste as is):

> stramus replaces the new tab page with a place to save, organise and re-open the pages you use: your
> open tabs become collections of cards, and a search box finds them again — along with your tabs and
> your browsing history — from the same keystroke.

**Permission justifications** — one box per permission:

- `tabs`

  > stramus shows the user their open tabs so they can save a tab into a collection, drag it to another
  > position, sort a window's tabs, and close a tab once it is saved. All of this is the visible work of
  > the tab pane, and none of it happens without the user asking for it.

- `history`

  > The history pane lists recently visited pages so that one can be saved as a card, and the search box
  > searches visited pages alongside saved ones. History is read from the browser at the moment the user
  > searches it. No copy is kept and nothing is transmitted.

- `search`

  > A query typed into the search box that is meant for the web is handed to chrome.search, which sends
  > it to whichever search engine the user has set as their default, exactly as the address bar would.
  > The alternative would be hardcoding a search engine on the user's behalf.

- `favicon`

  > Site icons for saved links and open tabs are read from the browser's own favicon store. This is a
  > privacy measure: the alternative is asking a public icon service for each host, which would disclose
  > to that service which sites the user keeps. For a site this browser has never visited the store has
  > no icon, and the icon is then fetched by our own server on the user's behalf
  > (GET /v1/favicon?host=…, anonymous, no account and no identifier attached); only if that server
  > cannot be reached does the extension ask favicone.com or google.com/s2/favicons directly.

- `identity`

  > Signing in with Google, and nothing else. chrome.identity.getAuthToken requests a token for the
  > account already signed into Chrome, with the scopes "openid" and "email" — an email address and
  > nothing more — and chrome.identity.launchWebAuthFlow is the fallback where that is unavailable. Both
  > happen only when the user presses "Sign in with Google". An account is optional and the extension is
  > fully usable without one.

- `storage`

  > A page saved by the keyboard shortcut, the right-click menu or the toolbar button waits in
  > chrome.storage.local until a stramus tab is open to turn it into a card. All three work from any tab,
  > and there may be no stramus tab at that moment. What is stored is the page's title, its address and
  > the address of its icon; it is removed as soon as the card exists. Nothing is transmitted.

- `contextMenus`

  > The two right-click entries, "Save page to stramus" and "Save link to stramus". They act on the page
  > or the link the user clicked, at the moment they click it. There is no content script and nothing is
  > read from the page besides what the click itself supplies.

- `notifications`

  > To confirm that one of those saves happened. It is the only feedback available: the save works with
  > no stramus tab open, so there may be no window to show it in. The notification is the saved page's
  > own title, displayed by the browser and sent nowhere.

- Host permission `https://api.stramus.space/*`

  > Our own server, and the only host the extension requests permission for. It is asked for two things:
  > synchronisation, which happens only while the user is signed in, and the anonymous favicon lookup
  > described above, which carries no account. No other host permission is requested; there is no
  > <all_urls>, no content script, and no injection into any page the user visits. (The one other host
  > the browser may contact is i.ytimg.com, and only if the user switches video previews on — an ordinary
  > <img> load, which needs no host permission. See the note under "Two things a reviewer may ask
  > about".)

**Remote code:** No.

> The extension executes no code it did not ship with; everything in the package is compiled from
> https://github.com/olluorg/stramus. The content security policy is `script-src 'self'; object-src
> 'self'` — no eval of any kind and no WebAssembly at all: the database is the browser's own IndexedDB,
> so there is no engine to load.

**Data usage** — tick these four, leave the rest:

| Category | Note to give |
| --- | --- |
| Personally identifiable information | The email address that the optional account is. Collected only if the user signs in. |
| Authentication information | The sign-in session token, and the salted hash of a section PIN, which travels with the section it belongs to. Only if the user signs in. |
| Website content | The user's own saved cards — links, note text and files they put there. Synchronised only if the user signs in. |
| Web history | Counters of pages the user opened from stramus, with their titles. Kept on the machine and off by default; transmitted only if the user switches "Sync browsing statistics" on. |

Do **not** tick: health, financial and payment information, personal communications, location, user
activity. The browser's own history that the `history` permission reads is never uploaded.

Certify all three — not sold to third parties, not used for purposes unrelated to the single purpose,
not used for creditworthiness or lending. All three hold.

**Privacy policy URL:** `https://stramus.space/privacy.html`

## Distribution

| Field | Value |
| --- | --- |
| Visibility | **Unlisted** — reachable by link, not by store search. Switching to Public later needs no new review. |
| Regions | All |
| Pricing | Free |

## Three things a reviewer may ask about

None is data collection, and all three are in the privacy policy:

- Choosing ChatGPT, Gemini or Claude as the assistant means a question the user types opens a chat with
  that service in a new tab, with the question in it. The default, where the browser has an on-device
  model, answers locally and sends nothing.
- A web search from the search box goes to the user's own default search engine.
- **Video previews** (new in 1.4.0, and the reason `i.ytimg.com` may appear in a network log). Off unless
  the user switches them on in Settings → Appearance. Switched on, a card standing for a saved YouTube
  video draws that video's published still frame by pointing an `<img>` at
  `i.ytimg.com/vi/<id>/mqdefault.jpg` — the address built from the video id in the saved link, the same
  one any embed of that video loads. It is an ordinary image request made by the user's browser, with
  `referrerpolicy="no-referrer"`, so Google sees the video id and the IP address and nothing of ours.
  Nothing is fetched to our server, stored with the card or re-encoded; switch the setting off and the
  request is not made at all.

## After submitting

- [ ] First review of a new item usually takes a few days.
- [ ] Once published: put the store link in `README.md` and in the web app's header.
- [ ] Check that sign-in works from the published extension — that is the first live test of the
      Application ID and the redirect URI now pointing at `accjfifjflckbinniekamhehcjdampjh`.
