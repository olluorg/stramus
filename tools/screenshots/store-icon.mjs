#!/usr/bin/env node
// Renders store/store-icon-128.png — the 128×128 the Web Store console asks for separately, because
// it does not take that one from the package.
//
//     cd tools/screenshots && node store-icon.mjs
//
// The mark itself is extension/src/jsMain/resources/logo.svg, and it is drawn here exactly as the app
// draws it (`.brand-mark` in index.html): the SVG as a mask, an accent-coloured gradient behind it.
// That is the whole reason this goes through a browser rather than an image library — the gradient's
// two ends are `oklch(from var(--accent) …)`, relative colours only a browser knows how to resolve,
// and a hand-mixed approximation of them would be a second, slightly-wrong brand colour to maintain.
// It lives in this directory, rather than beside the icon-library generator it is otherwise a cousin
// of, for one flat reason: this is where Playwright's Chromium already is.
//
// Not the same file as the extension's own logo-128.png, and not interchangeable with it: that one
// fills its canvas, since Chrome draws it small and inside its own chrome. The store draws this one
// large on a card next to other people's icons, and Google's own guidance is to leave a margin —
// hence GLYPH inside CANVAS below.
import { chromium } from 'playwright';
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = resolve(HERE, '..', '..');
const LOGO = join(REPO_ROOT, 'extension/src/jsMain/resources/logo.svg');
const OUT = join(REPO_ROOT, 'store/store-icon-128.png');

const CANVAS = 128;
/** The mark's own size inside that canvas — the margin the store card wants around it. */
const GLYPH = 96;
/** The default palette's light accent (`ThemePalette.DEFAULT.light.accent`), which is the brand blue. */
const ACCENT = '#3b82f6';
/** `--logo-angle`'s initial value in index.html: the mark at rest, before any hover sweep. */
const ANGLE = '135deg';

const logoDataUri = `data:image/svg+xml;base64,${readFileSync(LOGO).toString('base64')}`;

const page = `<!doctype html>
<meta charset="utf-8">
<style>
  html, body { margin: 0; background: transparent; }
  body { width: ${CANVAS}px; height: ${CANVAS}px; display: grid; place-items: center; }
  .mark {
    width: ${GLYPH}px; height: ${GLYPH}px;
    --accent: ${ACCENT};
    --logo-hi: var(--accent);
    --logo-lo: var(--accent);
    background: linear-gradient(${ANGLE}, var(--logo-hi), var(--accent) 50%, var(--logo-lo));
    -webkit-mask: url("${logoDataUri}") center / contain no-repeat;
    mask: url("${logoDataUri}") center / contain no-repeat;
  }
  @supports (color: oklch(from red l c h)) {
    .mark {
      --logo-hi: oklch(from var(--accent) calc(l + 0.10) c calc(h - 30));
      --logo-lo: oklch(from var(--accent) calc(l - 0.05) calc(c * 1.05) calc(h + 34));
    }
  }
</style>
<div class="mark"></div>`;

const browser = await chromium.launch();
try {
  const tab = await browser.newPage({
    viewport: { width: CANVAS, height: CANVAS },
    deviceScaleFactor: 1,
  });
  await tab.setContent(page, { waitUntil: 'load' });
  // omitBackground keeps the corners transparent: the store rounds and shades the tile itself, and a
  // white square baked in here would show as a white square on a dark card.
  const png = await tab.screenshot({ omitBackground: true });
  writeFileSync(OUT, png);
  console.log(`wrote ${OUT} (${CANVAS}×${CANVAS}, glyph ${GLYPH}px, accent ${ACCENT})`);
} finally {
  await browser.close();
}
