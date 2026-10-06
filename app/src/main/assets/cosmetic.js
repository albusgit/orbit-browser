// Element hiding, uBlock Origin style. Runs at document start in every frame: asks Orbit for the
// frame's site rules, then reports the ids and classes the page uses (now and as it changes) and
// receives just the generic rules filed under them. Orbit answers with CSS text only.
(function () {
  'use strict';
  if (window.__orbitCosmetic) return;
  window.__orbitCosmetic = true;
  var bridge = window.orbitBridge;
  if (!bridge || !bridge.addEventListener || !/^https?:$/.test(location.protocol)) return;

  var MAX_PER_MESSAGE = 2000;
  var sentIds = new Set();
  var sentClasses = new Set();
  var style = null;
  var queue = [];
  var pendingIds = [];
  var pendingClasses = [];
  var timer = 0;

  bridge.addEventListener('message', function (e) {
    if (typeof e.data !== 'string' || !e.data) return;
    if (!style) {
      style = document.createElement('style');
      style.setAttribute('data-orbit', 'hide');
    }
    style.textContent += e.data;
    // Pages sometimes rebuild <head>: keep the rules attached.
    if (!style.isConnected) (document.head || document.documentElement).appendChild(style);
  });

  function send(first, ids, classes) {
    bridge.postMessage(JSON.stringify({ type: 'cosmetic', first: first, ids: ids, classes: classes }));
  }

  function collect(el, ids, classes) {
    var id = el.id;
    if (id && typeof id === 'string' && !sentIds.has(id)) { sentIds.add(id); ids.push(id); }
    var list = el.classList;
    if (list) for (var i = 0; i < list.length; i++) {
      var c = list[i];
      if (!sentClasses.has(c)) { sentClasses.add(c); classes.push(c); }
    }
  }

  function scan(roots, ids, classes) {
    for (var r = 0; r < roots.length; r++) {
      var root = roots[r];
      if (root.nodeType !== 1) continue;
      collect(root, ids, classes);
      var els = root.querySelectorAll('[id],[class]');
      for (var i = 0; i < els.length; i++) collect(els[i], ids, classes);
    }
    for (var a = 0, b = 0; a < ids.length || b < classes.length; a += MAX_PER_MESSAGE, b += MAX_PER_MESSAGE) {
      send(false, ids.slice(a, a + MAX_PER_MESSAGE), classes.slice(b, b + MAX_PER_MESSAGE));
    }
  }

  function flush() {
    timer = 0;
    var roots = queue, ids = pendingIds, classes = pendingClasses;
    queue = [];
    pendingIds = [];
    pendingClasses = [];
    scan(roots, ids, classes);
  }

  send(true, [], []);
  document.addEventListener('DOMContentLoaded', function () {
    scan([document.documentElement], [], []);
    new MutationObserver(function (records) {
      for (var i = 0; i < records.length; i++) {
        var added = records[i].addedNodes;
        for (var k = 0; k < added.length; k++) if (added[k].nodeType === 1) queue.push(added[k]);
        // A changed id or class: just that element, not its whole subtree.
        if (records[i].type === 'attributes') collect(records[i].target, pendingIds, pendingClasses);
      }
      if ((queue.length || pendingIds.length || pendingClasses.length) && !timer) timer = setTimeout(flush, 300);
    }).observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['id', 'class'] });
  }, { once: true });
})();
