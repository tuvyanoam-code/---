#!/usr/bin/env node
/*
 * Prepares the invitation for the web. Run after replacing invitation.pdf or editing config.js:
 *
 *   node tools/build.mjs [--site-url https://example.com/path/]
 *
 * 1. Renders every PDF page to sharp JPEGs (invitation/page-N.jpg and page-N@2x.jpg; en-page-N… for an English
 *    PDF), so guests see the invitation instantly on any phone instead of waiting for a PDF viewer.
 * 2. Builds the link-preview image WhatsApp / Telegram show (invitation/og.jpg and og-en.jpg, 1200×630).
 * 3. Writes invitation/pages.js (pages per language + cache-busting version), the Hebrew <meta> preview tags in
 *    index.html, and en.html: the same page opening in English with an English preview.
 *
 * Needs poppler-utils (pdftoppm, pdfinfo) and ImageMagick (convert or magick). The GitHub Actions workflow
 * installs them and runs this on every deploy.
 */
import { readFileSync, writeFileSync, mkdirSync, existsSync, readdirSync, unlinkSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const args = process.argv.slice(2);
const argSiteUrl = (() => {
  const i = args.indexOf('--site-url');
  return i >= 0 ? args[i + 1] : '';
})();

/* ---------- config ---------- */
const sandbox = { window: {} };
vm.runInNewContext(readFileSync(join(root, 'config.js'), 'utf8'), sandbox);
const cfg = sandbox.window.WEDDING;
if (!cfg) throw new Error('config.js did not define window.WEDDING');
let siteUrl = argSiteUrl || cfg.siteUrl || '';
if (siteUrl && !siteUrl.endsWith('/')) siteUrl += '/';

/* ---------- tools ---------- */
function has(cmd) {
  try {
    execFileSync(cmd, ['-v'], { stdio: 'ignore' });
    return true;
  } catch (e) {
    return e.code !== 'ENOENT';
  }
}
const magick = has('magick') ? 'magick' : has('convert') ? 'convert' : null;
const run = (cmd, a) => execFileSync(cmd, a, { stdio: ['ignore', 'pipe', 'inherit'] }).toString();

function jpegSize(file) {
  const b = readFileSync(file);
  let i = 2;
  while (i < b.length) {
    if (b[i] !== 0xff) {
      i++;
      continue;
    }
    const marker = b[i + 1];
    const len = b.readUInt16BE(i + 2);
    if (marker >= 0xc0 && marker <= 0xcf && ![0xc4, 0xc8, 0xcc].includes(marker)) {
      return { h: b.readUInt16BE(i + 5), w: b.readUInt16BE(i + 7) };
    }
    i += 2 + len;
  }
  throw new Error('not a jpeg: ' + file);
}

/* ---------- 1. pages and 2. link previews, per language ---------- */
const L = (v, lang) => (v && typeof v === 'object' && !Array.isArray(v) ? (v[lang] != null && v[lang] !== '' && !(Array.isArray(v[lang]) && !v[lang].length) ? v[lang] : lang === 'en' ? '' : v.he) : lang === 'he' ? v : '');
const outDir = join(root, 'invitation');
mkdirSync(outDir, { recursive: true });
const canRender = has('pdftoppm') && has('pdfinfo');
if (!canRender) console.warn('! pdftoppm/pdfinfo missing (install poppler-utils) — skipping page images.');

function renderPdf(lang) {
  const pdfRel = L(cfg.invitationPdf, lang) || (lang === 'he' ? 'invitation.pdf' : '');
  const prefix = lang === 'he' ? '' : `${lang}-`;
  // Old images of this language go first, so a removed English PDF does not leave stale pages behind.
  readdirSync(outDir)
    .filter((f) => new RegExp(`^${prefix}page-\\d+(@2x)?\\.jpg$`).test(f) || f === (lang === 'he' ? 'og.jpg' : `og-${lang}.jpg`))
    .forEach((f) => canRender && unlinkSync(join(outDir, f)));
  if (!pdfRel) return null;
  const pdf = join(root, pdfRel);
  if (!existsSync(pdf)) {
    console.warn(`! ${pdfRel} not found — the ${lang} page will offer the PDF link only.`);
    return { pdf: pdfRel, pages: [], v: '', og: '' };
  }
  if (!canRender) return { pdf: pdfRel, pages: [], v: '', og: '' };
  const v = createHash('sha1').update(readFileSync(pdf)).digest('hex').slice(0, 10);
  const count = parseInt((run('pdfinfo', [pdf]).match(/^Pages:\s+(\d+)/m) || [])[1], 10) || 1;
  const maxPages = Math.min(count, 12);
  const set = { pdf: pdfRel, pages: [], v, og: '' };
  for (let n = 1; n <= maxPages; n++) {
    for (const [suffix, width] of [['', 900], ['@2x', 1800]]) {
      const base = join(outDir, `${prefix}page-${n}${suffix}`);
      run('pdftoppm', ['-q', '-f', String(n), '-l', String(n), '-singlefile', '-jpeg', '-jpegopt', 'quality=86,progressive=y', '-scale-to-x', String(width), '-scale-to-y', '-1', '-aa', 'yes', '-aaVector', 'yes', pdf, base]);
    }
    const { w, h } = jpegSize(join(outDir, `${prefix}page-${n}.jpg`));
    set.pages.push({ w, h, src: `invitation/${prefix}page-${n}.jpg?v=${v}`, src2x: `invitation/${prefix}page-${n}@2x.jpg?v=${v}` });
  }
  console.log(`✓ ${maxPages} page(s) rendered from ${pdfRel}`);

  const ogName = lang === 'he' ? 'og.jpg' : `og-${lang}.jpg`;
  if (magick) {
    run(magick, [
      '-size', '1200x630', 'xc:#fff8f0',
      '-fill', '#ffc23d', '-draw', 'circle 1130,40 1130,250',
      '-fill', '#2fbf9b', '-draw', 'circle 40,640 40,500',
      '-fill', '#d6336c', '-draw', 'circle 180,110 180,122',
      '-fill', '#a78bfa', '-draw', 'circle 1010,520 1010,546',
      '(', join(outDir, `${prefix}page-1@2x.jpg`), '-resize', 'x540', '-bordercolor', '#ffffff', '-border', '10',
      '(', '+clone', '-background', '#221f36', '-shadow', '35x14+0+14', ')', '+swap', '-background', 'none', '-layers', 'merge', '+repage',
      '-background', 'none', '-rotate', '-2', ')',
      '-gravity', 'center', '-geometry', '+0+4', '-composite',
      '-strip', '-quality', '84', '-sampling-factor', '4:2:0', join(outDir, ogName),
    ]);
    console.log(`✓ invitation/${ogName} (link preview)`);
  } else {
    console.warn('! ImageMagick missing — using page 1 as the link preview.');
    writeFileSync(join(outDir, ogName), readFileSync(join(outDir, `${prefix}page-1.jpg`)));
  }
  set.og = `invitation/${ogName}?v=${v}`;
  return set;
}

const manifest = { he: renderPdf('he'), en: renderPdf('en') };
writeFileSync(
  join(outDir, 'pages.js'),
  `/* generated by tools/build.mjs — do not edit */\nwindow.INVITATION_PAGES = ${JSON.stringify(manifest, null, 2)};\n`
);

/* ---------- 3. preview tags: index.html (Hebrew) and en.html (English) ---------- */
const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;');
const m = String(cfg.date || '').match(/^(\d{4})-(\d{2})-(\d{2})/);
const date = m ? new Date(+m[1], +m[2] - 1, +m[3]) : null;
const TEXT = {
  he: { locale: 'he-IL', og: 'he_IL', title: (c) => `${c} מתחתנים!`, cta: 'לחצו לצפייה בהזמנה ולאישור הגעה' },
  en: { locale: 'en-GB', og: 'en_GB', title: (c) => `${c} are getting married!`, cta: 'Tap to view the invitation and RSVP' },
};

function metaFor(lang) {
  const T = TEXT[lang];
  const couple = (L(cfg.couple, lang) || L(cfg.couple, 'he') || []).join(' & ');
  const venue = L(cfg.venue && cfg.venue.name, lang) || L(cfg.venue && cfg.venue.name, 'he') || '';
  const dateText = date ? new Intl.DateTimeFormat(T.locale, { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' }).format(date) : '';
  const title = T.title(couple);
  const desc = [dateText, venue].filter(Boolean).join(' · ') + '. ' + T.cta;
  const set = (manifest[lang] && manifest[lang].og ? manifest[lang] : manifest.he) || {};
  const og = set.og || 'invitation/og.jpg';
  const pageUrl = siteUrl ? siteUrl + (lang === 'en' ? 'en.html' : '') : '';
  return {
    title,
    html: [
      `<title>${esc(title)}</title>`,
      `<meta name="description" content="${esc(desc)}">`,
      `<meta property="og:type" content="website">`,
      `<meta property="og:locale" content="${T.og}">`,
      `<meta property="og:title" content="${esc(title)}">`,
      `<meta property="og:description" content="${esc(desc)}">`,
      pageUrl && `<meta property="og:url" content="${esc(pageUrl)}">`,
      `<meta property="og:image" content="${esc(siteUrl ? siteUrl + og : og)}">`,
      `<meta property="og:image:width" content="1200">`,
      `<meta property="og:image:height" content="630">`,
      `<meta name="twitter:card" content="summary_large_image">`,
      siteUrl && `<link rel="alternate" hreflang="he" href="${esc(siteUrl)}">`,
      siteUrl && `<link rel="alternate" hreflang="en" href="${esc(siteUrl + 'en.html')}">`,
    ]
      .filter(Boolean)
      .join('\n'),
  };
}

const withMeta = (html, meta) =>
  html.replace(/<!-- meta:start[^>]*-->[\s\S]*?<!-- meta:end -->/, `<!-- meta:start (generated by tools/build.mjs from config.js) -->\n${meta}\n<!-- meta:end -->`);

const indexPath = join(root, 'index.html');
const source = readFileSync(indexPath, 'utf8');
const he = metaFor('he');
const heHtml = withMeta(source, he.html);
if (heHtml !== source) writeFileSync(indexPath, heHtml);
console.log(`✓ index.html: "${he.title}"${siteUrl ? ' → ' + siteUrl : ''}`);

const en = metaFor('en');
const enHtml = withMeta(source, en.html)
  .replace('<html lang="he" dir="rtl">', '<html lang="en" dir="ltr" data-page-lang="en">')
  .replace('<!doctype html>', '<!doctype html>\n<!-- generated by tools/build.mjs from index.html — edit index.html, not this file -->');
writeFileSync(join(root, 'en.html'), enHtml);
console.log(`✓ en.html: "${en.title}"`);
