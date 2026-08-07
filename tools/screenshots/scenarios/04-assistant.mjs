// The assistant answering over the open collection. store/screenshots.md #5, shot 4.
//
// Only the LOCAL (on-device) provider opens an in-page window — a web provider (ChatGPT/Gemini/
// Claude) navigates the current tab away to that service instead, which is not a screenshot and
// would leave the browser on someone else's site. So this scenario forces the LOCAL provider and
// requires the model to already be downloaded and ready; anything else throws SkipShot, same as
// store/screenshots.md's own fallback ("if the window never appears, skip this shot").
import {
  openApp,
  setTheme,
  openCollection,
  checkLocalAiAvailable,
  useLocalAiProvider,
  search,
  SkipShot,
} from '../lib/dom.mjs';
import { importDemoData } from '../lib/seed.mjs';

export const description = 'AI assistant answering about the open Frontend collection.';
export const QUESTION = 'Which of these links would help me compress an image?';

export async function run({ page, newTabUrl }) {
  await openApp(page, newTabUrl);
  await importDemoData(page);
  await setTheme(page, 'Light');
  await openCollection(page, 'Work', 'Frontend');

  const status = await checkLocalAiAvailable(page);
  if (!status.ready) {
    throw new SkipShot(
      `On-device model not ready (available=${status.available}, hint="${status.hint}") — ` +
        'needs Chrome 138+ with the model already downloaded.',
    );
  }
  await useLocalAiProvider(page);

  await search(page, QUESTION);
  const askHit = page.locator('.hit', { has: page.locator('.hit-action', { hasText: 'Ask' }) });
  await askHit.first().click();

  const modal = page.locator('.ai-modal');
  await modal.waitFor({ state: 'visible', timeout: 10000 });
  // AiChat.kt renders the "no model" error as `.empty` alone; the ordinary before-any-question
  // state is `.empty.small` — checking `:not(.small)` avoids mistaking that transient mount state
  // for a real unavailability error.
  if ((await modal.locator('.ai-log .empty:not(.small)').count()) > 0) {
    throw new SkipShot('AI window opened but reported no built-in model available.');
  }

  await page.locator('.ai-log .ai-answer').first().waitFor({ state: 'visible', timeout: 30000 });
  await page.waitForTimeout(500);
}
