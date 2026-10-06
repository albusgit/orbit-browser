/*
 * Orbit — article extraction. Injected by background.js after a page loads, as content scripts
 * (Readability-readerable.js, Readability.js, then this): they run in the extension's sandbox,
 * so nothing touches the page's own globals.
 *
 * Expects `orbitForce` (boolean), set by a code snippet injected just before: true when the user
 * picked Reader for this site, which skips the "is this an article?" heuristic.
 *
 * Posts one message: { type: 'article', ok, ...article } to the Kotlin bridge.
 */
/* global Readability, isProbablyReaderable, orbitForce */
(function () {
  'use strict';

  function post(msg) {
    var s = JSON.stringify(msg);
    if (window.orbitBridge) window.orbitBridge.postMessage(s);
  }

  // Elements that never belong in the reader: active content, forms, media players.
  var DROP = 'script,style,link,meta,iframe,frame,frameset,object,embed,applet,form,input,button,' +
    'select,textarea,noscript,canvas,svg,math,video,audio,source,track,template,dialog,base';
  // Attributes worth keeping; everything else (on*, style, class, id, data-*) goes.
  var KEEP = {
    href: 1, src: 1, srcset: 1, sizes: 1, alt: 1, title: 1, colspan: 1, rowspan: 1,
    lang: 1, dir: 1, start: 1, reversed: 1, type: 1, width: 1, height: 1, datetime: 1, cite: 1,
  };
  var SAFE_URL = /^(https?:|mailto:|#)/i;
  var SAFE_IMG = /^(https?:|data:image\/)/i;

  /** Allow-list sanitiser, run in an inert document so nothing loads or executes. */
  function sanitize(html) {
    var doc = document.implementation.createHTMLDocument('');
    var root = doc.createElement('div');
    root.innerHTML = html;
    var dropped = root.querySelectorAll(DROP);
    for (var i = 0; i < dropped.length; i++) dropped[i].remove();
    var all = root.querySelectorAll('*');
    for (var j = 0; j < all.length; j++) {
      var el = all[j];
      for (var k = el.attributes.length - 1; k >= 0; k--) {
        var name = el.attributes[k].name.toLowerCase();
        var value = el.attributes[k].value.trim();
        if (!KEEP[name]) { el.removeAttribute(name); continue; }
        if (name === 'href' && !SAFE_URL.test(value)) el.removeAttribute(name);
        if (name === 'src' && !SAFE_IMG.test(value)) el.removeAttribute(name);
        if (name === 'srcset' && /javascript:/i.test(value)) el.removeAttribute(name);
      }
      if (el.tagName === 'IMG') {
        // Images load only when their page is about to be shown (saves Bluetooth bandwidth).
        el.setAttribute('loading', 'lazy');
        el.setAttribute('decoding', 'async');
        if (!el.getAttribute('src')) el.remove();
      }
      if (el.tagName === 'A' && el.getAttribute('href')) el.setAttribute('rel', 'noreferrer');
    }
    return root.innerHTML;
  }

  try {
    var force = typeof orbitForce !== 'undefined' && orbitForce;
    if (!force && !isProbablyReaderable(document, { minContentLength: 140, minScore: 20 })) {
      post({ type: 'article', ok: false, reason: 'not-readerable' });
      return;
    }
    var article = new Readability(document.cloneNode(true), { charThreshold: 400 }).parse();
    if (!article || !article.content || (article.textContent || '').trim().length < 280) {
      post({ type: 'article', ok: false, reason: 'no-content' });
      return;
    }
    post({
      type: 'article',
      ok: true,
      url: location.href,
      title: article.title || document.title || '',
      byline: article.byline || '',
      siteName: article.siteName || '',
      lang: article.lang || document.documentElement.lang || '',
      dir: article.dir || '',
      published: article.publishedTime || '',
      words: (article.textContent || '').split(/\s+/).length,
      content: sanitize(article.content),
    });
  } catch (e) {
    post({ type: 'article', ok: false, reason: 'error', error: String(e && e.message || e) });
  }
})();
