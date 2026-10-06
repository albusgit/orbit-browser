/*
 * Orbit — reader page bootstrap. reader.html is an extension page:
 *   moz-extension://…/reader.html#u=<article URL>&t=<token>
 * The app hands the article to background.js first (sanitised with jsoup, then laid out by
 * ReaderTemplate.kt); this asks for it by token, fills the page and starts reader.js.
 * The extension's CSP allows no inline script, so article markup can never run anything.
 */
(function () {
  'use strict';
  var params = new URLSearchParams(location.hash.slice(1));
  var token = params.get('t') || '';

  function send(m) {
    browser.runtime.sendMessage(m).catch(function () {});
  }

  // reader.js posts through window.orbitBridge, as page scripts do.
  window.orbitBridge = {
    postMessage: function (s) { send({ type: 'page', data: String(s) }); },
  };

  // Link focus works here too; links.js is only loaded the first time it's asked for.
  var linksLoading = null;
  function withLinks(then) {
    if (window.orbitLinks) return then(window.orbitLinks);
    if (!linksLoading) {
      linksLoading = new Promise(function (resolve) {
        var s = document.createElement('script');
        s.src = 'links.js';
        s.onload = resolve;
        document.body.appendChild(s);
      });
    }
    linksLoading.then(function () { if (window.orbitLinks) then(window.orbitLinks); });
  }

  // Commands from the app (relayed by background.js): page turns, typography, anchors, links.
  browser.runtime.onMessage.addListener(function (m) {
    if (!m || m.target !== 'reader') return;
    var r = window.orbitReader;
    switch (m.name) {
      case 'turn': if (r) r.turn(m.arg); break;
      case 'setStyle': if (r) r.setStyle(m.arg); break;
      case 'goToAnchor': if (r) r.goToAnchor(m.arg); break;
      case 'linksStep': withLinks(function (l) { l.step(m.arg < 0 ? -1 : 1); }); break;
      case 'linksStop': if (window.orbitLinks) window.orbitLinks.stop(); break;
      case 'linksActivate': if (window.orbitLinks) window.orbitLinks.activate(); break;
    }
  });

  // A tap in the middle that isn't on a link opens Orbit's ring menu.
  document.addEventListener('click', function (e) {
    var hit = e.target && e.target.closest && e.target.closest('a[href]');
    send({ type: 'tap', interactive: !!hit });
  }, true);

  browser.runtime.sendMessage({ type: 'readerPayload', token: token }).then(function (p) {
    if (!p) {
      // Reopened from history after the app restarted: the app rebuilds it.
      window.orbitBridge.postMessage(JSON.stringify({ type: 'reader', event: 'missing', url: params.get('u') || '' }));
      return;
    }
    var root = document.documentElement;
    if (p.lang) root.lang = p.lang;
    root.dir = p.dir || 'auto';
    document.title = p.title || 'Orbit';
    document.getElementById('orbit-source').innerHTML = p.html;
    window.__orbitReader = p.config;
    var s = document.createElement('script');
    s.src = 'reader.js';
    document.body.appendChild(s);
  });
})();
