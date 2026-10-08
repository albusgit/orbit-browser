/*
 * Orbit — the page side of the app link. A content script in the main frame of every web page,
 * from document start. It runs in the extension's sandbox, so pages can't see or fake it.
 *
 *   window.orbitBridge.postMessage(json)  what Orbit's injected scripts (extract, serp, links)
 *                                          call to reach the app
 *   metrics { y, max }                    scroll position in CSS px, ≤10 per second
 *   tap { interactive }                   a tap, and whether it hit a link or control, so a
 *                                          tap on nothing in the middle can open Orbit's menu
 *   commands from the app                 link focus steps, scroll to a saved position
 */
(function () {
  'use strict';
  if (window.__orbitBridge) return;
  window.__orbitBridge = true;

  var METRICS_MS = 100;
  var INTERACTIVE = 'a[href], button, input, select, textarea, label, summary, video, audio, ' +
    '[role="button"], [role="link"], [role="checkbox"], [role="tab"], [onclick], [contenteditable="true"]';

  function send(m) {
    try {
      browser.runtime.sendMessage(m).catch(function () {});
    } catch (e) { /* the extension is reloading */ }
  }

  window.orbitBridge = {
    postMessage: function (s) { send({ type: 'page', data: String(s) }); },
  };
  send({ type: 'hello' });

  browser.runtime.onMessage.addListener(function (m) {
    if (!m || !m.orbit) return;
    var links = window.orbitLinks;
    switch (m.orbit) {
      case 'linksStep': if (links) links.step(m.arg < 0 ? -1 : 1); break;
      case 'linksStop': if (links) links.stop(); break;
      case 'linksActivate': if (links) links.activate(); break;
      case 'clearSelection': {
        // A hold that opened Orbit's ring may have selected a word under the finger.
        var sel = window.getSelection && window.getSelection();
        if (sel) sel.removeAllRanges();
        break;
      }
      case 'scrollToFraction': {
        var f = Math.max(0, Math.min(1, Number(m.arg) || 0));
        window.scrollTo(0, f * maxScroll());
        break;
      }
    }
  });

  function maxScroll() {
    var de = document.documentElement;
    return Math.max(0, (de ? de.scrollHeight : 0) - window.innerHeight);
  }

  var timer = 0;
  function metrics() {
    timer = 0;
    send({ type: 'metrics', y: Math.round(window.scrollY), max: Math.round(maxScroll()) });
  }
  function soon() {
    if (!timer) timer = setTimeout(metrics, METRICS_MS);
  }
  window.addEventListener('scroll', soon, { passive: true });
  window.addEventListener('resize', soon, { passive: true });
  window.addEventListener('load', soon);
  document.addEventListener('DOMContentLoaded', soon);

  document.addEventListener('click', function (e) {
    var t = e.target;
    var hit = t && t.closest ? t.closest(INTERACTIVE) : null;
    send({ type: 'tap', interactive: !!hit });
  }, true);
})();
