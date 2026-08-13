// Settings → AI: provider choice + on-device model status. A bonus/onboarding shot, not one of
// the five store screenshots, showing the framework generalizes past store/screenshots.md.
import { openApp, setTheme, openSettings, goToSettingsTab } from '../lib/dom.mjs';
import { importDemoData } from '../lib/seed.mjs';

export const description = 'Settings → AI: provider picker and on-device model status.';

export async function run({ page, newTabUrl }) {
  await openApp(page, newTabUrl);
  await importDemoData(page);
  await setTheme(page, 'Light');
  await openSettings(page);
  await goToSettingsTab(page, 'AI');
}
