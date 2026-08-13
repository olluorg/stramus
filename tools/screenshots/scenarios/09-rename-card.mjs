// The rename dialog, opened from a card's pencil icon (shown on hover). Bonus/onboarding shot.
import { openApp, setTheme, openCollection } from '../lib/dom.mjs';
import { importDemoData } from '../lib/seed.mjs';

export const description = 'Rename dialog for a saved card.';

export async function run({ page, newTabUrl }) {
  await openApp(page, newTabUrl);
  await importDemoData(page);
  await setTheme(page, 'Light');
  await openCollection(page, 'Work', 'Frontend');

  const card = page.locator('.card').first();
  await card.hover();
  await card.locator('.icon.edit').click();

  await page.locator('.modal.rename-modal').waitFor({ state: 'visible' });
}
