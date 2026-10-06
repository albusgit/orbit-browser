/*
 * Orbit — the extension's hub between the app and its pages.
 *
 *   app (OrbitExtension.kt) ⇄ native port "orbit" ⇄ this ⇄ bridge.js / reader page / injected scripts
 *
 * From the app (port messages, { cmd, ... }):
 *   round    { on, geometry: {d, sq, inset} }  Round Scroll layout at document start, or off
 *   webgl    { allow: [host] }                 WebGL guard everywhere except these sites
 *   images   { block }                         cancel image requests for the page on screen
 *   inject   { what: 'extract'|'serp'|'links', force }   run page scripts in the main frame
 *   page     { name, arg }                     command for bridge.js (links, scroll)
 *   reader   { name, arg }                     command for the reader page
 *   readerPayload { token, payload }           an article for reader.html (the last few are kept)
 * To the app: { type: 'ready' }, and every page message as { type, frameId, url, ... }.
 *
 * Kept small and stateless apart from the above: everything that decides lives in the app.
 */
'use strict';

const NATIVE_APP = 'orbit';
const MAX_PAYLOADS = 5;
const PAGE_SCRIPTS = {
  extract: ['Readability-readerable.js', 'Readability.js', 'extract.js'],
  serp: ['serp.js'],
  links: ['links.js'],
};
const WEB = ['http://*/*', 'https://*/*'];
const HOST = /^[a-z0-9.-]+$/;

const state = {
  port: null,
  queue: [],
  retryMs: 250,
  tabId: null,
  blockImages: false,
  payloads: new Map(),
  roundScript: null,
  webglScript: null,
  roundCss: null,
  chain: Promise.resolve(),
};

// ------------------------------------------------------------------ app link

function connect() {
  let port;
  try {
    port = browser.runtime.connectNative(NATIVE_APP);
  } catch (e) {
    retry();
    return;
  }
  state.port = port;
  // One at a time, in order: "inject links" must finish before "linksStep" reaches the page.
  port.onMessage.addListener((msg) => {
    state.chain = state.chain.then(() => onAppMessage(msg));
  });
  port.onDisconnect.addListener(() => {
    if (state.port === port) state.port = null;
    retry();
  });
  state.retryMs = 250;
  const pending = state.queue.splice(0);
  toApp({ type: 'ready' });
  pending.forEach(toApp);
}

/** The app sets its message delegate a moment after the extension starts: keep trying. */
function retry() {
  setTimeout(connect, state.retryMs);
  state.retryMs = Math.min(state.retryMs * 2, 5000);
}

function toApp(msg) {
  if (state.port) {
    try {
      state.port.postMessage(msg);
      return;
    } catch (e) {
      state.port = null;
    }
  }
  if (state.queue.length < 50) state.queue.push(msg);
}

async function onAppMessage(msg) {
  try {
    switch (msg && msg.cmd) {
      case 'round': await setRound(msg.on, msg.geometry); break;
      case 'webgl': await setWebGl(msg.allow || []); break;
      case 'images': state.blockImages = !!msg.block; break;
      case 'inject': await inject(msg.what, !!msg.force); break;
      case 'page': await toPage({ orbit: msg.name, arg: msg.arg }); break;
      case 'reader': await browser.runtime.sendMessage({ target: 'reader', name: msg.name, arg: msg.arg }); break;
      case 'readerPayload': keepPayload(msg.token, msg.payload); break;
    }
  } catch (e) {
    toApp({ type: 'error', cmd: String(msg && msg.cmd), message: String(e && e.message || e) });
  }
}

// --------------------------------------------------------------- page side

browser.runtime.onMessage.addListener((m, sender) => {
  if (!m || typeof m.type !== 'string') return undefined;
  if (sender.tab && sender.tab.id != null) state.tabId = sender.tab.id;
  if (m.type === 'readerPayload') return Promise.resolve(state.payloads.get(m.token) || null);
  if (m.type === 'hello') return undefined;
  toApp(Object.assign({}, m, { frameId: sender.frameId || 0, url: sender.url || '' }));
  return undefined;
});

async function tabId() {
  if (state.tabId != null) return state.tabId;
  const tabs = await browser.tabs.query({ active: true });
  state.tabId = tabs.length ? tabs[0].id : null;
  return state.tabId;
}

function toPage(message) {
  return tabId().then((id) => (id == null ? undefined : browser.tabs.sendMessage(id, message, { frameId: 0 })));
}

/** Runs Orbit's page scripts in the main frame, in the extension's sandbox (never the page's). */
async function inject(what, force) {
  const files = PAGE_SCRIPTS[what];
  const id = await tabId();
  if (!files || id == null) return;
  if (what === 'extract') {
    await browser.tabs.executeScript(id, { code: `var orbitForce = ${force ? 'true' : 'false'};`, frameId: 0 });
  }
  for (const file of files) await browser.tabs.executeScript(id, { file, frameId: 0 });
}

function keepPayload(token, payload) {
  if (typeof token !== 'string' || !payload) return;
  state.payloads.delete(token);
  state.payloads.set(token, payload);
  while (state.payloads.size > MAX_PAYLOADS) state.payloads.delete(state.payloads.keys().next().value);
}

// ------------------------------------------------------- document-start scripts

async function setRound(on, geometry) {
  if (state.roundScript) {
    const old = state.roundScript;
    state.roundScript = null;
    await old.unregister();
  }
  if (!on || !geometry) return;
  if (state.roundCss == null) state.roundCss = await (await fetch(browser.runtime.getURL('round.css'))).text();
  const g = { d: Number(geometry.d) || 0, sq: Number(geometry.sq) || 0, inset: Number(geometry.inset) || 0 };
  const prefix = `window.__orbit=${JSON.stringify(g)};window.__orbitCss=${JSON.stringify(state.roundCss)};`;
  state.roundScript = await browser.contentScripts.register({
    matches: WEB,
    js: [{ code: prefix }, { file: 'round.js' }],
    runAt: 'document_start',
    allFrames: false,
  });
}

async function setWebGl(allow) {
  if (state.webglScript) {
    const old = state.webglScript;
    state.webglScript = null;
    await old.unregister();
  }
  const exclude = [];
  for (const h of allow) {
    if (typeof h === 'string' && HOST.test(h)) exclude.push(`*://${h}/*`, `*://*.${h}/*`);
  }
  const options = { matches: WEB, js: [{ file: 'webgl.js' }], runAt: 'document_start', allFrames: true, matchAboutBlank: true };
  if (exclude.length) options.excludeMatches = exclude;
  state.webglScript = await browser.contentScripts.register(options);
}

// ----------------------------------------------------------------- requests

// Per-site "Block images", and search-results pages (never shown, so never worth the bytes).
browser.webRequest.onBeforeRequest.addListener(
  (d) => (state.blockImages && (state.tabId == null || d.tabId === state.tabId) ? { cancel: true } : undefined),
  { urls: ['<all_urls>'], types: ['image', 'imageset'] },
  ['blocking'],
);

// WebGL is off until the app says which sites may have it.
setWebGl([]).catch(() => {});
connect();

if (typeof module !== 'undefined') module.exports = { state, onAppMessage, keepPayload, setWebGl, setRound, inject, toApp };
