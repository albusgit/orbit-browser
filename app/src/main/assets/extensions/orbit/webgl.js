/*
 * Orbit — WebGL off by default. Registered by background.js in every frame at document start,
 * except on sites opted in under This site → Rich graphics.
 *
 * Content scripts see the page through Xray wrappers, so the page's own canvas prototypes are
 * patched with exportFunction: pages asking for a WebGL context get null and fall back to 2D.
 */
/* global exportFunction */
(function () {
  'use strict';
  var page = window.wrappedJSObject;
  if (!page) return;
  var WEBGL = /^(experimental-)?webgl2?$/i;
  function guard(proto) {
    if (!proto) return;
    var original = proto.getContext;
    exportFunction(function (type) {
      if (typeof type === 'string' && WEBGL.test(type)) return null;
      return original.apply(this, arguments);
    }, proto, { defineAs: 'getContext' });
  }
  try {
    guard(page.HTMLCanvasElement && page.HTMLCanvasElement.prototype);
    guard(page.OffscreenCanvas && page.OffscreenCanvas.prototype);
  } catch (e) { /* a page that froze its prototypes keeps WebGL */ }
})();
