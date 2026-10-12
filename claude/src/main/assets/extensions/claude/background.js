/*
 * Orbit for Claude — the extension's hub between the app and claude.ai.
 *
 *   app (ClaudeBridge.kt) ⇄ native port "claude" ⇄ this ⇄ claude.js in the chat tab
 *
 * From the app: { cmd, ... } is passed to the chat tab as is (send, stop, scroll, layout,
 * showComposer). `layout` is also kept and re-sent to every new page.
 * To the app: { type: 'ready' }, and every message from claude.js.
 *
 * The chat tab is the last tab claude.js spoke from. claude.js stays silent in popups (the
 * Google sign-in window), so a popup that lands on claude.ai can't take over.
 */
'use strict';

const NATIVE_APP = 'claude';

const state = {
  port: null,
  queue: [],
  retryMs: 250,
  tabId: null,
  layout: null,
};

function connect() {
  let port;
  try {
    port = browser.runtime.connectNative(NATIVE_APP);
  } catch (e) {
    retry();
    return;
  }
  state.port = port;
  port.onMessage.addListener(onAppMessage);
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

function onAppMessage(msg) {
  if (!msg || typeof msg.cmd !== 'string') return;
  if (msg.cmd === 'layout') state.layout = msg;
  toTab(msg).catch((e) => toApp({ type: 'error', cmd: msg.cmd, message: String((e && e.message) || e) }));
}

function toTab(msg) {
  if (state.tabId == null) return Promise.resolve();
  return browser.tabs.sendMessage(state.tabId, msg, { frameId: 0 });
}

browser.runtime.onMessage.addListener((m, sender) => {
  if (!m || typeof m.type !== 'string' || !sender.tab) return undefined;
  if ((sender.frameId || 0) !== 0) return undefined;
  state.tabId = sender.tab.id;
  if (m.type === 'hello') return Promise.resolve(state.layout);
  toApp(m);
  return undefined;
});

connect();

if (typeof module !== 'undefined') module.exports = { state, onAppMessage, toApp };
