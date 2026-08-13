#!/usr/bin/env node
// CLI: node capture.mjs [--shot id ...] [--all] [--store] [--video] [--headless] [--out dir]
// With no selector flag, captures the five store shots (the previous default of
// store/screenshots.md's manual procedure).
import { parseArgs } from 'node:util';
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { launchExtension, REPO_ROOT } from './lib/launch.mjs';
import { SkipShot } from './lib/dom.mjs';
import { SHOTS, STORE_SHOT_IDS } from './shots.mjs';

const { values } = parseArgs({
  options: {
    shot: { type: 'string', multiple: true },
    all: { type: 'boolean', default: false },
    store: { type: 'boolean', default: false },
    video: { type: 'boolean', default: false },
    headless: { type: 'boolean', default: false },
    out: { type: 'string', default: join(REPO_ROOT, 'tools/screenshots/output') },
  },
});

function selectShots() {
  if (values.all) return SHOTS;
  if (values.shot?.length) {
    const unknown = values.shot.filter((id) => !SHOTS.some((s) => s.id === id));
    if (unknown.length) {
      throw new Error(`Unknown shot id(s): ${unknown.join(', ')}. Known: ${SHOTS.map((s) => s.id).join(', ')}`);
    }
    return SHOTS.filter((s) => values.shot.includes(s.id));
  }
  if (values.store) return SHOTS.filter((s) => STORE_SHOT_IDS.includes(s.id));
  return SHOTS.filter((s) => STORE_SHOT_IDS.includes(s.id));
}

const selected = selectShots();
const outDir = values.out;
const videoDir = join(outDir, 'video');
mkdirSync(outDir, { recursive: true });

const results = [];

for (const shot of selected) {
  // A fresh profile/browser per shot — cheap here (a handful of shots, run on demand or in CI, not
  // per commit) and it rules out one shot's state (dark theme, an open modal) leaking into the next,
  // the same reason store/screenshots.md insists on a throwaway profile for the manual procedure.
  const { context, newTabUrl, close } = await launchExtension({
    headless: values.headless,
    video: values.video,
    videoDir,
  });
  const page = context.pages()[0] ?? (await context.newPage());
  try {
    const mod = await import(shot.module);
    await mod.run({ page, context, newTabUrl });
    const outPath = join(outDir, shot.output);
    await page.screenshot({ path: outPath });
    results.push({ id: shot.id, status: 'ok', path: outPath });
    console.log(`✔ ${shot.id} -> ${outPath}`);
  } catch (err) {
    if (err instanceof SkipShot) {
      results.push({ id: shot.id, status: 'skipped', reason: err.message });
      console.log(`… ${shot.id} skipped: ${err.message}`);
    } else {
      results.push({ id: shot.id, status: 'failed', reason: err?.stack ?? String(err) });
      console.error(`✘ ${shot.id} failed:`, err);
    }
  } finally {
    await close();
  }
}

const ok = results.filter((r) => r.status === 'ok').length;
const skipped = results.filter((r) => r.status === 'skipped').length;
const failed = results.filter((r) => r.status === 'failed').length;
console.log(`\n${results.length} shot(s): ${ok} ok, ${skipped} skipped, ${failed} failed.`);
if (failed) process.exitCode = 1;
