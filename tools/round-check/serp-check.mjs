#!/usr/bin/env node
/*
 * Search-results check: runs assets/serp.js on fixture pages served at the engines' real
 * URLs (so location.hostname matches), applies the same link decoding as Serp.kt, and checks
 * that organic results come out in order with titles and snippets, ads/"People also ask"/
 * engine-internal links excluded, and that a CAPTCHA page yields nothing (so the app falls
 * back to showing the page). Then a live Bing run (Google and DuckDuckGo block this machine).
 *
 * Usage: node serp-check.mjs [--no-live]
 */
import { chromium } from 'playwright';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { here, asset } from './lib.mjs';

const serpJs = await asset('serp.js');
const UA = 'Mozilla/5.0 (Linux; Android 14; SM-R960) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36';

/** Mirror of Serp.decodeResultUrl. */
function decode(href) {
  let u;
  try { u = new URL(href); } catch { return null; }
  const host = u.hostname.toLowerCase();
  const isGoogle = /^(www\.)?google\.[a-z]{2,3}(\.[a-z]{2})?$/.test(host);
  let target = href;
  if (isGoogle && u.pathname === '/url') target = u.searchParams.get('q') || u.searchParams.get('url');
  else if (host.endsWith('duckduckgo.com') && u.pathname.startsWith('/l/')) target = u.searchParams.get('uddg');
  else if (host.endsWith('bing.com') && u.pathname.startsWith('/ck/')) {
    const v = u.searchParams.get('u') || '';
    target = v.startsWith('a1') ? Buffer.from(v.slice(2), 'base64url').toString('utf8') : null;
  }
  if (!target) return null;
  let t;
  try { t = new URL(target); } catch { return null; }
  if (!/^https?:$/.test(t.protocol)) return null;
  const th = t.hostname.toLowerCase();
  if (/^(www\.)?google\./.test(th) || th.endsWith('duckduckgo.com') || th === 'bing.com' || th.endsWith('.bing.com')) return null;
  return target;
}

const CASES = [
  {
    name: 'google (mobile + desktop markup, ads, PAA, featured snippet)',
    url: 'https://www.google.com/search?q=how+do+tides+work',
    fixture: 'google.html',
    expect: [
      'https://oceanservice.noaa.gov/facts/tides.html',
      'https://en.wikipedia.org/wiki/Tide',
      'https://www.bbc.co.uk/bitesize/topics/tides',
      'https://www.ocean.si.edu/planet-ocean/tides-currents/tides',
    ],
    answer: true,
    snippets: true,
  },
  { name: 'google CAPTCHA page', url: 'https://www.google.com/sorry/index?q=x', fixture: 'google-sorry.html', expect: [] },
  {
    name: 'duckduckgo html (with ad)',
    url: 'https://html.duckduckgo.com/html/?q=how+do+tides+work',
    fixture: 'ddg.html',
    expect: ['https://oceanservice.noaa.gov/facts/tides.html', 'https://en.wikipedia.org/wiki/Tide'],
    snippets: true,
  },
  {
    name: 'bing (with ad)',
    url: 'https://www.bing.com/search?q=how+do+tides+work',
    fixture: 'bing.html',
    expect: ['https://oceanservice.noaa.gov/facts/tides.html', 'https://en.wikipedia.org/wiki/Tide'],
    snippets: true,
  },
];

const browser = await chromium.launch(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {});
let failed = 0;

async function extract(page) {
  const got = [];
  await page.exposeFunction('__serpPost', (s) => got.push(JSON.parse(s)));
  await page.addInitScript(() => { window.orbitBridge = { postMessage: (s) => window.__serpPost(s) }; });
  return got;
}

for (const c of CASES) {
  const ctx = await browser.newContext({ viewport: { width: 240, height: 240 }, isMobile: true, userAgent: UA });
  const html = await readFile(path.join(here, 'fixtures', 'serp', c.fixture), 'utf8');
  await ctx.route('**/*', (r) => r.request().isNavigationRequest()
    ? r.fulfill({ contentType: 'text/html; charset=utf-8', body: html })
    : r.fulfill({ status: 204, body: '' }));
  const page = await ctx.newPage();
  const got = await extract(page);
  await page.goto(c.url, { waitUntil: 'load' });
  await page.evaluate(serpJs);
  await page.waitForTimeout(50);
  const msg = got[0] || { results: [] };
  const urls = msg.results.map((r) => decode(r.href)).filter(Boolean);
  const problems = [];
  if (JSON.stringify(urls) !== JSON.stringify(c.expect)) problems.push(`results ${JSON.stringify(urls)} != ${JSON.stringify(c.expect)}`);
  if (c.answer && !msg.answer) problems.push('featured snippet not found');
  if (c.snippets) {
    for (const r of msg.results.filter((r) => decode(r.href))) {
      if (!r.title) problems.push(`missing title for ${r.href}`);
      if (!r.snippet || r.snippet.length < 20) problems.push(`missing snippet for "${r.title}"`);
      if (r.snippet && /Sponsored|People also ask/.test(r.snippet)) problems.push(`junk in snippet "${r.snippet.slice(0, 40)}"`);
    }
  }
  if (problems.length) {
    failed++;
    console.log(`FAIL ${c.name}`);
    problems.forEach((p) => console.log(`     - ${p}`));
  } else {
    console.log(`ok   ${c.name}: ${urls.length} results${msg.answer ? ' + answer' : ''}`);
    for (const r of msg.results.filter((r) => decode(r.href))) console.log(`       · ${r.title.slice(0, 50)} — ${r.snippet.slice(0, 50)}…`);
  }
  await ctx.close();
}

if (!process.argv.includes('--no-live')) {
  const ctx = await browser.newContext({ viewport: { width: 240, height: 240 }, isMobile: true, userAgent: UA, locale: 'en-US' });
  const page = await ctx.newPage();
  const got = await extract(page);
  try {
    await page.goto('https://www.bing.com/search?q=how+do+tides+work', { waitUntil: 'domcontentloaded', timeout: 30000 });
    await page.waitForTimeout(2500);
    await page.evaluate(serpJs);
    await page.waitForTimeout(100);
    const results = (got[0]?.results || []).filter((r) => decode(r.href));
    if (results.length >= 3 && results.every((r) => r.title)) {
      console.log(`ok   live bing: ${results.length} results`);
      results.slice(0, 4).forEach((r) => console.log(`       · ${r.title.slice(0, 50)} — ${decode(r.href).slice(0, 50)}`));
    } else {
      failed++;
      console.log(`FAIL live bing: ${results.length} results`, JSON.stringify(got[0]).slice(0, 300));
    }
  } catch (e) {
    console.log(`skip live bing: ${e.message.slice(0, 80)}`);
  }
  await ctx.close();
}

await browser.close();
if (failed) process.exit(1);
console.log('\nSearch results: organic results in order, ads and internal links excluded, CAPTCHA falls back.');
