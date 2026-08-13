#!/usr/bin/env node
// One-off capture for the promo video (../promo-video): real interaction footage — a live search,
// a tab held over a drop target, a light→dark theme switch — rather than the static screenshots
// tools/promo-video/public/screenshots holds. Not part of shots.mjs on purpose: these are timed
// for how they get trimmed and cut in Remotion (see promo-video/public/clips/*.mp4 and
// promo-video/README.md), not for one representative frame.
//
// Output lands in promo-video/public/clips/ as page@<hash>.webm — rename to {search,drag,
// settings}.webm, then re-encode+trim the useful seconds to h264 mp4 (ffmpeg -ss/-t), e.g.:
//   ffmpeg -ss 19.55 -i search.webm -t 3.5 -c:v libx264 -pix_fmt yuv420p -crf 18 -an search-clip.mp4
// The @remotion/compositor-linux-x64-gnu package (once `npm install`ed in promo-video/) bundles
// its own ffmpeg if there's no system one: node_modules/@remotion/compositor-*/ffmpeg.
import { join } from 'node:path';
import { launchExtension, REPO_ROOT } from './lib/launch.mjs';
import { openApp, setTheme, ensureSectionOpen, dragHold, openSettings, goToSettingsTab } from './lib/dom.mjs';
import { importDemoData, seedHistory, openDemoTabs } from './lib/seed.mjs';

const outDir = join(REPO_ROOT, 'tools/promo-video/public/clips');

async function captureSearch() {
  const { context, newTabUrl, close } = await launchExtension({ video: true, videoDir: outDir });
  const page = context.pages()[0] ?? (await context.newPage());
  const video = page.video();
  await openApp(page, newTabUrl);
  await importDemoData(page);
  await seedHistory(context);
  await openDemoTabs(context);
  await setTheme(page, 'Light');

  await page.waitForTimeout(500);
  const input = page.locator('input.search');
  await input.click();
  await page.waitForTimeout(200);
  await page.keyboard.type('kotlin', { delay: 130 });
  await page.locator('.search-drop').waitFor({ state: 'visible' });
  await page.waitForTimeout(2200);

  await close();
  console.log('search ->', await video.path());
}

async function captureDrag() {
  const { context, newTabUrl, close } = await launchExtension({ video: true, videoDir: outDir });
  const page = context.pages()[0] ?? (await context.newPage());
  const video = page.video();
  await openApp(page, newTabUrl);
  await importDemoData(page);
  await openDemoTabs(context);
  await setTheme(page, 'Light');
  await ensureSectionOpen(page, 'Work');
  await page.waitForTimeout(700);

  const tab = page.locator('li.tab', { hasText: 'GitHub' }).first();
  const collection = page.locator('li.col', {
    has: page.locator('.col-title', { hasText: 'Frontend' }),
  });
  await tab.waitFor({ state: 'visible' });
  await collection.waitFor({ state: 'visible' });

  // Held, not dropped — same as scenarios/02-tabs.mjs, and for the same reason: dropping would
  // actually move the tab. The held state (drop-target outline, dimmed source row) is also the
  // more legible thing to hold a clip on than the instant post-drop state would be.
  await dragHold(page, tab, collection);
  await page.waitForTimeout(2200);

  await close();
  console.log('drag ->', await video.path());
}

async function captureSettings() {
  const { context, newTabUrl, close } = await launchExtension({ video: true, videoDir: outDir });
  const page = context.pages()[0] ?? (await context.newPage());
  const video = page.video();
  await openApp(page, newTabUrl);
  await importDemoData(page);
  await setTheme(page, 'Light');
  await page.waitForTimeout(500);

  await openSettings(page);
  await goToSettingsTab(page, 'Appearance');
  await page.waitForTimeout(500);
  await page.locator('.theme-toggle .theme-opt', { hasText: 'Dark' }).click();
  await page.waitForTimeout(3400);

  await close();
  console.log('settings ->', await video.path());
}

await captureSearch();
await captureDrag();
await captureSettings();
