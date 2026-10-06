#!/usr/bin/env node
/*
 * Reader check: runs the real pipeline (round.js → Readability → extract.js → reader.html
 * → reader.js) on fixture articles in headless Chromium sized like the watch, and checks:
 *
 *   1. Every word on a text page lies inside the circle (all four corners of its box).
 *   2. Images sit inside the inscribed square on their own page; table/code boxes too.
 *   3. Nothing is lost or reordered: the words across all pages equal the article's words.
 *   4. Changing typography re-paginates and keeps the reader on the same word.
 *   5. Speed with the CPU throttled 6× (roughly the watch's two A55 cores): time to the
 *      first page, full pagination, and page-turn latency.
 *
 * Usage: node reader-check.mjs   (screenshots of the first pages go to ./out)
 */
import { chromium } from 'playwright';
import { readFile, mkdir } from 'node:fs/promises';
import path from 'node:path';
import { here, SCREENS_PX, DENSITIES, geometry, roundScript, extractScript, readerHtml } from './lib.mjs';

const FIXTURES = ['long-article.html', 'article.html'];
const ORIGIN = 'https://fixture.test';
const STYLE = { fontSize: 15, lineHeight: 1.4, serif: true };
const BIG = { fontSize: 19, lineHeight: 1.55, serif: false };
const THROTTLE = 6;
const outDir = path.join(here, 'out');
await mkdir(outDir, { recursive: true });

const browser = await chromium.launch(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {});
let failed = 0;
const perf = [];

/** In-page: measure the current page. */
function measurePage() {
  const d = window.__orbitReader.d;
  const sq = window.__orbitReader.sq;
  const inset = window.__orbitReader.inset;
  const c = d / 2;
  const page = document.querySelector('.orbit-page.is-current');
  const problems = [];
  const words = [];
  const kind = page.querySelector('.orbit-flow') ? 'text' : page.querySelector('.orbit-box') ? 'box' : 'image';
  const container = page.querySelector('.orbit-flow, .orbit-square');
  const walker = document.createTreeWalker(container, NodeFilter.SHOW_TEXT);
  for (let n = walker.nextNode(); n; n = walker.nextNode()) {
    if (n.parentElement.closest('.orbit-caption')) continue;
    const re = /\S+/g;
    for (let m = re.exec(n.data); m; m = re.exec(n.data)) {
      words.push(m[0]);
      if (kind !== 'text') continue;
      const r = document.createRange();
      r.setStart(n, m.index);
      r.setEnd(n, m.index + m[0].length);
      for (const b of r.getClientRects()) {
        if (b.width <= 0 || b.height <= 0) continue;
        const far = Math.max(
          Math.hypot(b.left - c, b.top - c), Math.hypot(b.right - c, b.top - c),
          Math.hypot(b.left - c, b.bottom - c), Math.hypot(b.right - c, b.bottom - c));
        if (far > c + 0.5) problems.push(`"${m[0]}" outside circle by ${(far - c).toFixed(1)}px`);
      }
    }
  }
  if (kind !== 'text') {
    const inSquare = (b) => b.left >= inset - 0.5 && b.top >= inset - 0.5 && b.right <= inset + sq + 0.5 && b.bottom <= inset + sq + 0.5;
    const box = container.getBoundingClientRect();
    if (!inSquare(box)) problems.push(`${kind} box outside square`);
    const img = container.querySelector('img');
    if (img && !inSquare(img.getBoundingClientRect())) problems.push('image outside square');
  }
  return { kind, words, problems };
}

/** In-page: the article's words in reading order, as the reader should present them. */
function expectedWords({ content, title, meta }) {
  const doc = new DOMParser().parseFromString(`<div>${content}</div>`, 'text/html');
  const out = [...title.split(/\s+/), ...meta.split(/\s+/)].filter(Boolean);
  const visit = (node) => {
    if (node.nodeType === 3) { out.push(...node.data.split(/\s+/).filter(Boolean)); return; }
    if (node.nodeType !== 1) return;
    if (node.tagName === 'HR') { out.push('⁂'); return; }
    if (node.tagName === 'FIGURE' && node.querySelector('img') && !node.querySelector('table,pre')) return;
    node.childNodes.forEach(visit);
  };
  visit(doc.body.firstChild);
  return out;
}

const wait = (ms) => new Promise((r) => setTimeout(r, ms));

for (const fixture of FIXTURES) {
  const html = await readFile(path.join(here, 'fixtures', fixture), 'utf8');
  for (const px of SCREENS_PX) {
    for (const density of DENSITIES) {
      const g = geometry(px, density);
      const label = `${px}px @${density} ${fixture}`;
      const context = await browser.newContext({
        viewport: { width: Math.round(g.d), height: Math.round(g.d) },
        deviceScaleFactor: density, isMobile: true, hasTouch: true, reducedMotion: 'reduce',
      });
      const messages = [];
      await context.exposeBinding('__orbitPost', (_src, s) => messages.push(JSON.parse(s)));
      await context.addInitScript({ content: 'window.orbitBridge={postMessage:function(s){window.__orbitPost(s)}};' });
      await context.addInitScript({ content: await roundScript(g) });
      let readerPage = null;
      await context.route(`${ORIGIN}/**`, (route) =>
        route.fulfill({ contentType: 'text/html; charset=utf-8', body: readerPage ?? html }));

      const page = await context.newPage();
      const problems = [];
      try {
        await page.goto(`${ORIGIN}/article`, { waitUntil: 'load' });
        await page.evaluate(await extractScript(false));
        await wait(50);
        const article = messages.find((m) => m.type === 'article');
        if (!article || !article.ok) throw new Error(`no article extracted: ${JSON.stringify(article)}`);

        const cdp = await context.newCDPSession(page);
        await cdp.send('Emulation.setCPUThrottlingRate', { rate: THROTTLE });

        readerPage = await readerHtml(article, g, STYLE, null);
        messages.length = 0;
        await page.goto('about:blank');
        const t0 = Date.now();
        await page.goto(`${ORIGIN}/article#orbit-reader`, { waitUntil: 'domcontentloaded' });
        await page.waitForFunction(() => window.orbitReader && document.querySelector('.orbit-page.is-current'));
        const tFirst = Date.now() - t0;
        await page.waitForFunction(() => window.orbitReader.state().done, null, { timeout: 120000 });
        const tAll = Date.now() - t0;
        const total = await page.evaluate(() => window.orbitReader.state().total);

        // Turn latency: from the call to the next painted frame.
        const turns = await page.evaluate(async () => {
          const times = [];
          for (let i = 0; i < 12; i++) {
            const s = performance.now();
            window.orbitReader.turn(1);
            await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));
            times.push(performance.now() - s);
          }
          window.orbitReader.goTo(0);
          return times;
        });
        await cdp.send('Emulation.setCPUThrottlingRate', { rate: 1 });
        perf.push({ label, total, tFirst, tAll, turnAvg: turns.reduce((a, b) => a + b, 0) / turns.length, turnMax: Math.max(...turns) });

        // Walk every page.
        const seen = [];
        const kinds = { text: 0, image: 0, box: 0 };
        for (let i = 0; i < total; i++) {
          await page.evaluate((k) => window.orbitReader.goTo(k), i);
          await page.waitForFunction(() => [...document.querySelectorAll('.orbit-square img')].every((im) => im.complete || !im.closest('.is-current')));
          const m = await page.evaluate(measurePage);
          kinds[m.kind]++;
          seen.push(...m.words);
          for (const p of m.problems) problems.push(`page ${i + 1}: ${p}`);
          if (density === 2.0 && i < 4) await page.screenshot({ path: path.join(outDir, `reader-${path.parse(fixture).name}-${px}-p${i + 1}.png`) });
        }

        const minutes = Math.max(1, Math.floor(article.words / 220));
        const meta = [...new Set([article.byline.trim(), article.siteName.trim(), `${minutes} min read`].filter(Boolean))].join(' · ');
        const expected = await page.evaluate(expectedWords, { content: article.content, title: article.title, meta });
        const n = Math.max(expected.length, seen.length);
        for (let i = 0; i < n; i++) {
          if (expected[i] !== seen[i]) {
            problems.push(`word ${i}: expected "${expected[i]}" got "${seen[i]}" (${expected.length} vs ${seen.length} words)`);
            break;
          }
        }

        // Re-pagination keeps the same word on screen.
        const mid = Math.floor(total / 2);
        await page.evaluate((k) => window.orbitReader.goTo(k), mid);
        const before = await page.evaluate(() => window.orbitReader.state().anchor);
        await page.evaluate((s) => window.orbitReader.setStyle(s), BIG);
        const after = await page.evaluate(() => window.orbitReader.state());
        await page.evaluate((k) => window.orbitReader.goTo(k), after.page + 1);
        const next = await page.evaluate(() => window.orbitReader.state());
        const le = (a, b) => a.u < b.u || (a.u === b.u && a.w <= b.w);
        const holds = le(after.anchor, before) && (next.page === after.page || !le(next.anchor, before));
        if (!holds) problems.push(`re-pagination lost the position: ${JSON.stringify({ before, after: after.anchor, next: next.anchor })}`);

        const summary = `${total} pages (${kinds.text} text, ${kinds.image} image, ${kinds.box} box), ${seen.length} words`;
        if (problems.length) {
          failed++;
          console.log(`FAIL ${label}: ${summary}`);
          for (const p of problems.slice(0, 10)) console.log(`     - ${p}`);
        } else {
          console.log(`ok   ${label}: ${summary}`);
        }
      } catch (e) {
        failed++;
        console.log(`FAIL ${label}: ${e.message}`);
      }
      await context.close();
    }
  }
}
await browser.close();

console.log(`\nSpeed with CPU throttled ${THROTTLE}x (first page / all pages / turn avg, max):`);
for (const p of perf) {
  console.log(`  ${p.label.padEnd(36)} ${String(p.total).padStart(3)} pages  ${String(p.tFirst).padStart(5)} ms / ${String(p.tAll).padStart(5)} ms   turn ${p.turnAvg.toFixed(1)} ms, ${p.turnMax.toFixed(1)} ms`);
  if (p.turnAvg > 100) { failed++; console.log('     - page turns too slow'); }
}

if (failed) {
  console.log(`\n${failed} problem(s)`);
  process.exit(1);
}
console.log('\nReader: all pages inside the circle, nothing lost, position kept.');
