// Selectors here were mapped by hand against ui-shared/src/jsMain/kotlin/stramus/ui — the app has
// no data-testid attributes, so most of this keys off classes with a handful of English-string
// fallbacks (the profile is forced to en-US in lib/launch.mjs so these stay stable).

/** Thrown by a scenario to say "this shot can't be taken right now" — capture.mjs reports it as
 *  skipped rather than failed. Mirrors store/screenshots.md's own fallback for the AI shot: "if
 *  the window never appears, skip this shot, four screenshots is a complete listing". */
export class SkipShot extends Error {}

export async function openApp(page, newTabUrl) {
  await page.goto(newTabUrl);
  await page.locator('main.content').waitFor({ state: 'visible' });
}

export async function openSettings(page) {
  await page.locator('button.settings-btn, button.rail-toggle.settings-rail').first().click();
  await page.locator('.settings-modal').waitFor({ state: 'visible' });
}

export async function closeSettings(page) {
  await page.keyboard.press('Escape');
  await page.locator('.settings-modal').waitFor({ state: 'hidden' });
}

export async function goToSettingsTab(page, tabName) {
  await page.locator('.settings-nav-item', { hasText: tabName }).click();
}

/** theme: 'Auto' | 'Light' | 'Dark' (English labels — see I18n.kt themeAuto/themeLight/themeDark). */
export async function setTheme(page, theme) {
  await openSettings(page);
  await goToSettingsTab(page, 'Appearance');
  await page.locator('.theme-toggle .theme-opt', { hasText: theme }).click();
  await page.waitForFunction(
    (expected) => document.documentElement.getAttribute('data-theme') === expected,
    theme.toLowerCase(),
  );
  await closeSettings(page);
}

export async function importCsv(page, csvPath) {
  await openSettings(page);
  await goToSettingsTab(page, 'Data');
  await page.locator('.settings-modal input.hidden-file-input').setInputFiles(csvPath);
  await page.locator('.settings-modal .settings-hint').first().waitFor({ state: 'visible' });
  await closeSettings(page);
}

/** Expands a section if it's currently collapsed; leaves it alone otherwise (the title toggles
 *  collapse on click, so blindly clicking it would close an already-open section). */
export async function ensureSectionOpen(page, sectionName) {
  const section = page.locator('.section', {
    has: page.locator('.section-title', { hasText: sectionName }),
  });
  const collapsed = await section.evaluate((el) => el.classList.contains('collapsed'));
  if (collapsed) await section.locator('.section-title').click();
}

export async function openCollection(page, sectionName, collectionName) {
  await ensureSectionOpen(page, sectionName);
  const section = page.locator('.section', {
    has: page.locator('.section-title', { hasText: sectionName }),
  });
  await section.locator('.col-title', { hasText: collectionName }).click();
}

/**
 * The tab pane / sidebar / card-group drop targets are wired as native HTML5 drag-and-drop
 * (draggable + dragstart/dragover/drop handlers, see App.kt's TabRow) rather than pointer
 * events, so Playwright's own `locator.dragTo()` (which synthesizes mouse movement, not real
 * DragEvents) does not trigger them. Dispatching real DragEvent/DataTransfer objects from inside
 * the page is the reliable way to drive this — same technique Playwright's own docs recommend
 * for HTML5 DnD.
 */
async function fireDragSequence(page, source, target, { drop }) {
  const src = await source.elementHandle();
  const dst = await target.elementHandle();
  await page.evaluate(
    ([srcEl, dstEl, doDrop]) => {
      const dt = new DataTransfer();
      const fire = (el, type) =>
        el.dispatchEvent(new DragEvent(type, { bubbles: true, cancelable: true, dataTransfer: dt }));
      fire(srcEl, 'dragstart');
      fire(dstEl, 'dragenter');
      fire(dstEl, 'dragover');
      if (doDrop) {
        fire(dstEl, 'drop');
        fire(srcEl, 'dragend');
      }
    },
    [src, dst, drop],
  );
}

/** Drags `source` over `target` and holds it there (dragover), without dropping. */
export async function dragHold(page, source, target) {
  await fireDragSequence(page, source, target, { drop: false });
}

/** Full drag-and-drop: drags `source` onto `target` and drops it. */
export async function dragDrop(page, source, target) {
  await fireDragSequence(page, source, target, { drop: true });
}

export async function search(page, query) {
  const input = page.locator('input.search');
  await input.click();
  await input.fill(query);
  await page.locator('.search-drop').waitFor({ state: 'visible' });
}

export async function clearSearch(page) {
  await page.locator('input.search').fill('');
  await page.keyboard.press('Escape');
}

// Exact substring of Strings.aiModelReadyHint (I18n.kt) — the only state where the model is
// actually downloaded and can answer without triggering a first-question download mid-capture.
const AI_READY_HINT = "Runs on this machine";

/**
 * Reads Settings → AI without touching the provider choice, so it's safe to call even when the
 * on-device model isn't available (selecting an "Ask" search hit is *not* safe for this — see
 * scenarios/04-assistant.mjs for why). `ready` is stricter than `available`: it's false for the
 * DOWNLOADABLE/DOWNLOADING states too, since driving those into an answer means waiting out a
 * multi-hundred-MB download.
 */
export async function checkLocalAiAvailable(page) {
  await openSettings(page);
  await goToSettingsTab(page, 'AI');
  const modelRow = page.locator('.settings-row', { has: page.locator('.ai-model') });
  const hasRow = (await modelRow.count()) > 0;
  const title = hasRow ? (await modelRow.locator('.ai-model').innerText()).trim() : null;
  const hint = hasRow ? (await modelRow.locator('.settings-hint').innerText()).trim() : null;
  const localButton = page.locator('.theme-toggle .theme-opt', { hasText: 'On-device' });
  const available = (await localButton.count()) > 0 && !(await localButton.isDisabled());
  const ready = available && !!hint && hint.includes(AI_READY_HINT);
  await closeSettings(page);
  return { available, ready, title, hint };
}

/** Switches the assistant provider to the on-device model. Only call after checkLocalAiAvailable(). */
export async function useLocalAiProvider(page) {
  await openSettings(page);
  await goToSettingsTab(page, 'AI');
  await page.locator('.theme-toggle .theme-opt', { hasText: 'On-device' }).click();
  await closeSettings(page);
}
