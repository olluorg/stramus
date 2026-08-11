// The extension's only always-on code — everything else lives in the new-tab page's React app and
// only runs while a stramus tab is open. This is what lets a tab be captured (keyboard shortcut,
// right-click, or the toolbar button) without switching to one first.
//
// A capture never touches the database directly: it only stages {title, url, favicon} into
// chrome.storage.local under PENDING_KEY. The app turns pending captures into cards the next time
// it is open (App.kt's reconcilePendingCaptures) — the one place that already knows how to write a
// card correctly (order keys, the default collection, the duplicate check). Reimplementing that here,
// against a database this worker has no safe way to open at the same time as the app, would risk
// corrupting it instead of merely being late.

const PENDING_KEY = "stramus.pendingCaptures";

chrome.runtime.onInstalled.addListener(() => {
  chrome.contextMenus.create({
    id: "save-page",
    title: chrome.i18n.getMessage("ctxSavePage"),
    contexts: ["page"],
  });
  chrome.contextMenus.create({
    id: "save-link",
    title: chrome.i18n.getMessage("ctxSaveLink"),
    contexts: ["link"],
  });
});

function hostOf(url) {
  try {
    return new URL(url).hostname;
  } catch (e) {
    return url;
  }
}

function faviconOf(tab) {
  return tab && tab.favIconUrl && tab.favIconUrl.startsWith("http") ? tab.favIconUrl : null;
}

// [title] is left blank for a link capture — chrome.contextMenus hands back the link's address, not
// its anchor text — and the app falls back to the host the same way saveTab already does for a tab
// with no title.
async function queueCapture(title, url, favicon) {
  if (!url || !/^https?:\/\//.test(url)) return;
  const stored = await chrome.storage.local.get(PENDING_KEY);
  const pending = stored[PENDING_KEY] || [];
  pending.push({ title: title || "", url, favicon: favicon || null });
  await chrome.storage.local.set({ [PENDING_KEY]: pending });

  // Best-effort: a stramus tab open somewhere reconciles right away instead of waiting for the next
  // new-tab open. No listener on the other end is the common case, not an error.
  chrome.runtime.sendMessage({ type: "stramus:capture-queued" }).catch(() => {});

  chrome.notifications.create({
    type: "basic",
    iconUrl: "logo-128.png",
    title: chrome.i18n.getMessage("notifSaved"),
    message: title || hostOf(url),
  });
}

chrome.commands.onCommand.addListener((command, tab) => {
  if (command === "save-current-tab" && tab && tab.url) {
    queueCapture(tab.title, tab.url, faviconOf(tab));
  }
});

// The toolbar button has no popup, so a click reaches here: the same capture as the shortcut.
chrome.action.onClicked.addListener((tab) => {
  if (tab && tab.url) queueCapture(tab.title, tab.url, faviconOf(tab));
});

chrome.contextMenus.onClicked.addListener((info, tab) => {
  if (info.menuItemId === "save-page" && tab && tab.url) {
    queueCapture(tab.title, tab.url, faviconOf(tab));
  } else if (info.menuItemId === "save-link" && info.linkUrl) {
    queueCapture("", info.linkUrl, null);
  }
});
