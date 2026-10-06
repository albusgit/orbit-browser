/*
 * Orbit — Paged Reader pagination engine.
 *
 * shape-outside shapes text relative to the document, not the viewport, so a scrolling
 * circle-shaped column can't work. Instead the article is cut into circular pages:
 *
 *   - Every text page is a d×d box with two floats (left and right halves) whose
 *     shape-outside polygons cover everything outside the circle. Lines are short near
 *     the top and bottom and full width in the middle; where the chord gets too short to
 *     be readable the floats block it completely.
 *   - Blocks are added until one overflows the page. A paragraph that doesn't fit is split
 *     by binary search on its word count; the split keeps inline markup via DOM Ranges.
 *   - Images get their own page, scaled into the inscribed square. Tables and code get a
 *     page with a scrollable inscribed square.
 *
 * Positions are anchors {u: unit index, w: word index}, so a re-pagination after a
 * typography change lands on the page holding the same word.
 *
 * Config: window.__orbitReader = { d, sq, inset, style: {fontSize, lineHeight, serif}, anchor }
 * API:    window.orbitReader.turn(delta) / goTo(index) / goToAnchor(anchor) / setStyle(style) / state()
 * Posts:  { type: 'reader', event: 'ready'|'page'|'progress', page, total, done, anchor, source }
 */
(function () {
  'use strict';

  var cfg = window.__orbitReader || {};
  var d = cfg.d || Math.min(window.innerWidth, window.innerHeight);
  var sq = cfg.sq || d / Math.SQRT2;
  var inset = cfg.inset != null ? cfg.inset : (d - sq) / 2;
  var style = cfg.style || { fontSize: 15, lineHeight: 1.4, serif: true };
  var rtl = document.documentElement.dir === 'rtl';

  var root = document.documentElement;
  var book = document.getElementById('orbit-book');
  var source = document.getElementById('orbit-source');

  /** Gap between text and the rim, and the shortest line worth setting. */
  var EDGE = Math.max(4, d * 0.035);
  var MIN_CHORD = d * 0.5;
  var SLICE_MS = 12;

  function post(msg) {
    msg.type = 'reader';
    var s = JSON.stringify(msg);
    if (window.orbitBridge) window.orbitBridge.postMessage(s);
    else if (window.OrbitNative) window.OrbitNative.post(s);
  }

  function applyStyle() {
    root.style.setProperty('--orbit-d', d + 'px');
    root.style.setProperty('--orbit-sq', sq + 'px');
    root.style.setProperty('--orbit-inset', inset + 'px');
    root.style.setProperty('--orbit-font-size', style.fontSize + 'px');
    root.style.setProperty('--orbit-line-height', String(style.lineHeight));
    root.style.setProperty('--orbit-font', style.serif
      ? "'Noto Serif', Georgia, serif"
      : "system-ui, Roboto, 'Noto Sans', sans-serif");
  }

  // ---------------------------------------------------------------- shapes

  var shapeL = '';
  var shapeR = '';

  /**
   * Polygons for the two floats. Above and below the band where the chord is at least
   * MIN_CHORD the floats block the whole width; at the band edges the step is horizontal,
   * so the first and last usable lines get the full minimum chord (a slanted edge there
   * would leave a sliver that splits words). Inside the band the vertices sit on the
   * circle, so the straight edges between them stay inside it.
   */
  function buildShapes() {
    var c = d / 2;
    var R = c - EDGE;
    var hMin = MIN_CHORD / 2;
    var span = Math.sqrt(Math.max(0, R * R - hMin * hMin));
    var yTop = c - span;
    var yBot = c + span;
    var N = 48;
    var fx = function (v) { return v.toFixed(2) + 'px'; };
    var left = ['0px 0px', fx(c) + ' 0px', fx(c) + ' ' + fx(yTop)];
    var right = [fx(c) + ' 0px', '0px 0px', '0px ' + fx(yTop)];
    for (var i = 0; i <= N; i++) {
      var y = yTop + ((yBot - yTop) * i) / N;
      var dy = y - c;
      var h = Math.max(hMin, Math.sqrt(Math.max(0, R * R - dy * dy)));
      left.push(fx(c - h) + ' ' + fx(y));
      right.push(fx(h) + ' ' + fx(y));
    }
    left.push(fx(c) + ' ' + fx(yBot), fx(c) + ' ' + fx(d), '0px ' + fx(d));
    right.push('0px ' + fx(yBot), '0px ' + fx(d), fx(c) + ' ' + fx(d));
    shapeL = 'polygon(' + left.join(',') + ')';
    shapeR = 'polygon(' + right.join(',') + ')';
  }

  // ----------------------------------------------------------------- units

  var BLOCK_CONTAINER = /^(DIV|SECTION|ARTICLE|MAIN|HEADER|FOOTER|ASIDE|NAV|CENTER|DETAILS|HGROUP|FIGURE)$/;
  var TEXT_BLOCK = /^(P|H[1-6]|BLOCKQUOTE|DL|ADDRESS|SUMMARY)$/;
  var HEADING = /^H[1-6]$/;

  var units = [];

  function wordsOf(el) {
    var list = [];
    var walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
    var re = /\S+/g;
    for (var n = walker.nextNode(); n; n = walker.nextNode()) {
      re.lastIndex = 0;
      for (var m = re.exec(n.data); m; m = re.exec(n.data)) list.push([n, m.index, m.index + m[0].length]);
    }
    return list;
  }

  function imageUnit(img, caption) {
    return { kind: 'image', el: img, caption: caption || img.getAttribute('alt') || '' };
  }

  /** Text block: pull its images out into their own units, drop it if nothing is left. */
  function pushText(el, out, extra) {
    var imgs = el.querySelectorAll('img');
    var images = [];
    for (var i = 0; i < imgs.length; i++) {
      images.push(imageUnit(imgs[i], imgs[i].getAttribute('alt')));
      imgs[i].remove();
    }
    var words = wordsOf(el);
    if (words.length) {
      var u = { kind: 'text', el: el, words: words };
      if (extra) for (var k in extra) u[k] = extra[k];
      out.push(u);
    }
    for (var j = 0; j < images.length; j++) out.push(images[j]);
  }

  function collect(node, out) {
    var run = null;
    function flush() {
      if (run) { pushText(run, out); run = null; }
    }
    var children = Array.prototype.slice.call(node.childNodes);
    for (var i = 0; i < children.length; i++) {
      var ch = children[i];
      if (ch.nodeType === 3) {
        if (ch.data.trim()) (run = run || document.createElement('p')).appendChild(ch);
        continue;
      }
      if (ch.nodeType !== 1) continue;
      var tag = ch.tagName;
      if (tag === 'IMG') { flush(); out.push(imageUnit(ch)); continue; }
      if (tag === 'PICTURE') {
        flush();
        var pimg = ch.querySelector('img');
        if (pimg) out.push(imageUnit(pimg));
        continue;
      }
      if (tag === 'FIGURE') {
        flush();
        var fimg = ch.querySelector('img');
        var cap = ch.querySelector('figcaption');
        if (fimg && !ch.querySelector('table,pre')) {
          out.push(imageUnit(fimg, cap ? cap.textContent.trim() : ''));
        } else {
          collect(ch, out);
        }
        continue;
      }
      if (tag === 'UL' || tag === 'OL') {
        flush();
        var n = tag === 'OL' ? parseInt(ch.getAttribute('start') || '1', 10) || 1 : 0;
        var items = ch.children;
        for (var j = 0; j < items.length; j++) {
          if (items[j].tagName !== 'LI') continue;
          pushText(items[j], out, { list: tag, number: n });
          if (n) n++;
        }
        continue;
      }
      if (tag === 'PRE') { flush(); out.push({ kind: 'pre', el: ch, lines: ch.textContent.replace(/\n$/, '').split('\n') }); continue; }
      if (tag === 'TABLE') { flush(); out.push({ kind: 'box', el: ch }); continue; }
      if (tag === 'HR') {
        flush();
        var sep = document.createElement('p');
        sep.className = 'orbit-sep';
        sep.textContent = '⁂';
        out.push({ kind: 'text', el: sep, words: wordsOf(sep) });
        continue;
      }
      if (TEXT_BLOCK.test(tag) || tag === 'LI' || tag === 'DT' || tag === 'DD') { flush(); pushText(ch, out); continue; }
      if (BLOCK_CONTAINER.test(tag) || tag === 'FIGCAPTION') { flush(); collect(ch, out); continue; }
      if (tag === 'BR') { if (run) run.appendChild(ch); continue; }
      (run = run || document.createElement('p')).appendChild(ch); // inline content at block level
    }
    flush();
  }

  // ----------------------------------------------------------------- pages

  var pages = [];
  var cursor = { u: 0, w: 0 };
  var done = false;
  var current = -1;
  var generation = 0;

  function cmp(a, b) { return a.u - b.u || a.w - b.w; }

  function newPage(kind) {
    var el = document.createElement('div');
    el.className = 'orbit-page is-measuring';
    var page = { el: el, kind: kind, start: { u: cursor.u, w: cursor.w } };
    if (kind === 'text') {
      var flow = document.createElement('div');
      flow.className = 'orbit-flow';
      var l = document.createElement('div');
      l.className = 'orbit-shape-l';
      l.style.shapeOutside = shapeL;
      var r = document.createElement('div');
      r.className = 'orbit-shape-r';
      r.style.shapeOutside = shapeR;
      flow.appendChild(l);
      flow.appendChild(r);
      el.appendChild(flow);
      page.flow = flow;
    } else {
      var box = document.createElement('div');
      box.className = kind === 'image' ? 'orbit-square' : 'orbit-square orbit-box';
      el.appendChild(box);
      page.box = box;
    }
    book.appendChild(el);
    return page;
  }

  function finishPage(page) {
    page.el.classList.remove('is-measuring');
    pages.push(page);
  }

  /** Clone of words [a, b) of a text unit, keeping inline markup. */
  function fragment(unit, a, b) {
    var shell = unit.el.cloneNode(false);
    var words = unit.words;
    var range = document.createRange();
    if (a === 0) range.setStart(unit.el, 0);
    else range.setStart(words[a][0], words[a][1]);
    if (b >= words.length) range.setEnd(unit.el, unit.el.childNodes.length);
    else range.setEnd(words[b - 1][0], words[b - 1][2]);
    shell.appendChild(range.cloneContents());
    if (a > 0) shell.classList.add('orbit-cont');
    return shell;
  }

  function fits(el) {
    var flowTop = el.closest('.orbit-flow').getBoundingClientRect().top;
    return el.getBoundingClientRect().bottom - flowTop <= d + 0.5;
  }

  /** The list element on this page that list items of [unit] go into. */
  function listFor(page, unit) {
    var last = page.flow.lastElementChild;
    if (last && last.tagName === unit.list && last.__orbitList) return last;
    var list = document.createElement(unit.list);
    list.__orbitList = true;
    if (unit.list === 'OL') list.setAttribute('start', String(unit.number));
    page.flow.appendChild(list);
    return list;
  }

  /**
   * Places as many words of [unit] from [from] as fit. Returns the word index reached.
   * [atomic] units (headings) are placed whole or not at all.
   */
  function placeText(page, unit, from, atomic) {
    var parent = unit.list ? listFor(page, unit) : page.flow;
    var total = unit.words.length;
    var el = fragment(unit, from, total);
    parent.appendChild(el);
    if (fits(el)) return total;
    parent.removeChild(el);
    if (atomic) {
      if (!parent.children.length && parent !== page.flow) parent.remove();
      return from;
    }
    var lo = from;
    var hi = total - 1;
    while (lo < hi) {
      var mid = (lo + hi + 1) >> 1;
      el = fragment(unit, from, mid);
      parent.appendChild(el);
      var ok = fits(el);
      parent.removeChild(el);
      if (ok) lo = mid; else hi = mid - 1;
    }
    if (lo > from) parent.appendChild(fragment(unit, from, lo));
    if (!parent.children.length && parent !== page.flow) parent.remove();
    return lo;
  }

  function contentCount(page) { return page.flow.childElementCount - 2; /* minus the floats */ }

  function buildTextPage() {
    var page = newPage('text');
    var lastWhole = null;
    while (cursor.u < units.length) {
      var unit = units[cursor.u];
      if (unit.kind !== 'text') break;
      // A heading never splits across pages unless it can't fit even on an empty one.
      var atomic = HEADING.test(unit.el.tagName) && contentCount(page) > 0;
      var reached = placeText(page, unit, cursor.w, atomic);
      if (reached === unit.words.length) {
        lastWhole = { unit: unit, at: { u: cursor.u, w: cursor.w } };
        cursor.u++;
        cursor.w = 0;
        continue;
      }
      if (reached > cursor.w) {
        cursor.w = reached;
        lastWhole = null;
      } else if (contentCount(page) === 0) {
        // Not even one word fits on an empty page (giant heading at a huge font size):
        // place the rest anyway rather than loop forever.
        (unit.list ? listFor(page, unit) : page.flow).appendChild(fragment(unit, cursor.w, unit.words.length));
        cursor.u++;
        cursor.w = 0;
        lastWhole = null;
      }
      break;
    }
    // Keep a heading with the text it introduces.
    if (lastWhole && HEADING.test(lastWhole.unit.el.tagName) && cursor.u < units.length &&
        units[cursor.u].kind === 'text' && contentCount(page) > 1) {
      page.flow.lastElementChild.remove();
      cursor = lastWhole.at;
    }
    finishPage(page);
  }

  function buildImagePage(unit) {
    var page = newPage('image');
    var img = unit.el.cloneNode(false);
    img.removeAttribute('width');
    img.removeAttribute('height');
    page.box.appendChild(img);
    if (unit.caption) {
      var cap = document.createElement('div');
      cap.className = 'orbit-caption';
      cap.textContent = unit.caption;
      page.box.appendChild(cap);
    }
    cursor.u++;
    cursor.w = 0;
    finishPage(page);
  }

  function buildBoxPage(unit) {
    var page = newPage('box');
    page.box.appendChild(unit.el.cloneNode(true));
    cursor.u++;
    cursor.w = 0;
    finishPage(page);
  }

  /** Long code is split by lines so each page's square holds what fits. */
  function buildPrePage(unit) {
    var page = newPage('box');
    var pre = document.createElement('pre');
    page.box.appendChild(pre);
    var from = cursor.w;
    var total = unit.lines.length;
    var lo = from + 1;
    var hi = total;
    pre.textContent = unit.lines.slice(from, hi).join('\n');
    if (page.box.scrollHeight > sq + 0.5) {
      while (lo < hi) {
        var mid = (lo + hi + 1) >> 1;
        pre.textContent = unit.lines.slice(from, mid).join('\n');
        if (page.box.scrollHeight <= sq + 0.5) lo = mid; else hi = mid - 1;
      }
      pre.textContent = unit.lines.slice(from, lo).join('\n');
    } else {
      lo = total;
    }
    if (lo >= total) { cursor.u++; cursor.w = 0; } else { cursor.w = lo; }
    finishPage(page);
  }

  function buildPage() {
    if (cursor.u >= units.length) { done = true; return; }
    var unit = units[cursor.u];
    if (unit.kind === 'text') buildTextPage();
    else if (unit.kind === 'image') buildImagePage(unit);
    else if (unit.kind === 'pre') buildPrePage(unit);
    else buildBoxPage(unit);
    if (cursor.u >= units.length) done = true;
  }

  function pageFor(anchor) {
    var i = 0;
    while (i + 1 < pages.length && cmp(pages[i + 1].start, anchor) <= 0) i++;
    return i;
  }

  /** Builds until the page holding [anchor] and the one after it exist. */
  function buildThrough(anchor) {
    while (!done && (pages.length < 2 || cmp(pages[pages.length - 1].start, anchor) <= 0)) buildPage();
    if (!done) buildPage();
  }

  function background(gen) {
    if (gen !== generation || done) return;
    var t0 = performance.now();
    var before = pages.length;
    while (!done && performance.now() - t0 < SLICE_MS) buildPage();
    // Report when finished, and every 10 pages so the total in the UI keeps up.
    if (done || Math.floor(before / 10) !== Math.floor(pages.length / 10)) {
      post({ event: 'progress', page: current, total: pages.length, done: done });
    }
    if (!done) setTimeout(function () { background(gen); }, 16);
  }

  // ------------------------------------------------------------ navigation

  var animating = [];

  function settle() {
    for (var i = 0; i < animating.length; i++) {
      animating[i].classList.remove('is-leaving', 'in-next', 'in-prev', 'out-next', 'out-prev');
    }
    animating = [];
  }

  function loadImages(i) {
    var p = pages[i];
    if (!p || p.kind !== 'image') return;
    var img = p.el.querySelector('img');
    if (img && img.getAttribute('loading') === 'lazy') img.setAttribute('loading', 'eager');
  }

  function show(i, dir, sourceName) {
    settle();
    var prev = pages[current];
    var next = pages[i];
    if (!next) return;
    if (prev && prev !== next) {
      prev.el.classList.remove('is-current');
      if (dir) {
        prev.el.classList.add('is-leaving', dir > 0 ? 'out-next' : 'out-prev');
        animating.push(prev.el);
      }
    }
    next.el.classList.add('is-current');
    if (dir && prev !== next) {
      next.el.classList.add(dir > 0 ? 'in-next' : 'in-prev');
      animating.push(next.el);
      setTimeout(settle, 190);
    }
    current = i;
    loadImages(i);
    loadImages(i + 1);
    post({ event: 'page', page: current, total: pages.length, done: done, anchor: next.start, source: sourceName || 'api' });
  }

  function turn(delta, sourceName) {
    if (!pages.length) return;
    var target = current + delta;
    while (!done && target >= pages.length - 1) buildPage();
    target = Math.max(0, Math.min(pages.length - 1, target));
    if (target === current) {
      post({ event: 'edge', page: current, total: pages.length, done: done, edge: delta > 0 ? 'end' : 'start' });
      return;
    }
    show(target, delta > 0 ? 1 : -1, sourceName);
  }

  function paginate(anchor) {
    generation++;
    settle();
    book.textContent = '';
    pages = [];
    cursor = { u: 0, w: 0 };
    done = units.length === 0;
    current = -1;
    applyStyle();
    buildShapes();
    var target = anchor || { u: 0, w: 0 };
    buildThrough(target);
    show(pageFor(target), 0);
    var gen = generation;
    setTimeout(function () { background(gen); }, 32);
  }

  // ----------------------------------------------------------------- input

  var touch = null;
  document.addEventListener('touchstart', function (e) {
    // One finger, and not inside a table/code box (those scroll sideways themselves).
    if (e.touches.length !== 1 || (e.target.closest && e.target.closest('.orbit-box'))) { touch = null; return; }
    touch = { x: e.touches[0].clientX, y: e.touches[0].clientY, t: Date.now() };
  }, { passive: true });
  document.addEventListener('touchend', function (e) {
    if (!touch) return;
    var t = e.changedTouches[0];
    var dx = t.clientX - touch.x;
    var dy = t.clientY - touch.y;
    var quick = Date.now() - touch.t < 600;
    touch = null;
    if (quick && Math.abs(dx) > 36 && Math.abs(dx) > 1.5 * Math.abs(dy)) {
      var forward = dx < 0;
      if (rtl) forward = !forward;
      turn(forward ? 1 : -1, 'touch');
    }
  }, { passive: true });
  document.addEventListener('click', function (e) {
    var a = e.target.closest && e.target.closest('a[href^="#"]');
    if (a) e.preventDefault(); // in-page anchors have nowhere to go in a paged view
  });

  // ------------------------------------------------------------------- api

  window.orbitReader = {
    turn: function (delta) { turn(delta, 'bezel'); },
    goTo: function (i) {
      while (!done && i >= pages.length) buildPage();
      show(Math.max(0, Math.min(pages.length - 1, i)), i > current ? 1 : -1, 'api');
    },
    goToAnchor: function (a) {
      if (!a) return;
      buildThrough(a);
      var i = pageFor(a);
      if (i !== current) show(i, 0, 'api');
    },
    setStyle: function (s) {
      var anchor = pages[current] ? pages[current].start : null;
      style = s;
      paginate(anchor);
    },
    state: function () {
      return { page: current, total: pages.length, done: done, anchor: pages[current] ? pages[current].start : null };
    },
  };

  // ----------------------------------------------------------------- start

  function start() {
    collect(source, units);
    source.remove();
    paginate(cfg.anchor || null);
    post({ event: 'ready', page: current, total: pages.length, done: done, anchor: pages[current] ? pages[current].start : null });
  }

  if (document.fonts && document.fonts.ready) document.fonts.ready.then(start);
  else start();
})();
