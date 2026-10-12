#!/usr/bin/env node
/*
 * Orbit for Claude check: runs claude.js (claude/src/main/assets/extensions/claude) on a
 * claude.ai-shaped fixture at watch size, with a stand-in for the extension messaging, and
 * checks the interface layer's contract with the app:
 *
 *   1. It reports the chat (composer, native, not busy) and moves the message box off screen,
 *      padding the conversation by the app's button height.
 *   2. send puts the text in the editor and sends it: the message appears, the result is ok,
 *      and busy goes true then false around the answer.
 *   3. stop clicks the page's stop button while it answers.
 *   4. scroll moves the conversation and reports the top edge.
 *   5. A send that doesn't go through (unknown markup) is reported, and the page gets its
 *      own message box back.
 *   6. In a popup window (window.opener set) it does nothing.
 *
 * Usage: node claude-check.mjs
 */
import { chromium } from 'playwright';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { here } from './lib.mjs';

const script = await readFile(path.resolve(here, '../../claude/src/main/assets/extensions/claude/claude.js'), 'utf8');
const fixture = await readFile(path.join(here, 'fixtures/claude.html'), 'utf8');
const BOTTOM = 84;

/** The extension API claude.js uses, recording what it sends to the app. */
const stub = `
  window.__toApp = [];
  window.browser = {
    runtime: {
      sendMessage(m) {
        window.__toApp.push(JSON.parse(JSON.stringify(m)));
        return Promise.resolve(m.type === 'hello' ? { cmd: 'layout', bottom: ${BOTTOM} } : undefined);
      },
      onMessage: { addListener(f) { window.__fromApp = f; } },
    },
  };`;

const browser = await chromium.launch(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {});
let failed = 0;

function check(ok, label, detail) {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${label}${!ok && detail !== undefined ? ` — ${JSON.stringify(detail)}` : ''}`);
  if (!ok) failed++;
}

async function open(query = '', popup = false) {
  const ctx = await browser.newContext({ viewport: { width: 240, height: 240 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true });
  await ctx.route('**/*', (r) => r.fulfill({ contentType: 'text/html', body: fixture }));
  await ctx.addInitScript(`${stub}\n${popup ? 'window.opener = window;' : ''}\n${script}`);
  const page = await ctx.newPage();
  await page.goto(`https://claude.ai/chat/abc${query}`, { waitUntil: 'load' });
  return { ctx, page };
}

const lastState = (page) => page.evaluate(() => [...window.__toApp].reverse().find((m) => m.type === 'state'));
const results = (page, cmd) => page.evaluate((c) => window.__toApp.filter((m) => m.type === 'result' && m.cmd === c), cmd);
const states = (page) => page.evaluate(() => window.__toApp.filter((m) => m.type === 'state'));
const app = (page, msg) => page.evaluate((m) => window.__fromApp(m), msg);

// 1–4: the working chat.
{
  const { ctx, page } = await open();
  await page.waitForTimeout(400);
  const s = await lastState(page);
  check(s && s.composer && s.native && !s.busy && s.path === '/chat/abc', 'reports the chat', s);
  const box = await page.evaluate(() => {
    const f = document.querySelector('fieldset');
    const sc = document.getElementById('scroller');
    return {
      marked: f.hasAttribute('data-orbit-composer'),
      left: f.getBoundingClientRect().left,
      scroller: sc.hasAttribute('data-orbit-scroller'),
      padding: getComputedStyle(sc).paddingBottom,
    };
  });
  check(box.marked && box.left < -1000, 'moves the message box off screen', box);
  check(box.scroller && box.padding === `${BOTTOM}px`, 'pads the conversation by the buttons', box);

  await app(page, { cmd: 'send', text: 'How do tides work?', id: 7 });
  await page.waitForTimeout(300);
  const sent = await page.evaluate(() => [...document.querySelectorAll('.msg.user')].map((p) => p.textContent));
  check(sent.length === 1 && sent[0] === 'How do tides work?', 'sends the message', sent);
  const busy = await lastState(page);
  check(busy && busy.busy, 'reports busy while answering', busy);
  await page.waitForTimeout(1800);
  const r = await results(page, 'send');
  check(r.length === 1 && r[0].ok && r[0].id === 7, 'send result ok', r);
  const done = await lastState(page);
  check(done && !done.busy, 'reports done after the answer', done);
  const editorFocused = await page.evaluate(() => document.activeElement && document.activeElement.classList.contains('ProseMirror'));
  check(!editorFocused, 'leaves the editor unfocused (no soft keyboard)');

  await app(page, { cmd: 'send', text: 'Again', id: 8 });
  await page.waitForTimeout(300);
  await app(page, { cmd: 'stop', id: 9 });
  await page.waitForTimeout(100);
  const stopped = await page.evaluate(() => window.__stopped === true);
  const sr = await results(page, 'stop');
  check(stopped && sr.length === 1 && sr[0].ok, 'stop clicks the page\'s stop button', sr);
  await page.waitForTimeout(1600);

  await page.evaluate(() => { document.getElementById('scroller').scrollTop = 0; window.__toApp.length = 0; });
  await app(page, { cmd: 'scroll', dy: -60 });
  await app(page, { cmd: 'scroll', dy: 120 });
  await page.waitForTimeout(600);
  const scrolled = await page.evaluate(() => ({ top: document.getElementById('scroller').scrollTop, msgs: window.__toApp.filter((m) => m.type === 'scrolled') }));
  check(scrolled.msgs.length === 2 && scrolled.msgs[0].edge === true && scrolled.msgs[1].edge === false, 'reports the top edge', scrolled.msgs);
  check(scrolled.top > 60, 'bezel scrolls the conversation', scrolled.top);
  await ctx.close();
}

// 5: markup claude.js doesn't know.
{
  const { ctx, page } = await open('?broken');
  await page.waitForTimeout(300);
  await app(page, { cmd: 'send', text: 'Hello', id: 1 });
  await page.waitForTimeout(3000 + 1500 + 600);
  const r = await results(page, 'send');
  check(r.length === 1 && !r[0].ok && r[0].reason === 'not-sent', 'reports a send that didn\'t go through', r);
  const back = await page.evaluate(() => ({
    left: document.querySelector('fieldset').getBoundingClientRect().left,
    cls: document.documentElement.classList.contains('orbit-native'),
  }));
  const s = await lastState(page);
  check(back.left >= 0 && !back.cls && s && !s.native, 'gives the page its message box back', { back, s });
  await ctx.close();
}

// 6: popup windows (Google's sign-in) are left alone.
{
  const { ctx, page } = await open('', true);
  await page.waitForTimeout(400);
  const all = await states(page);
  const marked = await page.evaluate(() => document.querySelector('fieldset').hasAttribute('data-orbit-composer'));
  check(all.length === 0 && !marked, 'does nothing in a popup', { all, marked });
  await ctx.close();
}

await browser.close();
console.log(failed ? `\n${failed} check(s) failed` : '\nall checks passed');
process.exit(failed ? 1 : 0);
