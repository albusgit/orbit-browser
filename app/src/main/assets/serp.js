/*
 * Orbit — search-results extractor. Evaluated by Injector.kt on Google, DuckDuckGo (HTML/Lite)
 * and Bing result pages; Orbit then draws the results as native cards and never shows the page.
 *
 * Google's class names are obfuscated and change often, so it is read through anchors that
 * have been stable for years: results live in #rso (or #search), each result's title is an
 * h3 (or role=heading aria-level=3 on mobile) inside its link, ads live in #tads/#tadsb or
 * carry a "Sponsored" label, and "People also ask" blocks carry data-q.
 *
 * Posts { type: 'serp', url, results: [{ title, href, snippet }], answer }. Links are sent as
 * found (absolute); the app unwraps redirects and drops engine-internal links.
 */
(function () {
  'use strict';

  var MAX = 30;
  var SNIPPET_MAX = 320;

  function post(msg) {
    msg.type = 'serp';
    msg.url = location.href;
    var s = JSON.stringify(msg);
    if (window.orbitBridge) window.orbitBridge.postMessage(s);
    else if (window.OrbitNative) window.OrbitNative.post(s);
  }

  function clean(s) {
    s = (s || '').replace(/\s+/g, ' ').trim();
    return s.length > SNIPPET_MAX ? s.slice(0, SNIPPET_MAX - 1).replace(/\s+\S*$/, '') + '…' : s;
  }

  function text(el) {
    return el ? clean(el.innerText !== undefined && el.innerText !== '' ? el.innerText : el.textContent) : '';
  }

  // ------------------------------------------------------------------ Google

  var G_TITLE = 'h3, [role="heading"][aria-level="3"]';
  var G_AD = '#tads, #tadsb, #bottomads, [data-text-ad], [aria-label="Ads"], [aria-label="Sponsored"]';
  var G_PAA = '[data-q], .related-question-pair, [data-initq]';

  /** The block that holds exactly one result: climb while the parent has no other title. */
  function resultBlock(a, root) {
    var node = a;
    while (node.parentElement && node.parentElement !== root &&
           node.parentElement.querySelectorAll(G_TITLE).length <= 1) {
      node = node.parentElement;
    }
    return node;
  }

  function isSponsored(block) {
    var head = (block.innerText || block.textContent || '').slice(0, 120);
    return /(^|\n)\s*(Sponsored|Ad|Ads)\s*(·|\n|$)/.test(head);
  }

  /** Snippet: Google's snippet markers first, then the longest plain-text block that isn't title/URL. */
  function googleSnippet(block, a, title) {
    var marked = block.querySelectorAll('[data-sncf], .VwiC3b, [style*="-webkit-line-clamp"], [data-content-feature="1"]');
    for (var i = 0; i < marked.length; i++) {
      if (a.contains(marked[i])) continue;
      var t = text(marked[i]);
      if (t.length > 30 && t !== title) return t;
    }
    var best = '';
    var nodes = block.querySelectorAll('div, span, p');
    for (var j = 0; j < nodes.length; j++) {
      var n = nodes[j];
      if (a.contains(n) || n.querySelector('div, p, h3, cite') || n.closest('cite')) continue;
      var s = text(n);
      if (s.length > best.length && s !== title && !/^https?:\/\/|›/.test(s)) best = s;
    }
    return best.length > 40 ? best : '';
  }

  function google() {
    var root = document.getElementById('rso') || document.getElementById('search') ||
               document.getElementById('main');
    if (!root) return { results: [] };
    var out = [];
    var seen = {};
    var titles = root.querySelectorAll(G_TITLE);
    for (var i = 0; i < titles.length && out.length < MAX; i++) {
      var h = titles[i];
      var a = h.closest('a[href]') || h.querySelector('a[href]');
      if (!a || seen[a.href]) continue;
      if (a.closest(G_AD) || a.closest(G_PAA)) continue;
      var block = resultBlock(a, root);
      if (isSponsored(block)) continue;
      var title = text(h);
      if (!title) continue;
      seen[a.href] = true;
      out.push({ title: title, href: a.href, snippet: googleSnippet(block, a, title) });
    }
    // A featured snippet ("top answer"), only through its long-standing attribute markers.
    var answer = '';
    var fs = document.querySelector('[data-attrid="wa:/description"], [data-tts="answers"]');
    if (fs && !fs.closest(G_AD)) {
      var t = text(fs);
      if (t.length >= 20 && t.length <= SNIPPET_MAX) answer = t;
    }
    return { results: out, answer: answer };
  }

  // ------------------------------------------------------------------ DuckDuckGo

  function duckduckgo() {
    var out = [];
    var rows = document.querySelectorAll('.result, .web-result');
    if (rows.length) {
      for (var i = 0; i < rows.length && out.length < MAX; i++) {
        var r = rows[i];
        if (/result--ad|result--sponsored/.test(r.className)) continue;
        var a = r.querySelector('.result__a');
        if (!a) continue;
        out.push({ title: text(a), href: a.href, snippet: text(r.querySelector('.result__snippet')) });
      }
      return { results: out };
    }
    // DuckDuckGo Lite: a table of link rows followed by snippet rows.
    var links = document.querySelectorAll('a.result-link');
    for (var j = 0; j < links.length && out.length < MAX; j++) {
      var l = links[j];
      var row = l.closest('tr');
      if (row && /result-sponsored/.test(row.className)) continue;
      var snip = row && row.nextElementSibling ? row.nextElementSibling.querySelector('.result-snippet') : null;
      out.push({ title: text(l), href: l.href, snippet: text(snip) });
    }
    return { results: out };
  }

  // ------------------------------------------------------------------ Bing

  function bing() {
    var out = [];
    var rows = document.querySelectorAll('#b_results > li.b_algo, #b_results .b_algo');
    for (var i = 0; i < rows.length && out.length < MAX; i++) {
      var r = rows[i];
      if (r.closest('.b_ad, .b_adTop, .b_adBottom')) continue;
      var a = r.querySelector('h2 a[href], h3 a[href], a.tilk[href]');
      if (!a) continue;
      var title = text(r.querySelector('h2, h3')) || text(a);
      var snip = r.querySelector('.b_caption p, .b_lineclamp2, .b_lineclamp3, .b_lineclamp4, .b_algoSlug, .b_snippet');
      out.push({ title: title, href: a.href, snippet: text(snip) });
    }
    return { results: out };
  }

  // ------------------------------------------------------------------ main

  try {
    var host = location.hostname;
    var found = /(^|\.)google\./.test(host) ? google()
      : /duckduckgo\.com$/.test(host) ? duckduckgo()
      : /(^|\.)bing\.com$/.test(host) ? bing()
      : { results: [] };
    post({ results: found.results, answer: found.answer || '' });
  } catch (e) {
    post({ results: [], answer: '', error: String(e && e.message || e) });
  }
})();
