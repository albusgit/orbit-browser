#!/usr/bin/env node
/*
 * Round-check: loads fixture pages in headless Chromium sized like the Watch6 Classic,
 * injects round.css/round.js the same way Injector.kt does (at document start, with
 * window.__orbit geometry), and checks that no text is cut off by the circle:
 *
 *   1. Horizontally, every visible text line lies inside the inscribed-square column.
 *      Text hidden inside a scroll container (overflow auto/scroll, e.g. wide tables) is
 *      fine: the user can scroll it into view. Text cut by overflow:hidden is a failure.
 *   2. Vertically, the first line is inside the square at scrollTop = 0 and the last line
 *      is inside it at max scroll (document text never sits in the top/bottom inset).
 *   3. Fixed-position text lies inside the square in viewport coordinates.
 *
 * Usage: npm install && node check.mjs        (screenshots go to ./out)
 * Exit code is non-zero if any check fails.
 */
import { chromium } from 'playwright';
import { readFile, mkdir } from 'node:fs/promises';
import { fileURLToPath, pathToFileURL } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const assets = path.resolve(here, '../../app/src/main/assets');
const outDir = path.join(here, 'out');

const SCREENS_PX = [432, 480]; // Watch6 Classic 43mm, 47mm
const DENSITIES = [1.75, 2.0, 2.25]; // the real value is read at runtime on the watch; cover a range
const FIXTURES = ['article.html', 'wide.html', 'links.html'];
const TOLERANCE = 0.75; // CSS px, for sub-pixel rounding

const css = await readFile(path.join(assets, 'round.css'), 'utf8');
const js = await readFile(path.join(assets, 'round.js'), 'utf8');

/** Same geometry and script shape as RoundGeometry.kt + Injector.kt. */
function geometry(px, density) {
  const d = px / density;
  const sq = d / Math.SQRT2;
  return { d, sq, inset: (d - sq) / 2 };
}

function injected(g) {
  return `window.__orbit=${JSON.stringify(g)};\nwindow.__orbitCss=${JSON.stringify(css)};\n${js}`;
}

/** Runs in the page. Returns visible word rects with clipping info, in CSS px. */
function collect() {
  const scrollClip = (el) => {
    // Intersection of the boxes of ancestor scroll containers (overflow auto/scroll).
    let clip = { left: -Infinity, right: Infinity, top: -Infinity, bottom: Infinity };
    for (let a = el; a && a !== document.documentElement; a = a.parentElement) {
      if (a === document.body) break;
      const cs = getComputedStyle(a);
      const scrolls = /(auto|scroll)/.test(cs.overflowX + cs.overflowY);
      if (!scrolls) continue;
      const r = a.getBoundingClientRect();
      clip = {
        left: Math.max(clip.left, r.left),
        right: Math.min(clip.right, r.right),
        top: Math.max(clip.top, r.top),
        bottom: Math.min(clip.bottom, r.bottom),
      };
    }
    return clip;
  };
  const isFixed = (el) => {
    for (let a = el; a; a = a.parentElement) {
      if (getComputedStyle(a).position === 'fixed') return true;
    }
    return false;
  };

  const out = [];
  const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
  for (let n = walker.nextNode(); n; n = walker.nextNode()) {
    if (!n.textContent.trim()) continue;
    const el = n.parentElement;
    const cs = getComputedStyle(el);
    if (cs.visibility === 'hidden' || cs.display === 'none') continue;
    const clip = scrollClip(el);
    const fixed = isFixed(el);
    // Measure each visible word, so hanging whitespace (e.g. in pre-wrap) doesn't count as text.
    const words = /\S+/g;
    for (let m = words.exec(n.textContent); m; m = words.exec(n.textContent)) {
      const range = document.createRange();
      range.setStart(n, m.index);
      range.setEnd(n, m.index + m[0].length);
      for (const r of range.getClientRects()) {
        if (r.width <= 0 || r.height <= 0) continue;
        const v = {
          left: Math.max(r.left, clip.left),
          right: Math.min(r.right, clip.right),
          top: Math.max(r.top, clip.top),
          bottom: Math.min(r.bottom, clip.bottom),
        };
        if (v.right - v.left <= 0 || v.bottom - v.top <= 0) continue; // scrolled out of its container
        out.push({
          text: m[0].slice(0, 40),
          fixed,
          left: v.left,
          right: v.right,
          top: v.top + (fixed ? 0 : window.scrollY),
          bottom: v.bottom + (fixed ? 0 : window.scrollY),
        });
      }
    }
  }
  return {
    lines: out,
    docHeight: document.documentElement.scrollHeight,
    innerWidth: window.innerWidth,
    roundOn: document.documentElement.classList.contains('orbit-round'),
    viewport: document.querySelector('meta[name=viewport]')?.content,
  };
}

function check(result, g) {
  const failures = [];
  const minX = g.inset - TOLERANCE;
  const maxX = g.inset + g.sq + TOLERANCE;
  if (!result.roundOn) failures.push('round.js did not run');
  if (Math.abs(result.innerWidth - g.d) > 1) failures.push(`viewport not device-width: innerWidth=${result.innerWidth}, d=${g.d.toFixed(1)}`);

  for (const l of result.lines) {
    const where = `"${l.text}"`;
    if (l.left < minX || l.right > maxX) {
      failures.push(`${where} outside column x=[${l.left.toFixed(1)}, ${l.right.toFixed(1)}] allowed=[${minX.toFixed(1)}, ${maxX.toFixed(1)}]`);
    }
    if (l.fixed) {
      if (l.top < g.inset - TOLERANCE || l.bottom > g.inset + g.sq + TOLERANCE) {
        failures.push(`${where} fixed text outside square y=[${l.top.toFixed(1)}, ${l.bottom.toFixed(1)}]`);
      }
    } else {
      // In document coordinates: nothing in the top inset (visible at scrollTop 0) and nothing
      // in the bottom inset (visible at max scroll).
      if (l.top < g.inset - TOLERANCE) failures.push(`${where} in top band y=${l.top.toFixed(1)}`);
      if (l.bottom > result.docHeight - g.inset + TOLERANCE) {
        failures.push(`${where} in bottom band y=${l.bottom.toFixed(1)} doc=${result.docHeight}`);
      }
    }
  }
  return failures;
}

/** Masks everything outside the circle and outlines the safe square, for eyeballing. */
async function screenshot(page, g, file) {
  await page.evaluate(({ sq, inset }) => {
    const o = document.createElement('div');
    o.id = 'orbit-check-mask';
    o.style.cssText =
      'position:fixed;inset:0;pointer-events:none;z-index:2147483647;' +
      'background:radial-gradient(circle closest-side, transparent 99.5%, #000 100%);';
    const s = document.createElement('div');
    s.style.cssText = `position:absolute;left:${inset}px;top:${inset}px;width:${sq}px;height:${sq}px;outline:1px dashed rgba(255,0,0,.6);`;
    o.appendChild(s);
    document.documentElement.appendChild(o);
  }, g);
  await page.screenshot({ path: file });
  await page.evaluate(() => document.getElementById('orbit-check-mask')?.remove());
}

// CHROMIUM_PATH lets you point at an existing Chromium instead of Playwright's download.
const browser = await chromium.launch(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {});
await mkdir(outDir, { recursive: true });

let failed = 0;
for (const px of SCREENS_PX) {
  for (const density of DENSITIES) {
    const g = geometry(px, density);
    const context = await browser.newContext({
      viewport: { width: Math.round(g.d), height: Math.round(g.d) },
      deviceScaleFactor: density,
      isMobile: true,
      hasTouch: true,
    });
    await context.addInitScript({ content: injected(g) });
    for (const fixture of FIXTURES) {
      const page = await context.newPage();
      await page.goto(pathToFileURL(path.join(here, 'fixtures', fixture)).href, { waitUntil: 'load' });
      await page.waitForTimeout(50);
      const result = await page.evaluate(collect);
      const failures = check(result, g);
      const label = `${px}px @${density} (d=${g.d.toFixed(1)}css) ${fixture}`;
      if (failures.length) {
        failed++;
        console.log(`FAIL ${label}: ${failures.length} problem(s)`);
        for (const f of failures.slice(0, 8)) console.log(`     - ${f}`);
      } else {
        console.log(`ok   ${label}: ${result.lines.length} word boxes checked`);
      }
      if (density === 2.0) {
        const base = path.join(outDir, `${path.parse(fixture).name}-${px}`);
        await screenshot(page, g, `${base}-top.png`);
        await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
        await page.waitForTimeout(30);
        await screenshot(page, g, `${base}-bottom.png`);
      }
      await page.close();
    }
    await context.close();
  }
}
await browser.close();

if (failed) {
  console.log(`\n${failed} case(s) failed`);
  process.exit(1);
}
console.log('\nAll cases passed: no text clipped by the circle.');
