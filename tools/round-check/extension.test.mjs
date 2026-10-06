// node --test tools/round-check/extension.test.mjs
// Unit tests for the Orbit extension's hub (background.js) and page link (bridge.js), run
// against a fake `browser` object. Gecko itself is only exercised on the watch.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const dir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../app/src/main/assets/extensions/orbit');
const source = (f) => readFileSync(path.join(dir, f), 'utf8');

function event() {
  const listeners = [];
  return { addListener: (f) => listeners.push(f), listeners, fire: (...a) => listeners.map((f) => f(...a)) };
}

/** A fake WebExtension API recording what background.js does. */
function fakeBrowser() {
  const log = { app: [], executed: [], registered: [], unregistered: 0, toTab: [], runtimeSent: [] };
  const appPort = { onMessage: event(), onDisconnect: event(), postMessage: (m) => log.app.push(m) };
  const b = {
    log,
    appPort,
    runtime: {
      connectNative: () => appPort,
      onMessage: event(),
      sendMessage: async (m) => { log.runtimeSent.push(m); },
      getURL: (p) => `moz-extension://x/${p}`,
    },
    tabs: {
      query: async () => [{ id: 7 }],
      sendMessage: async (id, m, o) => { log.toTab.push({ id, m, o }); },
      executeScript: async (id, o) => { log.executed.push({ id, ...o }); },
    },
    contentScripts: {
      register: async (o) => { log.registered.push(o); return { unregister: async () => { log.unregistered++; } }; },
    },
    webRequest: { onBeforeRequest: event() },
  };
  return b;
}

function loadBackground() {
  const browser = fakeBrowser();
  const ctx = { browser, module: { exports: {} }, setTimeout, fetch: async () => ({ text: async () => '.round{}' }), console };
  vm.createContext(ctx);
  vm.runInContext(source('background.js'), ctx);
  return { browser, bg: ctx.module.exports };
}

// Values built inside the vm context have that context's prototypes: compare as plain JSON.
const eq = (actual, expected) => assert.deepEqual(JSON.parse(JSON.stringify(actual ?? null)), expected);
const tick = () => new Promise((r) => setTimeout(r, 0));

test('connects to the app and says ready', () => {
  const { browser } = loadBackground();
  eq(browser.log.app[0], { type: 'ready' });
});

test('WebGL guard is on everywhere until the app lists sites, then excludes them', async () => {
  const { browser, bg } = loadBackground();
  await tick();
  assert.equal(browser.log.registered[0].js[0].file, 'webgl.js');
  assert.equal(browser.log.registered[0].excludeMatches, undefined);
  await bg.onAppMessage({ cmd: 'webgl', allow: ['maps.example', 'bad host!'] });
  const last = browser.log.registered.at(-1);
  eq(last.excludeMatches, ['*://maps.example/*', '*://*.maps.example/*']);
  assert.equal(last.allFrames, true);
  assert.equal(browser.log.unregistered, 1);
});

test('round layout registers geometry + round.js at document start, and turns off', async () => {
  const { browser, bg } = loadBackground();
  await bg.onAppMessage({ cmd: 'round', on: true, geometry: { d: 240, sq: 169.7, inset: 35.1 } });
  const reg = browser.log.registered.at(-1);
  assert.match(reg.js[0].code, /window.__orbit=\{"d":240,"sq":169.7,"inset":35.1\}/);
  assert.match(reg.js[0].code, /window.__orbitCss=".round\{\}"/);
  assert.equal(reg.js[1].file, 'round.js');
  assert.equal(reg.runAt, 'document_start');
  const before = browser.log.unregistered;
  await bg.onAppMessage({ cmd: 'round', on: false });
  assert.equal(browser.log.unregistered, before + 1);
});

test('inject runs the extract scripts in order in the main frame, with orbitForce first', async () => {
  const { browser, bg } = loadBackground();
  await bg.onAppMessage({ cmd: 'inject', what: 'extract', force: true });
  const ex = browser.log.executed;
  assert.equal(ex[0].code, 'var orbitForce = true;');
  eq(ex.slice(1).map((e) => e.file), ['Readability-readerable.js', 'Readability.js', 'extract.js']);
  assert.ok(ex.every((e) => e.frameId === 0 && e.id === 7));
  await bg.onAppMessage({ cmd: 'inject', what: 'nope' });
  assert.equal(browser.log.executed.length, 4);
});

test('page messages are forwarded with their frame; reader payloads are served by token', async () => {
  const { browser, bg } = loadBackground();
  const [listener] = browser.runtime.onMessage.listeners;
  listener({ type: 'page', data: '{"type":"serp"}' }, { tab: { id: 3 }, frameId: 0, url: 'https://www.google.com/search?q=x' });
  eq(browser.log.app.at(-1), { type: 'page', data: '{"type":"serp"}', frameId: 0, url: 'https://www.google.com/search?q=x' });
  for (let i = 0; i < 7; i++) bg.keepPayload(`t${i}`, { html: `<p>${i}</p>` });
  eq(await listener({ type: 'readerPayload', token: 't6' }, { tab: { id: 3 } }), { html: '<p>6</p>' });
  assert.equal(await listener({ type: 'readerPayload', token: 't0' }, { tab: { id: 3 } }), null); // only the last 5
  // Commands go to the tab the page spoke from.
  await bg.onAppMessage({ cmd: 'page', name: 'linksStep', arg: 1 });
  eq(browser.log.toTab.at(-1), { id: 3, m: { orbit: 'linksStep', arg: 1 }, o: { frameId: 0 } });
  await bg.onAppMessage({ cmd: 'reader', name: 'turn', arg: -1 });
  eq(browser.log.runtimeSent.at(-1), { target: 'reader', name: 'turn', arg: -1 });
});

test('images are cancelled only while the app asks', async () => {
  const { browser, bg } = loadBackground();
  const [onRequest] = browser.webRequest.onBeforeRequest.listeners;
  bg.state.tabId = 3;
  assert.equal(onRequest({ tabId: 3 }), undefined);
  await bg.onAppMessage({ cmd: 'images', block: true });
  eq(onRequest({ tabId: 3 }), { cancel: true });
  assert.equal(onRequest({ tabId: 9 }), undefined);
});

test('bridge.js: postMessage shim, commands, taps and throttled metrics', async () => {
  const sent = [];
  const listeners = {};
  const docListeners = {};
  let scrolledTo = null;
  const link = { closest: (sel) => (sel.includes('a[href]') ? {} : null) };
  const plain = { closest: () => null };
  const orbitLinks = { steps: [], step(d) { this.steps.push(d); }, stop() {}, activate() {} };
  const win = {
    innerHeight: 400,
    scrollY: 120,
    addEventListener: (t, f) => { listeners[t] = f; },
    scrollTo: (x, y) => { scrolledTo = y; },
  };
  const ctx = {
    window: win,
    document: { documentElement: { scrollHeight: 2400 }, addEventListener: (t, f) => { docListeners[t] = f; } },
    browser: {
      runtime: {
        sendMessage: (m) => { sent.push(m); return Promise.resolve(); },
        onMessage: { addListener: (f) => { win.__command = f; } },
      },
    },
    setTimeout,
  };
  vm.createContext(ctx);
  vm.runInContext(source('bridge.js'), ctx);
  win.orbitLinks = orbitLinks;
  eq(sent[0], { type: 'hello' });
  win.orbitBridge.postMessage('{"type":"links"}');
  eq(sent.at(-1), { type: 'page', data: '{"type":"links"}' });
  win.__command({ orbit: 'linksStep', arg: -3 });
  eq(orbitLinks.steps, [-1]);
  win.__command({ orbit: 'scrollToFraction', arg: 0.5 });
  assert.equal(scrolledTo, 1000);
  docListeners.click({ target: link });
  eq(sent.at(-1), { type: 'tap', interactive: true });
  docListeners.click({ target: plain });
  eq(sent.at(-1), { type: 'tap', interactive: false });
  const before = sent.length;
  listeners.scroll(); listeners.scroll(); listeners.scroll();
  await new Promise((r) => setTimeout(r, 150));
  assert.equal(sent.length, before + 1);
  eq(sent.at(-1), { type: 'metrics', y: 120, max: 2000 });
});
