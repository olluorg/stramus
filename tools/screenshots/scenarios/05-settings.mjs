// Settings open on Dark, showing theme + language. store/screenshots.md #5, shot 5.
// The Settings modal is tabbed (Appearance / Account / Startup / Tabs / Security / AI / Data /
// About — Settings.kt), so theme, language, the assistant choice and CSV export can't all be in
// frame at once; Appearance is the one that carries the listing's main visual claim (theme +
// language together). The AI and Data tabs are their own scenarios (06-ai-settings, 08-data-export).
import { openApp, setTheme, openSettings, goToSettingsTab } from '../lib/dom.mjs';
import { importDemoData } from '../lib/seed.mjs';

export const description = 'Settings → Appearance on Dark: theme and language.';

export async function run({ page, newTabUrl }) {
  await openApp(page, newTabUrl);
  await importDemoData(page);
  await setTheme(page, 'Dark');
  await openSettings(page);
  await goToSettingsTab(page, 'Appearance');
}
