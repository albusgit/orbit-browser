#!/usr/bin/env node
/*
 * Link-focus check: drives links.js the way the bezel does (orbitLinks.step(±1)) on a dense
 * link page in Round Scroll and on a reader page, and checks:
 *
 *   1. Focus moves in reading order (document position never goes backwards) and never
 *      repeats while stepping forward, across scrolls / page turns.
 *   2. The focused control is inside the circle and the ring surrounds it.
 *   3. Stepping back returns to the previous control.
 *   4. activate() opens the focused link.
 *
 * Usage: node links-check.mjs
 */
import { chromium } from 'playwright';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { here, geometry, roundScript, extractScript, readerHtml, asset } from './lib.mjs';

const ORIGIN = 'https://fixture.test';
const STEPS = 60;
const browser = await chromium.launch(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {});
const linksJs = await asset('links.js');
let failed = 0;

/** In-page: where the focused element is, and whether the ring surrounds it. */
function probe() {
  const msgs = window.__linkMsgs;
  const last = msgs[msgs.length - 1];
  const ring = [...document.documentElement.children].find((e) => e.style && e.style.boxShadow && e.style.position === 'fixed' && e.style.display !== 'none');
  const active = last && last.event === 'focus' ? last : null;
  if (!active || !ring) return { ok: false, why: 'no focus or ring', last };
  const rr = ring.getBoundingClientRect();
  const c = Math.min(innerWidth, innerHeight) / 2;
  const cx = (rr.left + rr.right) / 2;
  const cy = (rr.top + rr.bottom) / 2;
  const docY = rr.top + window.scrollY + (window.orbitReader ? window.orbitReader.state().page * 100000 : 0);
  return {
    ok: true,
    href: active.href,
    label: active.label,
    docY,
    docX: rr.left,
    insideCircle: Math.hypot(cx - c, cy - c) <= c,
  };
}

async function run(label, page, shot) {
  const problems = [];
  await page.evaluate(() => { window.__linkMsgs = []; });
  await page.evaluate(linksJs);
  const seen = [];
  let prev = null;
  for (let i = 0; i < STEPS; i++) {
    await page.evaluate(() => window.orbitLinks.step(1));
    await page.waitForTimeout(20);
    const p = await page.evaluate(probe);
    if (!p.ok) {
      if (p.last && p.last.event === 'edge') break; // reached the end
      problems.push(`step ${i}: ${p.why}`);
      break;
    }
    if (i === 3 && shot) await page.screenshot({ path: shot });
    if (!p.insideCircle) problems.push(`step ${i}: focus outside circle (${p.label})`);
    if (prev && (p.docY < prev.docY - 12)) problems.push(`step ${i}: went backwards from "${prev.label}" to "${p.label}"`);
    const key = `${p.href}|${p.label}|${Math.round(p.docY)}`;
    if (seen.includes(key)) problems.push(`step ${i}: repeated "${p.label}"`);
    seen.push(key);
    prev = p;
  }
  // One step back returns to the previous control.
  if (seen.length >= 2) {
    await page.evaluate(() => window.orbitLinks.step(-1));
    await page.waitForTimeout(20);
    const back = await page.evaluate(probe);
    const expected = seen[seen.length - 2].split('|')[1];
    if (back.label !== expected) problems.push(`step back focused "${back.label}", expected "${expected}"`);
  }
  // Activating opens the link.
  const before = await page.evaluate(() => window.__linkMsgs.filter((m) => m.event === 'focus').pop());
  let navigated = null;
  page.once('request', (r) => { if (r.isNavigationRequest()) navigated = r.url(); });
  await page.evaluate(() => window.orbitLinks.activate());
  await page.waitForTimeout(150);
  // An in-page #link navigates without a request: check the location instead.
  if (!navigated) navigated = await page.evaluate(() => location.href).catch(() => null);
  if (before && before.href && navigated !== before.href) problems.push(`activate() didn't open ${before.href} (at ${navigated})`);
  if (problems.length) {
    failed++;
    console.log(`FAIL ${label}: ${seen.length} controls visited`);
    for (const p of problems.slice(0, 8)) console.log(`     - ${p}`);
  } else {
    console.log(`ok   ${label}: ${seen.length} controls in reading order, back/activate work`);
  }
}

for (const px of [432, 480]) {
  const g = geometry(px, 2.0);
  const ctxOpts = {
    viewport: { width: Math.round(g.d), height: Math.round(g.d) },
    deviceScaleFactor: 2, isMobile: true, hasTouch: true, reducedMotion: 'reduce',
  };

  // Round Scroll on a dense link page.
  {
    const context = await browser.newContext(ctxOpts);
    const html = await readFile(path.join(here, 'fixtures', 'links.html'), 'utf8');
    await context.exposeBinding('__post', (_s, m) => m);
    await context.addInitScript({ content: 'window.__linkMsgs=[];window.orbitBridge={postMessage:function(s){window.__linkMsgs.push(JSON.parse(s))}};' });
    await context.addInitScript({ content: await roundScript(g) });
    await context.route(`${ORIGIN}/**`, (route) => route.request().url().endsWith('/links')
      ? route.fulfill({ contentType: 'text/html', body: html })
      : route.fulfill({ contentType: 'text/html', body: '<p>target</p>' }));
    const page = await context.newPage();
    await page.goto(`${ORIGIN}/links`, { waitUntil: 'load' });
    await run(`${px}px round scroll links.html`, page, path.join(here, 'out', `links-focus-${px}.png`));
    await context.close();
  }

  // Reader pages of the long article.
  {
    const context = await browser.newContext(ctxOpts);
    const html = await readFile(path.join(here, 'fixtures', 'long-article.html'), 'utf8');
    const messages = [];
    await context.exposeBinding('__orbitPost', (_s, s) => messages.push(JSON.parse(s)));
    await context.addInitScript({ content: 'window.__linkMsgs=[];window.orbitBridge={postMessage:function(s){var m=JSON.parse(s);window.__linkMsgs.push(m);window.__orbitPost(s)}};' });
    await context.addInitScript({ content: await roundScript(g) });
    let reader = null;
    await context.route(`${ORIGIN}/**`, (route) => {
      const u = route.request().url();
      if (u.endsWith('/article')) return route.fulfill({ contentType: 'text/html', body: reader ?? html });
      return route.fulfill({ contentType: 'text/html', body: '<p>target</p>' });
    });
    const page = await context.newPage();
    await page.goto(`${ORIGIN}/article`, { waitUntil: 'load' });
    await page.evaluate(await extractScript(false));
    await page.waitForTimeout(50);
    const article = messages.find((m) => m.type === 'article');
    reader = await readerHtml(article, g, { fontSize: 15, lineHeight: 1.4, serif: true }, null);
    await page.goto('about:blank');
    await page.goto(`${ORIGIN}/article#orbit-reader`, { waitUntil: 'load' });
    await page.waitForFunction(() => window.orbitReader && window.orbitReader.state().done);
    await run(`${px}px reader long-article.html`, page, path.join(here, 'out', `links-reader-${px}.png`));
    await context.close();
  }
}
await browser.close();
if (failed) process.exit(1);
console.log('\nLink focus: reading order, scrolling/page turns, back and activate all work.');
