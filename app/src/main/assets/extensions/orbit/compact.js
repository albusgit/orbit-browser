/*
 * Orbit — Compact view. Runs at document start in the main frame of web pages.
 *
 * Lays the page out at a phone's width (360 CSS px) and lets Gecko scale it to fit the watch, so
 * mobile sites show more per screen than at the watch's own ~240 px. It rewrites every viewport
 * <meta>, the page's own included, until the document has loaded.
 */
(function () {
  'use strict';
  if (window.top !== window || window.__orbitCompact) return;
  window.__orbitCompact = true;
  var CONTENT = 'width=360';

  function fix() {
    var metas = document.querySelectorAll('meta[name="viewport" i]');
    for (var i = 0; i < metas.length; i++) if (metas[i].content !== CONTENT) metas[i].content = CONTENT;
    if (!metas.length && document.head) {
      var m = document.createElement('meta');
      m.name = 'viewport';
      m.content = CONTENT;
      document.head.prepend(m);
    }
  }

  var observer = new MutationObserver(fix);
  observer.observe(document, { childList: true, subtree: true });
  fix();
  document.addEventListener('DOMContentLoaded', function () {
    fix();
    observer.disconnect();
  });
})();
