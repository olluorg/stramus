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

// The manifest declares no background service worker (chrome_url_overrides.newtab is a plain
// page, not a script Chrome needs to run in the background) — there is never a 'serviceworker'
// event to wait for. `chrome://newtab/` is Chrome's own redirect to the overriding extension's
// page, so navigating there and reading back the resolved URL is what recovers the (per-launch
// random) extension ID without needing one.
async function resolveExtensionId(context) {
  const page = context.pages()[0] ?? (await context.newPage());
  await page.goto('chrome://newtab/');
  const { host } = new URL(page.url());
  await page.goto('about:blank');
  return host;
}
