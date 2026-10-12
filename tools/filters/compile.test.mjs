// node --test tools/filters/
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseNetwork, parseCosmetic, genericKey, compile, ALL_REQUESTS, T, DOCUMENT, GENERICHIDE } from './compile.mjs';

test('network filters keep what Orbit can honour', () => {
  assert.deepEqual(parseNetwork('||ads.example.com^'), { allow: false, important: false, party: 0, mask: ALL_REQUESTS, domains: '', pattern: '||ads.example.com^' });
  assert.equal(parseNetwork('||x.com^$third-party').party, 3);
  assert.equal(parseNetwork('||x.com^$~third-party').party, 1);
  assert.equal(parseNetwork('||x.com^$script,image').mask, T.script | T.image);
  assert.equal(parseNetwork('||x.com^$~script').mask, ALL_REQUESTS & ~T.script);
  assert.equal(parseNetwork('/ads/*$domain=a.com|~b.a.com').domains, 'a.com|~b.a.com');
  assert.equal(parseNetwork('/Banner_*').pattern, '/banner_');
  assert.equal(parseNetwork('||x.com^$important').important, true);
});

test('network filters Orbit drops', () => {
  assert.equal(parseNetwork('/^https?:\\/\\/ads\\./'), null); // regex
  assert.equal(parseNetwork('||popunder.example^$popup'), null); // no popups in Orbit
  assert.equal(parseNetwork('||malware.example^$document'), null); // never block the page itself
  assert.equal(parseNetwork('||x.com^$csp=script-src none'), null);
  assert.equal(parseNetwork('||x.com/ga.js$script,redirect=noop.js'), null);
  assert.equal(parseNetwork('*$third-party'), null); // would match everything
  assert.equal(parseNetwork('/ads/$domain=example.*'), null); // entity-only domains
  assert.equal(parseNetwork('! comment'), null);
});

test('exceptions keep page-level kinds', () => {
  const doc = parseNetwork('@@||trusted.example^$document');
  assert.equal(doc.allow, true);
  assert.equal(doc.mask, DOCUMENT);
  assert.equal(parseNetwork('@@||plain.example^$generichide').mask, GENERICHIDE);
  // Unknown options on exceptions are ignored (allows more), but csp/redirect exceptions go.
  assert.equal(parseNetwork('@@||x.com^$someday-option').mask, ALL_REQUESTS);
  assert.equal(parseNetwork('@@||x.com^$csp'), null);
});

test('cosmetic filters', () => {
  assert.deepEqual(parseCosmetic('##.ad-slot'), { kind: 'hide', domains: '', selector: '.ad-slot' });
  assert.deepEqual(parseCosmetic('a.com,~b.a.com##.promo'), { kind: 'hide', domains: 'a.com|~b.a.com', selector: '.promo' });
  assert.equal(parseCosmetic('a.com#@#.promo').kind, 'allow');
  assert.equal(parseCosmetic('a.com#?#div:-abp-has(.ad)'), null);
  assert.equal(parseCosmetic('a.com##+js(set, x, 1)'), null);
  assert.equal(parseCosmetic('a.com##div:has-text(Sponsored)'), null);
  assert.equal(parseCosmetic('##div{color:red}'), null);
});

test('generic keys are a class or id the selector needs', () => {
  assert.equal(genericKey('.ad-slot'), '.ad-slot');
  assert.equal(genericKey('#banner > div'), '#banner');
  assert.equal(genericKey('ins.adsbygoogle[data-ad-slot]'), '.adsbygoogle');
  assert.equal(genericKey('[id^="div-gpt-ad"]'), '');
  assert.equal(genericKey('a[href*=".ad"] > .x'), '');
  assert.equal(genericKey('.a\\:b'), '');
});

test('compile sorts hosts apart and applies generic exceptions', () => {
  const r = compile([{ text: [
    '||ads.example^', '||track.example^$third-party', '||ads.example^$third-party',
    '/banner/*', '@@||ok.example/banner/', '##.ad', '##.keep', '#@#.keep', '~quiet.example##.sidebar-ad',
    'news.example##.promo',
  ].join('\n') }]);
  assert.deepEqual(r.hosts, ['ads.example']);
  assert.deepEqual(r.hosts3p, ['track.example']);
  assert.ok(r.filters.includes('b\t0\t3ff\t\t/banner/'));
  assert.ok(r.filters.includes('a\t0\t3ff\t\t||ok.example/banner/'));
  assert.ok(r.generic.includes('.ad\t\t.ad'));
  assert.ok(!r.generic.some(l => l.endsWith('.keep')));
  assert.ok(r.generic.includes('.sidebar-ad\tquiet.example\t.sidebar-ad'));
  assert.deepEqual(r.specific, ['news.example\t.promo']);
});
