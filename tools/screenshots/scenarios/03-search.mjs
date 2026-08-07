// "kotlin" typed into search, showing all three kinds of hit at once: a saved card, an open tab,
// and a history entry. store/screenshots.md #5, shot 3.
import { openApp, setTheme, search } from '../lib/dom.mjs';
import { importDemoData, seedHistory, openDemoTabs } from '../lib/seed.mjs';

export const description = 'Search box with "kotlin": card, open-tab and history hits together.';

export async function run({ page, context, newTabUrl }) {
  await openApp(page, newTabUrl);
  await importDemoData(page);
  // kotlinlang.org is in both — the overlap is what makes the tab and history hits show up
  // alongside the "Kotlin Documentation" card from the imported data.
  await seedHistory(context);
  await openDemoTabs(context);
  await setTheme(page, 'Light');

  await search(page, 'kotlin');
  await page.waitForTimeout(300);
}
