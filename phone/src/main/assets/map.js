/*
 * Orbit Phone — the page map for the watch. Run with the page scrolled to the top; returns (as
 * JSON) the page size and every link, button and field in document pixels, so the watch can
 * step through links, open them and know what a tap hits without asking the phone.
 *
 * { width, height, title, live, boxes: [[x, y, w, h, kind(0 link, 1 button, 2 field), href, label]] }
 *
 * "live" means the page is mostly something moving (a video, a map, a game): pictures of it
 * would be useless, so the watch shows it with its own engine instead.
 */
(function () {
  'use strict';
  var SELECTOR = 'a[href], button, input:not([type="hidden"]), select, textarea, summary, ' +
    '[role="button"], [role="link"], [onclick], [contenteditable="true"]';
  var MAX = 1500;
  var dpr = window.devicePixelRatio || 1;
  var sx = window.scrollX;
  var sy = window.scrollY;
  var NOT_TEXT = /^(button|submit|reset|checkbox|radio|file|image|range|color)$/i;

  function kindOf(el) {
    var tag = el.tagName;
    if (tag === 'TEXTAREA' || tag === 'SELECT' || el.isContentEditable) return 2;
    if (tag === 'INPUT') return NOT_TEXT.test(el.type) ? 1 : 2;
    if (tag === 'A' && /^https?:/i.test(el.href)) return 0;
    return 1;
  }

  function labelOf(el) {
    var s = el.getAttribute('aria-label') || el.innerText || el.value || el.title || el.placeholder || el.alt || '';
    s = String(s).replace(/\s+/g, ' ').trim();
    return s.length > 80 ? s.slice(0, 79) + '…' : s;
  }

  var boxes = [];
  var nodes = document.querySelectorAll(SELECTOR);
  for (var i = 0; i < nodes.length && boxes.length < MAX; i++) {
    var el = nodes[i];
    if (el.parentElement && el.parentElement.closest(SELECTOR)) continue; // a button inside a link
    var r = el.getBoundingClientRect();
    if (r.width < 2 || r.height < 2) continue;
    var cs = getComputedStyle(el);
    if (cs.visibility === 'hidden' || cs.display === 'none' || cs.pointerEvents === 'none' || el.disabled) continue;
    var kind = kindOf(el);
    boxes.push([
      Math.round((r.left + sx) * dpr), Math.round((r.top + sy) * dpr),
      Math.round(r.width * dpr), Math.round(r.height * dpr),
      kind, kind === 0 ? el.href : '', labelOf(el),
    ]);
  }

  var vw = window.innerWidth;
  var vh = window.innerHeight;
  var live = false;
  var media = document.querySelectorAll('video, canvas, iframe');
  for (var j = 0; j < media.length && !live; j++) {
    var m = media[j];
    var b = m.getBoundingClientRect();
    var shown = Math.max(0, Math.min(b.right, vw) - Math.max(b.left, 0)) * Math.max(0, Math.min(b.bottom, vh) - Math.max(b.top, 0));
    if (shown < vw * vh * 0.4) continue;
    live = m.tagName !== 'IFRAME' || /youtube|vimeo|maps|player|game/i.test(m.src || '');
  }

  var doc = document.documentElement;
  var body = document.body;
  return JSON.stringify({
    width: Math.round(doc.clientWidth * dpr),
    height: Math.round(Math.max(doc.scrollHeight, body ? body.scrollHeight : 0) * dpr),
    title: document.title || '',
    live: live,
    boxes: boxes,
  });
})();
