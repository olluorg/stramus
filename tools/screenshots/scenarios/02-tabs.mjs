// The tab pane mid-save: a tab dragged over a collection, held there.
// store/screenshots.md #5, shot 2.
import { openApp, setTheme, openCollection, dragHold } from '../lib/dom.mjs';
import { importDemoData, openDemoTabs } from '../lib/seed.mjs';

export const description = 'Tab pane: a tab dragged over Work → Backend, held mid-drop, over a filled grid.';

export async function run({ page, context, newTabUrl }) {
  await openApp(page, newTabUrl);
  await importDemoData(page);
  await openDemoTabs(context);
  await setTheme(page, 'Light');
  // A collection is opened rather than merely its section unfolded, and a populated one: without it
  // the shot is a drag over an empty content pane, which is what a fresh profile opens on now (the
  // seeded welcome collection) and which makes the app look like it holds nothing. The drag then goes
  // to a *different* collection than the one on screen, so both read at once — what is saved already,
  // and where this tab is about to go.
  await openCollection(page, 'Work', 'Frontend');

  const tab = page.locator('li.tab', { hasText: 'GitHub' }).first();
  const collection = page.locator('li.col', {
    has: page.locator('.col-title', { hasText: 'Backend' }),
  });
  await tab.waitFor({ state: 'visible' });
  await collection.waitFor({ state: 'visible' });

  // Held, not dropped — dropping would actually move the tab and change the state we want to show.
  await dragHold(page, tab, collection);
}
