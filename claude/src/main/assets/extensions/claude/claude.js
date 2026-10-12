/*
 * Orbit for Claude — the page side of the interface layer. A content script in claude.ai's
 * main frame, from document start, in the extension's sandbox (the page can't see or fake it).
 *
 * The watch's own buttons (speak, type, stop) replace claude.ai's message box: the box is
 * moved off screen, not removed, so text can still be put into it and sent. If a send doesn't
 * go through (claude.ai changed its markup), the box comes back and you type in the page.
 *
 *   to the app      state { path, title, composer, busy, native }   whenever it changes
 *                   result { cmd, id, ok, reason }                  after send / stop
 *                   scrolled { edge }                               after a bezel step
 *   from the app    send { text, id }   stop { id }   scroll { dy }   layout { bottom }
 *                   native { on }       (false: show claude.ai's own message box again)
 *
 * claude.ai's markup changes without notice, so every selector has fallbacks, newest first.
 */
(function () {
  'use strict';
  if (window.__orbitClaude) return;
  // Only the chat window: never a frame, never a popup (the Google sign-in window).
  if (window.top !== window || window.opener) return;
  window.__orbitClaude = true;

  var STATE_MS = 150;
  var SEND_WAIT_MS = 3000;
  var VERIFY_MS = 1500;

  var COMPOSER = [
    'div.ProseMirror[contenteditable="true"]',
    '[contenteditable="true"][role="textbox"]',
    'fieldset [contenteditable="true"]',
    'fieldset textarea',
    'textarea',
  ];
  var SEND = ['button[aria-label="Send message"]', 'button[aria-label="Send Message"]'];
  var SEND_NEAR = 'button[aria-label*="send" i], button[type="submit"]';
  var STOP = ['button[aria-label="Stop response"]', 'button[aria-label="Stop Response"]'];
  var STOP_NEAR = 'button[aria-label*="stop" i]';

  var CSS =
    'html.orbit-native [data-orbit-composer]{position:fixed!important;left:-10000px!important;top:0!important;' +
    'opacity:0!important;pointer-events:none!important}' +
    'html.orbit-native [data-orbit-scroller]{padding-bottom:var(--orbit-bottom,0px)!important}';

  /** Page-world constructors, so events we make look like the page's own (Firefox Xrays). */
  var W = window.wrappedJSObject || window;
  var clone = typeof cloneInto === 'function'
    ? function (o) { return cloneInto(o, window, { wrapReflectors: true }); }
    : function (o) { return o; };

  var native = true;
  var bottom = 0;
  var last = '';
  var timer = 0;

  function toApp(m) {
    try {
      browser.runtime.sendMessage(m).catch(function () {});
    } catch (e) { /* the extension is reloading */ }
  }

  function find(list, root) {
    for (var i = 0; i < list.length; i++) {
      var el = (root || document).querySelector(list[i]);
      if (el) return el;
    }
    return null;
  }

  function composer() {
    return find(COMPOSER);
  }

  /** claude.ai's message box around [el]: its fieldset, or the lowest box-sized ancestor with a button. */
  function composerBox(el) {
    var fs = el.closest('fieldset');
    if (fs) return fs;
    for (var n = el.parentElement, i = 0; n && n !== document.body && i < 8; n = n.parentElement, i++) {
      if (n.querySelector('button') && n.offsetHeight < window.innerHeight * 0.6) return n;
    }
    return null;
  }

  function sendButton(el) {
    var box = el && composerBox(el);
    return find(SEND) || (box && box.querySelector(SEND_NEAR));
  }

  function stopButton() {
    var el = composer();
    var box = el && composerBox(el);
    return find(STOP) || (box && box.querySelector(STOP_NEAR));
  }

  function enabled(b) {
    return !b.disabled && b.getAttribute('aria-disabled') !== 'true';
  }

  function textOf(el) {
    return el.tagName === 'TEXTAREA' ? el.value : el.textContent || '';
  }

  // ------------------------------------------------------------------ layout

  function mark(attr, el) {
    var old = document.querySelectorAll('[' + attr + ']');
    for (var i = 0; i < old.length; i++) if (old[i] !== el) old[i].removeAttribute(attr);
    if (el && !el.hasAttribute(attr)) el.setAttribute(attr, '');
  }

  /** The element that scrolls the conversation: the scrollable ancestor at the middle of the screen. */
  function scroller() {
    var cached = document.querySelector('[data-orbit-scroller]');
    if (cached && cached.isConnected && cached.scrollHeight > cached.clientHeight + 4) return cached;
    var hit = document.elementFromPoint(window.innerWidth / 2, window.innerHeight / 2);
    for (var el = hit; el && el !== document.body && el !== document.documentElement; el = el.parentElement) {
      var o = getComputedStyle(el).overflowY;
      if ((o === 'auto' || o === 'scroll') && el.scrollHeight > el.clientHeight + 4) {
        mark('data-orbit-scroller', el);
        return el;
      }
    }
    return document.scrollingElement || document.documentElement;
  }

  /** The app's layout can arrive before the root element exists: kept, and applied on start too. */
  function applyLayout(m) {
    if (m) bottom = Math.max(0, Math.min(200, Number(m.bottom) || 0));
    if (document.documentElement) document.documentElement.style.setProperty('--orbit-bottom', bottom + 'px');
  }

  function setNative(on) {
    native = !!on;
    report();
  }

  // ------------------------------------------------------------------ state

  function report() {
    timer = 0;
    var c = composer();
    var on = native && !!c;
    document.documentElement.classList.toggle('orbit-native', on);
    mark('data-orbit-composer', on ? composerBox(c) : null);
    if (on) scroller();
    var s = {
      type: 'state',
      path: location.pathname,
      title: document.title || '',
      composer: !!c,
      busy: !!stopButton(),
      native: on,
    };
    var json = JSON.stringify(s);
    if (json !== last) {
      last = json;
      toApp(s);
    }
  }

  function soon() {
    if (!timer) timer = setTimeout(report, STATE_MS);
  }

  // ------------------------------------------------------------------ commands

  function insert(el, text) {
    if (el.tagName === 'TEXTAREA') {
      // The prototype's setter, past React's own on the element, then the input React listens for.
      Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set.call(el, text);
      el.dispatchEvent(new W.Event('input', clone({ bubbles: true })));
      return;
    }
    var range = document.createRange();
    range.selectNodeContents(el);
    var sel = window.getSelection();
    sel.removeAllRanges();
    sel.addRange(range);
    if (document.execCommand('insertText', false, text)) return;
    // No insertText: a paste, which ProseMirror handles itself.
    try {
      var dt = new W.DataTransfer();
      dt.setData('text/plain', text);
      el.dispatchEvent(new W.ClipboardEvent('paste', clone({ bubbles: true, cancelable: true, clipboardData: dt })));
    } catch (e) { /* verify() reports it as not sent */ }
  }

  function send(text, id) {
    text = String(text || '');
    var el = composer();
    if (!text.trim() || !el) return result('send', id, false, el ? 'empty' : 'no-composer');
    // No soft keyboard for our own focus: the watch's keyboard already did its job.
    var mode = el.getAttribute('inputmode');
    el.setAttribute('inputmode', 'none');
    el.focus();
    insert(el, text);
    var start = Date.now();
    (function click() {
      var b = sendButton(el);
      if (b && enabled(b)) {
        b.click();
      } else if (Date.now() - start < SEND_WAIT_MS) {
        setTimeout(click, 100);
        return;
      } else {
        el.dispatchEvent(new W.KeyboardEvent('keydown', clone({ key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true })));
      }
      el.blur();
      if (mode == null) el.removeAttribute('inputmode');
      else el.setAttribute('inputmode', mode);
      setTimeout(function () { verify(text, id); }, VERIFY_MS);
    })();
  }

  /** Sent means the text left the message box. If it's still there, give the page its box back. */
  function verify(text, id) {
    var el = composer();
    var stuck = !!el && textOf(el).indexOf(text.slice(0, 40)) >= 0;
    if (stuck) setNative(false);
    result('send', id, !stuck, stuck ? 'not-sent' : null);
  }

  function stop(id) {
    var b = stopButton();
    if (b) b.click();
    result('stop', id, !!b, b ? null : 'no-button');
  }

  function scroll(dy) {
    var el = scroller();
    var root = el === document.scrollingElement || el === document.documentElement;
    var top = root ? window.scrollY : el.scrollTop;
    var max = el.scrollHeight - (root ? window.innerHeight : el.clientHeight);
    var edge = (dy < 0 && top <= 0) || (dy > 0 && top >= max - 1);
    if (!edge) (root ? window : el).scrollBy({ top: dy, behavior: 'smooth' });
    toApp({ type: 'scrolled', edge: edge });
  }

  function result(cmd, id, ok, reason) {
    toApp({ type: 'result', cmd: cmd, id: id == null ? null : id, ok: ok, reason: reason || null });
  }

  browser.runtime.onMessage.addListener(function (m) {
    if (!m || typeof m.cmd !== 'string') return;
    switch (m.cmd) {
      case 'send': send(m.text, m.id); break;
      case 'stop': stop(m.id); break;
      case 'scroll': scroll(Number(m.dy) || 0); break;
      case 'layout': applyLayout(m); break;
      case 'native': setNative(m.on); break;
    }
  });

  // ------------------------------------------------------------------ start

  function start() {
    var style = document.createElement('style');
    style.textContent = CSS;
    (document.head || document.documentElement).appendChild(style);
    applyLayout(null);
    new MutationObserver(soon).observe(document.documentElement, {
      subtree: true,
      childList: true,
      attributes: true,
      attributeFilter: ['aria-label', 'disabled', 'aria-disabled'],
    });
    // pushState navigations and title changes don't always touch the DOM we watch.
    setInterval(soon, 1000);
    soon();
  }

  try {
    browser.runtime.sendMessage({ type: 'hello' }).then(applyLayout, function () {});
  } catch (e) { /* the extension is reloading */ }

  // At document start the root element may not exist yet.
  if (document.documentElement) {
    start();
  } else {
    var wait = new MutationObserver(function () {
      if (!document.documentElement) return;
      wait.disconnect();
      start();
    });
    wait.observe(document, { childList: true });
  }
})();
