// Shared helpers for the round-check scripts. These mirror RoundGeometry.kt, the Orbit
// extension (background.js, reader-boot.js) and ReaderTemplate.kt, so Chromium runs the same
// page scripts the watch runs in GeckoView.
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const here = path.dirname(fileURLToPath(import.meta.url));
export const assetsDir = path.resolve(here, '../../app/src/main/assets/extensions/orbit');
export const asset = (name) => readFile(path.join(assetsDir, name), 'utf8');

export const SCREENS_PX = [432, 480];
export const DENSITIES = [1.75, 2.0, 2.25];

export function geometry(px, density) {
  const d = px / density;
  const sq = d / Math.SQRT2;
  return { d, sq, inset: (d - sq) / 2 };
}

/**
 * background.js setRound: the geometry prefix + round.js. On the watch it is registered for web
 * pages only, so it never runs on the reader (an extension page); the checks serve their reader
 * stand-in at #orbit-reader, which is skipped here to match.
 */
export async function roundScript(g) {
  const css = await asset('round.css');
  const js = await asset('round.js');
  return `if (location.hash !== '#orbit-reader') {\nwindow.__orbit=${JSON.stringify(g)};\nwindow.__orbitCss=${JSON.stringify(css)};\n${js}\n}`;
}

/** background.js inject('extract'): orbitForce, then Readability and extract.js, in one sandbox. */
export async function extractScript(force) {
  const parts = await Promise.all(['Readability-readerable.js', 'Readability.js', 'extract.js'].map(asset));
  return `(function(){var orbitForce=${force ? 'true' : 'false'};\n${parts.join('\n')}\n})();`;
}

const escapeHtml = (s) => s.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);

/** ReaderTemplate.payload as reader.html + reader-boot.js lay it out, inlined into one page. */
export async function readerHtml(article, g, style, anchor) {
  const [css, script] = await Promise.all([asset('reader.css'), asset('reader.js')]);
  const minutes = Math.max(1, Math.floor(article.words / 220));
  const meta = [...new Set([article.byline.trim(), article.siteName.trim(), `${minutes} min read`].filter(Boolean))].join(' · ');
  const config = JSON.stringify({ d: g.d, sq: g.sq, inset: g.inset, style, anchor: anchor ?? null });
  const lang = /^[A-Za-z]{1,8}(-[A-Za-z0-9]{1,8})*$/.test(article.lang) ? article.lang : '';
  const dir = ['rtl', 'ltr'].includes((article.dir || '').toLowerCase()) ? article.dir.toLowerCase() : 'auto';
  const title = article.title || article.siteName || article.url || '';
  const header = `<header class="orbit-title-block"><h1 class="orbit-title">${escapeHtml(title)}</h1>` +
    `<p class="orbit-meta">${escapeHtml(meta)}</p></header>\n`;
  return `<!doctype html><html lang="${lang}" dir="${dir}"><head><meta charset="utf-8">` +
    '<meta name="viewport" content="width=device-width, initial-scale=1, minimum-scale=1, maximum-scale=1, user-scalable=no">' +
    `<meta name="color-scheme" content="dark"><title>${escapeHtml(title)}</title><style>${css}</style></head><body>` +
    `<div id="orbit-source" hidden>${header}${article.content}</div><div id="orbit-book" aria-live="polite"></div>` +
    `<script>window.__orbitReader = ${config};</script><script>${script}</script></body></html>`;
}
