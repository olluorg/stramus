// The tab pane mid-save: a tab dragged over a collection, held there.
// store/screenshots.md #5, shot 2.
import { openApp, setTheme, ensureSectionOpen, dragHold } from '../lib/dom.mjs';
import { importDemoData, openDemoTabs } from '../lib/seed.mjs';

export const description = 'Tab pane: a tab dragged over Work → Frontend, held mid-drop.';

export async function run({ page, context, newTabUrl }) {
  await openApp(page, newTabUrl);
  await importDemoData(page);
  await openDemoTabs(context);
  await setTheme(page, 'Light');
  await ensureSectionOpen(page, 'Work');

  const tab = page.locator('li.tab', { hasText: 'GitHub' }).first();
  const collection = page.locator('li.col', {
    has: page.locator('.col-title', { hasText: 'Frontend' }),
  });
  await tab.waitFor({ state: 'visible' });
  await collection.waitFor({ state: 'visible' });

  // Held, not dropped — dropping would actually move the tab and change the state we want to show.
  await dragHold(page, tab, collection);
}
