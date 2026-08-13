// The "folder" layout for card-sections (Settings → Appearance → Sections view, experimental).
// Bonus/onboarding shot showing an alternate content-pane layout.
import { openApp, setTheme, openSettings, goToSettingsTab, closeSettings, openCollection } from '../lib/dom.mjs';
import { importDemoData } from '../lib/seed.mjs';

export const description = 'Card-sections in "Folders" layout, closed and one open.';

export async function run({ page, newTabUrl }) {
  await openApp(page, newTabUrl);
  await importDemoData(page);
  await setTheme(page, 'Light');

  await openSettings(page);
  await goToSettingsTab(page, 'Appearance');
  const row = page.locator('.settings-row', { has: page.locator('.settings-title', { hasText: 'Sections view' }) });
  await row.locator('.theme-opt', { hasText: 'Folders' }).click();
  await closeSettings(page);

  await openCollection(page, 'Work', 'Frontend');
  await page.locator('.card-group.folder').first().click();
}
