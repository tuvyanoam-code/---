#!/usr/bin/env node
/*
 * Prepares the invitation for the web. Run after replacing invitation.pdf or editing config.js:
 *
 *   node tools/build.mjs [--site-url https://example.com/path/]
 *
 * 1. Renders every PDF page to sharp JPEGs (invitation/page-N.jpg and page-N@2x.jpg), so guests see the
 *    invitation instantly on any phone instead of waiting for a PDF viewer.
 * 2. Builds the link-preview image WhatsApp / Telegram show (invitation/og.jpg, 1200×630).
 * 3. Writes invitation/pages.js (page list + cache-busting version) and the <meta> preview tags in index.html.
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

/* ---------- 1. pages ---------- */
const pdfRel = cfg.invitationPdf || 'invitation.pdf';
const pdf = join(root, pdfRel);
const outDir = join(root, 'invitation');
mkdirSync(outDir, { recursive: true });

let manifest = { pdf: pdfRel, pages: [], v: '' };
if (!existsSync(pdf)) {
  console.warn(`! ${pdfRel} not found — the page will offer the PDF link only.`);
} else if (!has('pdftoppm') || !has('pdfinfo')) {
  console.warn('! pdftoppm/pdfinfo missing (install poppler-utils) — skipping page images.');
} else {
  const v = createHash('sha1').update(readFileSync(pdf)).digest('hex').slice(0, 10);
  readdirSync(outDir)
    .filter((f) => /^page-\d+(@2x)?\.jpg$/.test(f))
    .forEach((f) => unlinkSync(join(outDir, f)));
  const count = parseInt((run('pdfinfo', [pdf]).match(/^Pages:\s+(\d+)/m) || [])[1], 10) || 1;
  const maxPages = Math.min(count, 12);
  for (let n = 1; n <= maxPages; n++) {
    for (const [suffix, width] of [['', 900], ['@2x', 1800]]) {
      const base = join(outDir, `page-${n}${suffix}`);
      run('pdftoppm', ['-q', '-f', String(n), '-l', String(n), '-singlefile', '-jpeg', '-jpegopt', 'quality=86,progressive=y', '-scale-to-x', String(width), '-scale-to-y', '-1', '-aa', 'yes', '-aaVector', 'yes', pdf, base]);
    }
    const { w, h } = jpegSize(join(outDir, `page-${n}.jpg`));
    manifest.pages.push({ w, h, src: `invitation/page-${n}.jpg?v=${v}`, src2x: `invitation/page-${n}@2x.jpg?v=${v}` });
  }
  manifest.v = v;
  console.log(`✓ ${maxPages} page(s) rendered from ${pdfRel}`);

  /* ---------- 2. link preview ---------- */
  if (magick) {
    const page = join(outDir, 'page-1@2x.jpg');
    run(magick, [
      '-size', '1200x630', 'xc:#fff8f0',
      '-fill', '#ffc23d', '-draw', 'circle 1130,40 1130,250',
      '-fill', '#2fbf9b', '-draw', 'circle 40,640 40,500',
      '-fill', '#d6336c', '-draw', 'circle 180,110 180,122',
      '-fill', '#a78bfa', '-draw', 'circle 1010,520 1010,546',
      '(', page, '-resize', 'x540', '-bordercolor', '#ffffff', '-border', '10',
      '(', '+clone', '-background', '#221f36', '-shadow', '35x14+0+14', ')', '+swap', '-background', 'none', '-layers', 'merge', '+repage',
      '-background', 'none', '-rotate', '-2', ')',
      '-gravity', 'center', '-geometry', '+0+4', '-composite',
      '-strip', '-quality', '84', '-sampling-factor', '4:2:0', join(outDir, 'og.jpg'),
    ]);
    console.log('✓ invitation/og.jpg (link preview)');
  } else {
    console.warn('! ImageMagick missing — using page 1 as the link preview.');
    writeFileSync(join(outDir, 'og.jpg'), readFileSync(join(outDir, 'page-1.jpg')));
  }
}

writeFileSync(
  join(outDir, 'pages.js'),
  `/* generated by tools/build.mjs — do not edit */\nwindow.INVITATION_PAGES = ${JSON.stringify(manifest, null, 2)};\n`
);

/* ---------- 3. preview tags ---------- */
const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;');
const couple = (cfg.couple || []).join(' & ');
const m = String(cfg.date || '').match(/^(\d{4})-(\d{2})-(\d{2})/);
const date = m ? new Date(+m[1], +m[2] - 1, +m[3]) : null;
const dateText = date ? new Intl.DateTimeFormat('he-IL', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' }).format(date) : '';
const title = `${couple} מתחתנים!`;
const desc = [dateText, cfg.venue && cfg.venue.name].filter(Boolean).join(' · ') + '. לחצו לצפייה בהזמנה ולאישור הגעה';
const image = siteUrl ? `${siteUrl}invitation/og.jpg${manifest.v ? '?v=' + manifest.v : ''}` : 'invitation/og.jpg';

const meta = [
  `<title>${esc(title)}</title>`,
  `<meta name="description" content="${esc(desc)}">`,
  `<meta property="og:type" content="website">`,
  `<meta property="og:locale" content="he_IL">`,
  `<meta property="og:title" content="${esc(title)}">`,
  `<meta property="og:description" content="${esc(desc)}">`,
  siteUrl && `<meta property="og:url" content="${esc(siteUrl)}">`,
  `<meta property="og:image" content="${esc(image)}">`,
  `<meta property="og:image:width" content="1200">`,
  `<meta property="og:image:height" content="630">`,
  `<meta name="twitter:card" content="summary_large_image">`,
]
  .filter(Boolean)
  .join('\n');

const indexPath = join(root, 'index.html');
const html = readFileSync(indexPath, 'utf8');
const next = html.replace(
  /<!-- meta:start[^>]*-->[\s\S]*?<!-- meta:end -->/,
  `<!-- meta:start (generated by tools/build.mjs from config.js) -->\n${meta}\n<!-- meta:end -->`
);
if (next !== html) writeFileSync(indexPath, next);
console.log(`✓ preview tags: "${title}"${siteUrl ? ' → ' + siteUrl : ''}`);
