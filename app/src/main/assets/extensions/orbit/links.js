/*
 * Orbit — link focus mode. Injected on demand by background.js; installs window.orbitLinks.
 *
 * The bezel steps through the visible links and controls in reading order (top to bottom,
 * then start to end), the focused one gets a ring, and a tap anywhere activates it. When
 * focus runs past the last visible control the page scrolls (Round Scroll) or turns
 * (Reader) and focus continues on the new content.
 *
 * Posts { type: 'links', event: 'focus'|'activate'|'none'|'edge', href, label, kind, index, count }.
 */
(function () {
  'use strict';
  if (window.orbitLinks) return;

  var SELECTOR = 'a[href], button, input:not([type="hidden"]), select, textarea, summary, ' +
    '[role="button"], [role="link"], [onclick], [contenteditable="true"]';

  var current = null;
  var rings = [];
  var MAX_RINGS = 4;
  var rtl = document.documentElement.dir === 'rtl' || (document.body && getComputedStyle(document.body).direction === 'rtl');

  function post(msg) {
    msg.type = 'links';
    var s = JSON.stringify(msg);
    if (window.orbitBridge) window.orbitBridge.postMessage(s);
  }

  function geometry() {
    var w = window.innerWidth;
    var h = window.innerHeight;
    return { cx: w / 2, cy: h / 2, r: Math.min(w, h) / 2 };
  }

  /**
   * On screen, inside the circle, not hidden and not covered by something else. Returns the
   * first line box: a link that wraps onto a second line still sits where it starts.
   */
  function visibleRect(el, g) {
    var rects = el.getClientRects();
    var r = rects.length ? rects[0] : null;
    if (!r || r.width < 2 || r.height < 2) return null;
    var x = Math.min(Math.max(g.cx, r.left + 2), r.right - 2);
    var y = Math.min(Math.max(g.cy, r.top + 2), r.bottom - 2);
    if (Math.hypot(x - g.cx, y - g.cy) > g.r * 0.94) return null;
    var cs = getComputedStyle(el);
    if (cs.visibility === 'hidden' || cs.pointerEvents === 'none' || el.disabled) return null;
    var hit = document.elementFromPoint(x, y);
    if (hit && hit !== el && !el.contains(hit) && !hit.contains(el)) return null;
    return r;
  }

  function collect() {
    var g = geometry();
    var nodes = document.querySelectorAll(SELECTOR);
    var list = [];
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      // Skip controls nested in another candidate (a button inside a link).
      if (el.parentElement && el.parentElement.closest(SELECTOR)) continue;
      var r = visibleRect(el, g);
      if (r) list.push({ el: el, r: r });
    }
    // Same line if their vertical centres are within half the smaller line height (tiny
    // text and big headings both sort correctly); otherwise top to bottom.
    list.sort(function (a, b) {
      var ca = (a.r.top + a.r.bottom) / 2;
      var cb = (b.r.top + b.r.bottom) / 2;
      var sameRow = Math.abs(ca - cb) < Math.min(a.r.height, b.r.height) / 2;
      if (!sameRow) return ca - cb;
      return rtl ? b.r.right - a.r.right : a.r.left - b.r.left;
    });
    return list;
  }

  function kindOf(el) {
    var tag = el.tagName;
    if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || el.isContentEditable) return 'input';
    if (tag === 'A' && el.getAttribute('href')) return 'link';
    return 'button';
  }

  function labelOf(el) {
    var text = (el.innerText || '').trim();
    if (!text) text = el.getAttribute('aria-label') || el.getAttribute('title') || el.getAttribute('placeholder') || '';
    if (!text) { var img = el.querySelector && el.querySelector('img[alt]'); if (img) text = img.alt; }
    if (!text && el.value) text = String(el.value);
    text = text.replace(/\s+/g, ' ').trim();
    return text.length > 80 ? text.slice(0, 77) + '…' : text;
  }

  /** One ring per line box, so a link that wraps is outlined line by line, not as a block. */
  function placeRing() {
    if (!current || !rings.length) return;
    var rects = current.getClientRects();
    var pad = 3;
    for (var i = 0; i < rings.length; i++) {
      var r = rects[i];
      var s = rings[i].style;
      if (!r || r.width < 1) { s.display = 'none'; continue; }
      s.display = 'block';
      s.left = (r.left - pad) + 'px';
      s.top = (r.top - pad) + 'px';
      s.width = (r.width + 2 * pad) + 'px';
      s.height = (r.height + 2 * pad) + 'px';
    }
  }

  function ensureRing() {
    if (rings.length && rings[0].isConnected) return;
    rings = [];
    for (var i = 0; i < MAX_RINGS; i++) {
      var ring = document.createElement('div');
      var s = ring.style;
      s.position = 'fixed';
      s.pointerEvents = 'none';
      s.zIndex = '2147483647';
      s.borderRadius = '6px';
      s.boxShadow = '0 0 0 2px #8ab4f8, 0 0 10px 3px rgba(138,180,248,0.65)';
      s.transition = 'left 90ms ease-out, top 90ms ease-out, width 90ms ease-out, height 90ms ease-out';
      document.documentElement.appendChild(ring);
      rings.push(ring);
    }
  }

  function focus(el, index, count) {
    current = el;
    ensureRing();
    // In a scrolling page, keep the focused control near the middle of the circle.
    if (!window.orbitReader) {
      var g = geometry();
      var r = el.getBoundingClientRect();
      var mid = (r.top + r.bottom) / 2;
      if (Math.abs(mid - g.cy) > g.r * 0.45) window.scrollBy(0, mid - g.cy);
    }
    placeRing();
    post({
      event: 'focus',
      href: el.tagName === 'A' ? el.href : '',
      label: labelOf(el),
      kind: kindOf(el),
      index: index,
      count: count,
    });
  }

  /** Moves the content one screen in [dir]. Returns false at the start/end. */
  function advanceContent(dir) {
    if (window.orbitReader) {
      var before = window.orbitReader.state().page;
      window.orbitReader.turn(dir);
      return window.orbitReader.state().page !== before;
    }
    var y = window.scrollY;
    window.scrollBy(0, dir * window.innerHeight * 0.55);
    return window.scrollY !== y;
  }

  function step(delta) {
    var dir = delta > 0 ? 1 : -1;
    var list = collect();
    var at = -1;
    for (var i = 0; i < list.length; i++) if (list[i].el === current) { at = i; break; }
    var target = at === -1 ? (dir > 0 ? 0 : list.length - 1) : at + delta;
    if (target >= 0 && target < list.length) {
      focus(list[target].el, target, list.length);
      return;
    }
    // Ran off the visible set: move the content and continue there. Pages or screens without
    // any control are skipped, up to a limit.
    var prevTop = current ? current.getBoundingClientRect().top : null;
    var limit = window.orbitReader ? 40 : 12;
    var home = window.orbitReader ? window.orbitReader.state().page : window.scrollY;
    function restore() {
      // Nothing further: put the content back where the focus is.
      if (window.orbitReader) window.orbitReader.goTo(home); else window.scrollTo(0, home);
      placeRing();
    }
    for (var tries = 0; tries < limit; tries++) {
      if (!advanceContent(dir)) {
        restore();
        post({ event: 'edge', edge: dir > 0 ? 'end' : 'start' });
        return;
      }
      list = collect();
      if (!list.length) continue;
      var pick = -1;
      if (window.orbitReader || prevTop === null) {
        pick = dir > 0 ? 0 : list.length - 1;
      } else {
        // After a scroll the old focus moved up (or down); pick the first control past it.
        var cr = current && current.isConnected ? current.getClientRects() : null;
        var newTop = cr && cr.length ? cr[0].top : -Infinity;
        for (var j = 0; j < list.length; j++) {
          var k = dir > 0 ? j : list.length - 1 - j;
          var t = list[k].r.top;
          if (list[k].el !== current && (dir > 0 ? t > newTop + 1 : t < newTop - 1)) { pick = k; break; }
        }
      }
      if (pick !== -1) { focus(list[pick].el, pick, list.length); return; }
    }
    restore();
    post({ event: 'edge', edge: dir > 0 ? 'end' : 'start' });
  }

  function activate() {
    var el = current;
    if (!el || !el.isConnected) { post({ event: 'none' }); return; }
    var kind = kindOf(el);
    if (kind === 'input') {
      el.focus();
      if (el.tagName === 'SELECT' && el.showPicker) { try { el.showPicker(); } catch (e) { /* not allowed */ } }
    } else {
      el.click();
    }
    post({ event: 'activate', kind: kind, href: el.tagName === 'A' ? el.href : '', label: labelOf(el) });
  }

  function stop() {
    current = null;
    for (var i = 0; i < rings.length; i++) rings[i].remove();
    rings = [];
  }

  window.addEventListener('scroll', placeRing, { passive: true });
  window.addEventListener('resize', placeRing, { passive: true });

  window.orbitLinks = {
    step: step,
    activate: activate,
    stop: stop,
    refresh: function () { if (current && !current.isConnected) stop(); placeRing(); },
  };
})();
