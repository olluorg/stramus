// Settings → Data: export CSV / bookmarks, and import. Bonus/onboarding shot.
import { openApp, setTheme, openSettings, goToSettingsTab } from '../lib/dom.mjs';
import { importDemoData } from '../lib/seed.mjs';

export const description = 'Settings → Data: export CSV / bookmarks, import.';

export async function run({ page, newTabUrl }) {
  await openApp(page, newTabUrl);
  await importDemoData(page);
  await setTheme(page, 'Light');
  await openSettings(page);
  await goToSettingsTab(page, 'Data');
}
