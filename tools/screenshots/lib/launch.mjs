import { chromium } from 'playwright';
import { existsSync, mkdtempSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = fileURLToPath(new URL('.', import.meta.url));
export const REPO_ROOT = resolve(HERE, '..', '..', '..');

// The build the release ZIP is made from — see store/screenshots.md.
export const EXTENSION_DIST = resolve(
  REPO_ROOT,
  'extension/build/dist/js/productionExecutable',
);

export const VIEWPORT = { width: 1280, height: 800 };

/**
 * Launches Chromium with the unpacked extension loaded, in a throwaway profile.
 * `--load-extension`/`--disable-extensions-except` load it without Developer Mode
 * (that toggle is only needed when loading through chrome://extensions by hand).
 * The rest of the flags exist because a stock profile shows first-run/default-browser/
 * search-engine-choice prompts that would otherwise sit on top of the app and break
 * every selector — none of them relax any *extension* permission, only browser chrome.
 */
export async function launchExtension({ headless = false, video = false, videoDir } = {}) {
  if (!existsSync(join(EXTENSION_DIST, 'manifest.json'))) {
    throw new Error(
      `No build at ${EXTENSION_DIST}.\n` +
        'Build it first: ./gradlew :extension:jsBrowserDistribution',
    );
  }

  const userDataDir = mkdtempSync(join(tmpdir(), 'stramus-shots-'));
  if (video && videoDir) mkdirSync(videoDir, { recursive: true });

  const args = [
    `--disable-extensions-except=${EXTENSION_DIST}`,
    `--load-extension=${EXTENSION_DIST}`,
    '--no-first-run',
    '--no-default-browser-check',
    '--disable-sync',
    '--disable-search-engine-choice-screen',
    '--disable-features=PrivacySandboxSettings4,AutofillServerCommunication',
    '--lang=en-US',
  ];

  const context = await chromium.launchPersistentContext(userDataDir, {
    headless,
    // `headless: true` alone runs Playwright's headless *shell*, a build with no extension support at
    // all: the extension silently never loads and the first thing that notices is the ID lookup below
    // failing. Naming the channel picks the full Chromium instead, in its own headless mode, which
    // does load one. Only for headless — a headed run is already the full browser.
    ...(headless ? { channel: 'chromium' } : {}),
    viewport: VIEWPORT,
    deviceScaleFactor: 1,
    colorScheme: 'light',
    locale: 'en-US',
    args,
    recordVideo: video ? { dir: videoDir, size: VIEWPORT } : undefined,
  });

  const extensionId = await resolveExtensionId(context);

  return {
    context,
    extensionId,
    userDataDir,
    newTabUrl: `chrome-extension://${extensionId}/index.html`,
    async close() {
      await context.close();
    },
  };
}

// The ID is random per launch, so it has to be read back from the running browser rather than known.
// Two ways, and the first one only became available in 1.4.0: the extension now has a background
// service worker (background.js, for the captures that work with no stramus tab open), and its URL
// carries the ID. Playwright hands it over directly, with no page to navigate and no chrome:// URL
// involved — which is what makes `--headless` work at all, headless Chromium refusing to navigate to
// chrome://newtab/ with ERR_INVALID_URL.
//
// The old way is kept behind it: `chrome://newtab/` redirects to whichever extension overrides the
// page, so the resolved URL names the extension. It is the fallback for a build whose worker has not
// woken yet, and it is why headed runs went on working before there was a worker at all.
async function resolveExtensionId(context) {
  const worker = context.serviceWorkers()[0]
    ?? (await context.waitForEvent('serviceworker', { timeout: 10_000 }).catch(() => null));
  if (worker) return new URL(worker.url()).host;

  const page = context.pages()[0] ?? (await context.newPage());
  await page.goto('chrome://newtab/');
  const { host } = new URL(page.url());
  await page.goto('about:blank');
  return host;
}
