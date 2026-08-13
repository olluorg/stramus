// The listing's thumbnail — populated, nothing mid-interaction.
// store/screenshots.md #5, shot 1.
import { openApp, setTheme, openCollection } from '../lib/dom.mjs';
import { importDemoData, openDemoTabs } from '../lib/seed.mjs';

export const description = 'Work → Frontend open: sidebar, populated card grid, tab pane.';

export async function run({ page, context, newTabUrl }) {
  await openApp(page, newTabUrl);
  await importDemoData(page);
  await openDemoTabs(context);
  await setTheme(page, 'Light');
  await openCollection(page, 'Work', 'Frontend');
  await page.waitForTimeout(300); // let card thumbnails/favicons finish painting
}
