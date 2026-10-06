// Shared helpers for the round-check scripts. These mirror RoundGeometry.kt, Injector.kt
// and ReaderTemplate.kt so the browser sees exactly what the WebView sees.
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const here = path.dirname(fileURLToPath(import.meta.url));
export const assetsDir = path.resolve(here, '../../app/src/main/assets');
export const asset = (name) => readFile(path.join(assetsDir, name), 'utf8');

export const SCREENS_PX = [432, 480];
export const DENSITIES = [1.75, 2.0, 2.25];

export function geometry(px, density) {
  const d = px / density;
  const sq = d / Math.SQRT2;
  return { d, sq, inset: (d - sq) / 2 };
}

/** Injector.roundScript */
export async function roundScript(g) {
  const css = await asset('round.css');
  const js = await asset('round.js');
  return `window.__orbit=${JSON.stringify(g)};\nwindow.__orbitCss=${JSON.stringify(css)};\n${js}`;
}

/** Injector.extractScript */
export async function extractScript(force) {
  const parts = await Promise.all(['Readability-readerable.js', 'Readability.js', 'extract.js'].map(asset));
  return `(function(){var orbitForce=${force ? 'true' : 'false'};\n${parts.join('\n')}\n})();`;
}

const escapeHtml = (s) => s.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);

/** ReaderTemplate.build */
export async function readerHtml(article, g, style, anchor) {
  const [template, css, script] = await Promise.all([asset('reader.html'), asset('reader.css'), asset('reader.js')]);
  const minutes = Math.max(1, Math.floor(article.words / 220));
  const meta = [...new Set([article.byline.trim(), article.siteName.trim(), `${minutes} min read`].filter(Boolean))].join(' · ');
  const config = JSON.stringify({ d: g.d, sq: g.sq, inset: g.inset, style, anchor: anchor ?? null });
  const values = {
    LANG: /^[A-Za-z]{1,8}(-[A-Za-z0-9]{1,8})*$/.test(article.lang) ? article.lang : '',
    DIR: ['rtl', 'ltr'].includes((article.dir || '').toLowerCase()) ? article.dir.toLowerCase() : 'auto',
    NONCE: 'testnonce',
    TITLE: escapeHtml(article.title),
    META: escapeHtml(meta),
    CSS: css,
    CONTENT: article.content,
    CONFIG: config,
    SCRIPT: script,
  };
  return template.replace(/\{\{([A-Z]+)\}\}/g, (m, k) => values[k] ?? m);
}
