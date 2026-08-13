import { join } from 'node:path';
import { REPO_ROOT } from './launch.mjs';
import { importCsv } from './dom.mjs';

export const SCREENSHOT_DATA_CSV = join(REPO_ROOT, 'store', 'screenshot-data.csv');

// A subset of screenshot-data.csv's own sites — visiting them for real writes real Chrome
// history, which is what teaches the search box's ranking and what the history pane lists.
// (store/screenshots.md's manual procedure does this by clicking cards in the app; visiting
// directly is equivalent from chrome.history's point of view and doesn't depend on UI state.)
export const HISTORY_SITES = [
  { url: 'https://kotlinlang.org/', visits: 3 },
  { url: 'https://github.com/', visits: 3 },
  { url: 'https://www.figma.com/', visits: 2 },
  { url: 'https://developer.mozilla.org/', visits: 2 },
  { url: 'https://news.ycombinator.com/', visits: 2 },
];

// ~8 open tabs "of the same flavour as the collections", for the tab-pane shot — an empty pane
// makes the product look empty (store/screenshots.md).
export const DEMO_TAB_SITES = [
  'https://github.com/',
  'https://developer.mozilla.org/',
  'https://react.dev/',
  'https://kotlinlang.org/',
  'https://www.figma.com/',
  'https://vite.dev/',
  'https://www.notion.so/',
  'https://linear.app/',
];

export async function importDemoData(page, csvPath = SCREENSHOT_DATA_CSV) {
  await importCsv(page, csvPath);
}

/** Visits each site for real, `visits` times each, so it lands in the browser's own history. */
export async function seedHistory(context, sites = HISTORY_SITES) {
  for (const { url, visits } of sites) {
    for (let i = 0; i < visits; i++) {
      const page = await context.newPage();
      await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 20000 }).catch(() => {});
      await page.close();
    }
  }
}

/** Opens `urls` as real tabs and leaves them open — the caller decides when to close them. */
export async function openDemoTabs(context, urls = DEMO_TAB_SITES) {
  const pages = [];
  for (const url of urls) {
    const page = await context.newPage();
    await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 20000 }).catch(() => {});
    pages.push(page);
  }
  return pages;
}
