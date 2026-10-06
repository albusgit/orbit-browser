#!/usr/bin/env node
/*
 * Element-hiding check: runs assets/cosmetic.js in Chromium against a stand-in for Orbit's
 * bridge that answers from the shipped assets/filters/cosmetic.txt (a mirror of Cosmetics.kt),
 * then checks that EasyList's generic, unkeyed and site-specific rules hide what they should,
 * including ads added after load, and leave the article alone.
 *
 * Usage: node cosmetic-check.mjs
 */
import { chromium } from 'playwright';
import { asset } from './lib.mjs';

const cosmeticJs = await asset('cosmetic.js');
const list = await asset('filters/cosmetic.txt');

// Mirror of Cosmetics.parse / siteCss / genericCss (no exceptions needed for these pages).
const keyed = new Map();
const always = [];
const specific = new Map();
let section = '';
for (const line of list.split('\n')) {
  if (!line || line.startsWith('!')) continue;
  if (line === '#generic' || line === '#specific' || line === '#exceptions') { section = line; continue; }
  if (section === '#generic') {
    const [key, , sel] = line.split('\t');
    if (key === '') always.push(sel); else (keyed.get(key) ?? keyed.set(key, []).get(key)).push(sel);
  } else if (section === '#specific') {
    const [domains, sel] = line.split('\t');
    for (const d of domains.split('|')) if (!d.startsWith('~')) (specific.get(d) ?? specific.set(d, []).get(d)).push(sel);
  }
}
const rule = (s) => `${s}{display:none!important}\n`;
function answer(host, msg) {
  let css = '';
  if (msg.first) {
    for (let h = host; h.includes('.'); h = h.slice(h.indexOf('.') + 1)) for (const s of specific.get(h) ?? []) css += rule(s);
    for (const s of always) css += rule(s);
  }
  for (const id of msg.ids) for (const s of keyed.get('#' + id) ?? []) css += rule(s);
  for (const c of msg.classes) for (const s of keyed.get('.' + c) ?? []) css += rule(s);
  return css;
}

const PAGE = `<!doctype html><html><head><title>t</title></head><body>
<article class="story"><h1 id="headline">Tides</h1><p class="lede">The Moon pulls the oceans.</p></article>
<ins class="adsbygoogle" data-ad-client="ca-pub-1" style="display:block;height:90px"></ins>
<div id="div-gpt-ad-123" style="height:250px">gpt</div>
<div class="gc-leaderboard" style="height:90px">site ad</div>
<div id="later"></div>
<script>setTimeout(() => { const d = document.createElement('div'); d.className = 'ad-slot'; d.textContent = 'late ad'; document.getElementById('later').appendChild(d); }, 400);</script>
</body></html>`;

const browser = await chromium.launch();
let failures = 0;
const check = (ok, label) => { console.log(`${ok ? 'ok  ' : 'FAIL'} ${label}`); if (!ok) failures++; };

for (const host of ['www.1001games.com', 'news.example.org']) {
  const page = await browser.newPage();
  const messages = [];
  await page.exposeFunction('__orbitAnswer', (raw) => { const m = JSON.parse(raw); messages.push(m); return answer(host, m); });
  // Stand-in for the WebMessageListener object: postMessage out, replies as 'message' events.
  await page.addInitScript(() => {
    const target = new EventTarget();
    window.orbitBridge = {
      addEventListener: target.addEventListener.bind(target),
      postMessage(raw) { window.__orbitAnswer(raw).then((css) => { if (css) target.dispatchEvent(new MessageEvent('message', { data: css })); }); },
    };
  });
  await page.addInitScript(cosmeticJs);
  await page.route('**/*', (r) => r.fulfill({ body: PAGE, contentType: 'text/html' }));
  await page.goto(`https://${host}/article`);
  await page.waitForTimeout(1200);

  const hidden = (sel) => page.$eval(sel, (el) => getComputedStyle(el).display === 'none');
  check(await hidden('ins.adsbygoogle'), `${host}: AdSense slot hidden (generic, keyed by class)`);
  check(await hidden('#div-gpt-ad-123'), `${host}: GPT slot hidden (generic, unkeyed)`);
  check(await hidden('.ad-slot'), `${host}: ad added after load hidden`);
  check(!(await hidden('article.story')) && !(await hidden('#headline')), `${host}: article left alone`);
  const siteRule = await hidden('.gc-leaderboard');
  check(host.endsWith('1001games.com') ? siteRule : !siteRule, `${host}: site-specific rule only on its site`);
  check(messages[0]?.first === true, `${host}: first message asks for site rules`);
  await page.close();
}

await browser.close();
if (failures) { console.error(`${failures} check(s) failed`); process.exit(1); }
console.log('cosmetic: all checks passed');
