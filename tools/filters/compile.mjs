#!/usr/bin/env node
// Compiles EasyList + EasyPrivacy (Adblock Plus / uBlock Origin syntax) into the compact,
// pre-normalised lists Orbit ships in app/src/main/assets/filters/. The watch never parses raw
// filter syntax: it loads these files and indexes them (FilterEngine.kt, Cosmetics.kt).
//
//   node tools/filters/compile.mjs              fetch the current lists and compile
//   node tools/filters/compile.mjs --from DIR   compile DIR/easylist.txt + DIR/easyprivacy.txt
//
// What is kept, and what is dropped on purpose:
// - Network filters: host filters (||host^), patterns with * ^ | || anchors, $third-party,
//   resource types, $domain=, $important, and exceptions (@@), including the page-level
//   $document, $elemhide and $generichide exceptions.
// - Dropped: regex filters, $popup-only filters (Orbit never opens popups), blocking $document
//   filters (Orbit never blocks a page the user opened), and options WebView can't honour
//   ($csp, $redirect, $removeparam, $rewrite, $header, ...). An unknown option drops a blocking
//   filter (block less, never break more) but is ignored on an exception (allow more).
// - Cosmetic: ## and #@# with plain CSS selectors. Procedural and scriptlet filters (#?#, #$#,
//   ##+js, :has-text, :xpath, ...) need a script engine in every page and are dropped.

import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, '../..');

export const LISTS = [
  { name: 'EasyList', file: 'easylist.txt', url: 'https://easylist.to/easylist/easylist.txt' },
  { name: 'EasyPrivacy', file: 'easyprivacy.txt', url: 'https://easylist.to/easylist/easyprivacy.txt' },
];

// Resource-type bits; FilterEngine.kt uses the same values.
export const T = {
  script: 1, image: 2, stylesheet: 4, xmlhttprequest: 8, subdocument: 16,
  ping: 32, websocket: 64, media: 128, font: 256, other: 512,
};
export const ALL_REQUESTS = 0x3ff;
export const DOCUMENT = 0x400;
export const ELEMHIDE = 0x800;
export const GENERICHIDE = 0x1000;

const TYPE_ALIASES = {
  script: 'script', image: 'image', img: 'image', stylesheet: 'stylesheet', css: 'stylesheet',
  xmlhttprequest: 'xmlhttprequest', xhr: 'xmlhttprequest', subdocument: 'subdocument', frame: 'subdocument',
  ping: 'ping', beacon: 'ping', websocket: 'websocket', media: 'media', font: 'font',
  object: 'other', 'object-subrequest': 'other', other: 'other', webrtc: 'other',
};
// Exceptions for things Orbit never blocks: dropping them changes nothing.
const EXCEPTION_ONLY_FOR_UNSUPPORTED = new Set([
  'csp', 'redirect', 'redirect-rule', 'rewrite', 'removeparam', 'queryprune', 'header', 'permissions',
  'replace', 'urltransform', 'badfilter',
]);

const PURE_HOST = /^\|\|([a-z0-9-]+(?:\.[a-z0-9-]+)+)\^$/;
const OPTIONS = /^[~\w-]+(=[^,]*)?(,[~\w-]+(=[^,]*)?)*$/;

/** One network filter line → a normalised record, or null when Orbit can't honour it. */
export function parseNetwork(line) {
  let text = line.trim();
  if (!text || text.startsWith('!') || text.startsWith('[')) return null;
  const allow = text.startsWith('@@');
  if (allow) text = text.slice(2);

  let pattern = text;
  let opts = '';
  const dollar = text.lastIndexOf('$');
  if (dollar >= 0 && OPTIONS.test(text.slice(dollar + 1))) {
    pattern = text.slice(0, dollar);
    opts = text.slice(dollar + 1);
  }
  if (pattern.length > 1 && pattern.startsWith('/') && pattern.endsWith('/')) return null; // regex

  let party = 0;
  let types = 0;
  let negTypes = 0;
  let hadPositive = false;
  let page = 0;
  let important = false;
  let domains = '';
  for (const raw of opts ? opts.split(',') : []) {
    const [nameRaw, value = ''] = raw.split(/=(.*)/s);
    const neg = nameRaw.startsWith('~');
    const name = (neg ? nameRaw.slice(1) : nameRaw).toLowerCase();
    if (name === 'third-party' || name === '3p') party = neg ? 1 : 3;
    else if (name === 'first-party' || name === '1p') party = neg ? 3 : 1;
    else if (name === 'domain' || name === 'from') domains = normaliseDomains(value.split('|'));
    else if (name === 'important') important = true;
    else if (name === 'all') hadPositive = true, types |= ALL_REQUESTS;
    else if (name === 'popup' || name === 'popunder') { if (!neg) hadPositive = true; }
    else if (name === 'document' || name === 'doc') { if (!neg) { hadPositive = true; page |= DOCUMENT; } }
    else if (name === 'elemhide' || name === 'ehide') { hadPositive = true; page |= ELEMHIDE; }
    else if (name === 'generichide' || name === 'ghide') { hadPositive = true; page |= GENERICHIDE; }
    else if (TYPE_ALIASES[name]) {
      const bit = T[TYPE_ALIASES[name]];
      if (neg) negTypes |= bit; else { types |= bit; hadPositive = true; }
    } else {
      if (!allow || EXCEPTION_ONLY_FOR_UNSUPPORTED.has(name)) return null;
      // Unknown option on an exception: ignore it, which only ever allows more.
    }
  }
  if (domains === null) return null;

  // Request types this filter applies to; page-level kinds ($document, $elemhide, $generichide)
  // only mean something on exceptions.
  let mask;
  if (negTypes) mask = (types || ALL_REQUESTS) & ~negTypes;
  else if (types) mask = types;
  else mask = hadPositive ? 0 : ALL_REQUESTS;
  if (!allow) page = 0;
  if ((mask | page) === 0) return null; // $popup-only, blocking $document, ...

  pattern = pattern.toLowerCase();
  // Leading/trailing * add nothing; strip them so the anchors and tokens are clear.
  if (!pattern.startsWith('|')) pattern = pattern.replace(/^\*+/, '');
  if (!pattern.endsWith('|')) pattern = pattern.replace(/\*+$/, '');
  if (pattern === '' || pattern === '|' || pattern === '||') {
    // Matches every URL: only sane when scoped to sites or the page level.
    if (!domains && !page) return null;
    pattern = '';
  }
  if (/\s/.test(pattern)) return null;

  return { allow, important: important && !allow, party, mask: mask | page, domains, pattern };
}

/** "a.com|~b.a.com" → normalised, or null when every included domain is unusable (entity *). */
function normaliseDomains(list) {
  const kept = [];
  let positives = 0;
  let droppedPositive = false;
  for (const d of list.map(s => s.trim().toLowerCase()).filter(Boolean)) {
    const neg = d.startsWith('~');
    const host = neg ? d.slice(1) : d;
    if (!/^[a-z0-9.-]+$/.test(host) || !host.includes('.')) {
      if (!neg) droppedPositive = true; // example.* entities, IPs with ports, ...
      continue;
    }
    if (!neg) positives++;
    kept.push(d);
  }
  if (droppedPositive && positives === 0) return null;
  return kept.join('|');
}

const PROCEDURAL = /:-abp-|:has-text|:contains\(|:xpath|:upward|:remove\(|:style\(|:matches-|:min-text-length|:watch-attr|:others|:nth-ancestor|:if\(|:if-not|:spath|:matches-path|:remove-attr|:remove-class/;

/** One cosmetic line → { kind: 'hide' | 'allow', domains, selector } or null. */
export function parseCosmetic(line) {
  const m = /^([^#]*)(#@#|##)(.+)$/.exec(line.trim());
  if (!m) return null;
  const [, domainPart, sep, selector] = m;
  if (selector.startsWith('+js') || selector.startsWith('^') || PROCEDURAL.test(selector)) return null;
  if (selector.includes('{') || selector.includes('}')) return null; // would break out of the rule
  let domains = '';
  if (domainPart) {
    domains = normaliseDomains(domainPart.split(','));
    if (domains === null) return null;
  }
  return { kind: sep === '##' ? 'hide' : 'allow', domains, selector: selector.trim() };
}

/** The class or id a generic selector can't match without: ".ad", "#banner", or "" (always). */
export function genericKey(selector) {
  if (selector.includes('\\')) return '';
  // Only the first compound selector (before any combinator or list comma).
  let depth = 0;
  let end = selector.length;
  for (let i = 0; i < selector.length; i++) {
    const c = selector[i];
    if (c === '[' || c === '(') depth++;
    else if (c === ']' || c === ')') depth--;
    else if (depth === 0 && (c === ' ' || c === '>' || c === '+' || c === '~' || c === ',')) { end = i; break; }
  }
  const first = selector.slice(0, end);
  depth = 0;
  for (let i = 0; i < first.length; i++) {
    const c = first[i];
    if (c === '[' || c === '(') depth++;
    else if (c === ']' || c === ')') depth--;
    else if (depth === 0 && (c === '.' || c === '#')) {
      const id = /^[A-Za-z_-][\w-]*/.exec(first.slice(i + 1));
      if (id) return c + id[0];
    }
  }
  return '';
}

export function compile(sources) {
  const hosts = new Set();
  const hosts3p = new Set();
  const filters = new Map(); // dedupe by output line
  const generic = new Map(); // selector → excluded domains
  const genericAllowed = new Set();
  const specific = new Map(); // `${domains}\t${selector}`
  const exceptions = new Set();
  const stats = { network: 0, cosmetic: 0, dropped: 0 };

  for (const { text } of sources) {
    for (const raw of text.split(/\r?\n/)) {
      const line = raw.trim();
      if (!line || line.startsWith('!') || line.startsWith('[')) continue;
      if (/#[@?$%]?[#?$]/.test(line) && /^[^/]*#[@?$%]*#/.test(line)) {
        const c = parseCosmetic(line);
        if (!c) { stats.dropped++; continue; }
        stats.cosmetic++;
        if (c.kind === 'allow') {
          if (c.domains) exceptions.add(`${c.domains}\t${c.selector}`); else genericAllowed.add(c.selector);
        } else if (!c.domains || c.domains.split('|').every(d => d.startsWith('~'))) {
          generic.set(c.selector, c.domains.split('|').filter(Boolean).map(d => d.slice(1)).join('|'));
        } else {
          specific.set(`${c.domains}\t${c.selector}`, true);
        }
        continue;
      }
      const f = parseNetwork(line);
      if (!f) { stats.dropped++; continue; }
      stats.network++;
      const host = PURE_HOST.exec(f.pattern);
      if (host && !f.allow && !f.important && !f.domains && f.mask === ALL_REQUESTS && f.party !== 1) {
        (f.party === 3 ? hosts3p : hosts).add(host[1]);
        continue;
      }
      const kind = f.allow ? 'a' : f.important ? 'B' : 'b';
      filters.set(`${kind}\t${f.party}\t${f.mask.toString(16)}\t${f.domains}\t${f.pattern}`, true);
    }
  }
  for (const h of hosts) hosts3p.delete(h);
  for (const s of genericAllowed) generic.delete(s);

  const genericLines = [...generic].map(([sel, excl]) => `${genericKey(sel)}\t${excl}\t${sel}`).sort();
  return {
    hosts: [...hosts].sort(),
    hosts3p: [...hosts3p].sort(),
    filters: [...filters.keys()].sort(),
    generic: genericLines,
    specific: [...specific.keys()].sort(),
    exceptions: [...exceptions].sort(),
    stats,
  };
}

function header(sources) {
  const lines = ['! Orbit content filters, compiled by tools/filters/compile.mjs. Do not edit by hand.'];
  for (const s of sources) lines.push(`! ${s.name}${s.version ? ` version ${s.version}` : ''}${s.url ? ` (${s.url})` : ''}`);
  lines.push('! EasyList and EasyPrivacy: (c) The EasyList authors, https://easylist.to,');
  lines.push('! dual-licensed GPLv3 and CC BY-SA 3.0. This file is a derived work under the same terms.');
  return lines.join('\n');
}

async function main() {
  const args = process.argv.slice(2);
  const fromIdx = args.indexOf('--from');
  const outIdx = args.indexOf('--out');
  const out = outIdx >= 0 ? resolve(args[outIdx + 1]) : join(root, 'app/src/main/assets/filters');
  const sources = [];
  for (const list of LISTS) {
    const text = fromIdx >= 0
      ? readFileSync(join(resolve(args[fromIdx + 1]), list.file), 'utf8')
      : await (await fetch(list.url)).text();
    const version = /^! Version: (\S+)/m.exec(text)?.[1];
    sources.push({ ...list, text, version });
  }
  sources.push({ name: 'Orbit extras', url: 'tools/filters/extra.txt', text: readFileSync(join(here, 'extra.txt'), 'utf8') });

  const r = compile(sources);
  mkdirSync(out, { recursive: true });
  const head = header(sources);
  writeFileSync(join(out, 'network.txt'), [
    head, '#hosts', ...r.hosts, '#hosts3p', ...r.hosts3p, '#filters', ...r.filters, '',
  ].join('\n'));
  writeFileSync(join(out, 'cosmetic.txt'), [
    head, '#generic', ...r.generic, '#specific', ...r.specific, '#exceptions', ...r.exceptions, '',
  ].join('\n'));
  const always = r.generic.filter(l => l.startsWith('\t')).length;
  console.log(`hosts ${r.hosts.length}, third-party hosts ${r.hosts3p.length}, pattern filters ${r.filters.length}`);
  console.log(`generic selectors ${r.generic.length} (${always} unkeyed), site selectors ${r.specific.length}, exceptions ${r.exceptions.length}`);
  console.log(`dropped ${r.stats.dropped} lines Orbit can't honour`);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch(e => { console.error(e); process.exit(1); });
}
