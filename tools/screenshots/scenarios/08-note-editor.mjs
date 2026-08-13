// The note editor, opened from a card-section's "+" menu → Note. Bonus/onboarding shot.
// The menu only reveals its dropdown on hover (CSS, not React state — see App.kt's hoverMenu),
// so it has to be hovered before the item underneath is clickable.
import { openApp, setTheme, openCollection } from '../lib/dom.mjs';
import { importDemoData } from '../lib/seed.mjs';

export const description = 'Note editor, opened from a card-section’s + menu.';

export async function run({ page, newTabUrl }) {
  await openApp(page, newTabUrl);
  await importDemoData(page);
  await setTheme(page, 'Light');
  await openCollection(page, 'Work', 'Frontend');

  const menu = page.locator('.card-section-head .menu').first();
  await menu.hover();
  await menu.locator('.menu-item', { hasText: 'Note' }).click();

  const modal = page.locator('.modal.note-modal');
  await modal.waitFor({ state: 'visible' });
  await modal.locator('.modal-title-input').fill('Handy references');
  await modal.locator('.wysiwyg').click();
  await page.keyboard.type('A few things worth keeping close while building the frontend.');
}
