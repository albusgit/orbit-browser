/*
 * Orbit — Round Scroll mode bootstrap. Runs at document start in the main frame.
 *
 * Expects (prepended by Injector.kt):
 *   window.__orbit    = { d, sq, inset }   screen geometry in CSS px
 *   window.__orbitCss = "...round.css..."
 * Falls back to the viewport size if __orbit is missing (e.g. in tools/round-check).
 */
(function () {
  'use strict';
  // Skip frames, repeats, and Orbit's own reader pages (loaded with this fragment).
  if (window.top !== window || window.__orbitRound || location.hash === '#orbit-reader') return;
  window.__orbitRound = true;

  var VIEWPORT = 'width=device-width, initial-scale=1, minimum-scale=1, maximum-scale=5, user-scalable=yes';
  var STYLE_ID = 'orbit-round-css';

  var geo = window.__orbit || {};
  var d = geo.d || Math.min(window.innerWidth, window.innerHeight) || screen.width;
  var sq = geo.sq || d / Math.SQRT2;
  var inset = geo.inset != null ? geo.inset : (d - sq) / 2;

  // At document start the parser may not have created <html> yet.
  var root = null;

  function applyVars() {
    root.style.setProperty('--orbit-d', d + 'px');
    root.style.setProperty('--orbit-sq', sq + 'px');
    root.style.setProperty('--orbit-inset', inset + 'px');
    root.classList.add('orbit-round');
  }

  // Our <style> must come after the page's styles so equal-specificity rules lose to ours.
  function placeStyle() {
    var style = document.getElementById(STYLE_ID);
    if (!style) {
      style = document.createElement('style');
      style.id = STYLE_ID;
      style.textContent = window.__orbitCss || '';
    }
    var parent = document.head || root;
    if (style.parentNode !== parent || style !== parent.lastElementChild) parent.appendChild(style);
  }

  function fixMeta(meta) {
    if (meta.getAttribute('content') !== VIEWPORT) meta.setAttribute('content', VIEWPORT);
  }

  function fixViewport() {
    var metas = document.querySelectorAll('meta[name="viewport" i]');
    if (metas.length === 0) {
      var meta = document.createElement('meta');
      meta.name = 'viewport';
      meta.content = VIEWPORT;
      (document.head || root).appendChild(meta);
      return;
    }
    for (var i = 0; i < metas.length; i++) fixMeta(metas[i]);
  }

  // Fixed headers, cookie bars and sticky nav would sit over a third of a 1.4" screen (and
  // under the rim). Put shallow ones back in the flow so they scroll away with the page.
  // Shallow only: a full-document getComputedStyle sweep is too slow on a watch.
  function unfixPositioned() {
    if (!document.body) return;
    var candidates = document.body.querySelectorAll('body > *, body > * > *');
    for (var i = 0; i < candidates.length; i++) {
      var el = candidates[i];
      var pos = getComputedStyle(el).position;
      if (pos === 'fixed' || pos === 'sticky') el.style.setProperty('position', 'static', 'important');
    }
  }

  // Tables that can't fit the column (data tables, nowrap cells) scroll inside a wrapper.
  // Done after layout so layout tables that do fit keep working as tables.
  function wrapWideTables() {
    if (!document.body) return;
    var tables = Array.prototype.slice.call(document.body.getElementsByTagName('table'));
    for (var i = 0; i < tables.length; i++) {
      var t = tables[i];
      if (!t.parentNode || t.closest('.orbit-scroll-x')) continue;
      if (t.getBoundingClientRect().width <= sq + 1) continue;
      var wrap = document.createElement('div');
      wrap.className = 'orbit-scroll-x';
      t.parentNode.insertBefore(wrap, t);
      wrap.appendChild(t);
    }
  }

  function fitContent() {
    unfixPositioned();
    wrapWideTables();
  }

  function start() {
    root = document.documentElement;
    applyVars();
    placeStyle();
    fixViewport();
    observer.observe(root, { childList: true, subtree: true, attributes: true, attributeFilter: ['content'] });
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', onReady, { once: true });
    } else {
      onReady();
    }
    window.addEventListener('load', fitContent, { once: true });
  }

  // Catch viewport metas the parser (or page script) adds later, and keep them in line.
  var observer = new MutationObserver(function (records) {
    for (var i = 0; i < records.length; i++) {
      var r = records[i];
      if (r.type === 'attributes') {
        if (r.target.nodeName === 'META' && /^viewport$/i.test(r.target.name)) fixMeta(r.target);
        continue;
      }
      for (var j = 0; j < r.addedNodes.length; j++) {
        var n = r.addedNodes[j];
        if (n.nodeName === 'META' && /^viewport$/i.test(n.name)) fixMeta(n);
      }
    }
  });

  function onReady() {
    applyVars();
    placeStyle();
    fixViewport();
    fitContent();
    // After parsing, only <head> can still gain viewport metas worth caring about.
    observer.disconnect();
    if (document.head) {
      observer.observe(document.head, { childList: true, attributes: true, subtree: true, attributeFilter: ['content'] });
    }
  }

  if (document.documentElement) {
    start();
  } else {
    new MutationObserver(function (_, waiting) {
      if (!document.documentElement) return;
      waiting.disconnect();
      start();
    }).observe(document, { childList: true });
  }
})();
