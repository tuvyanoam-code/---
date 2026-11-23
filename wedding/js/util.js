/* Shared helpers for the guest page and the admin dashboard. Plain script (works from file:// too). */
(function () {
  'use strict';

  const cfg = window.WEDDING || {};
  const C = (window.W = {});
  C.cfg = cfg;

  /* ---------- language ---------- */
  // The inline script in <head> already picked the language (?lang=, saved choice, page default) and set lang/dir.
  C.lang = document.documentElement.lang === 'en' ? 'en' : 'he';
  C.rtl = C.lang === 'he';
  C.locale = C.lang === 'en' ? 'en-GB' : 'he-IL';
  const dict = (window.I18N || {})[C.lang] || {};
  const dictHe = (window.I18N || {}).he || {};

  // A config value may be plain, or { he, en }.
  C.L = (v, lang) => {
    if (v == null) return '';
    if (typeof v !== 'object' || Array.isArray(v)) return v;
    const l = lang || C.lang;
    return v[l] != null && v[l] !== '' && !(Array.isArray(v[l]) && !v[l].length) ? v[l] : v.he != null ? v.he : v.en != null ? v.en : '';
  };

  C.fill = (tpl, vars) => String(tpl == null ? '' : tpl).replace(/\{(\w+)\}/g, (m, k) => (vars && vars[k] != null ? vars[k] : m));

  // t('key', { n: 3 }) — picks the singular/plural form by n and fills {placeholders}.
  C.t = (key, vars, lang) => {
    const d = lang ? (window.I18N || {})[lang] || dict : dict;
    let s = d[key] != null ? d[key] : dictHe[key] != null ? dictHe[key] : key;
    if (s && typeof s === 'object' && !Array.isArray(s)) s = vars && Number(vars.n) === 1 ? s.one : s.other;
    if (Array.isArray(s)) return s;
    return C.fill(s, Object.assign({}, vars, vars && vars.n != null && typeof vars.n === 'number' ? { n: C.num(vars.n) } : {}));
  };

  C.couple = (lang) => C.L(cfg.couple, lang) || [];
  C.coupleText = C.couple().join(' & ');

  // Sides are stored in their Hebrew form (stable in the sheet) and shown in the current language.
  const sidesFor = (lang) => {
    const s = C.L(cfg.sides, lang);
    return Array.isArray(s) && s.length ? s : [...C.couple(lang), C.t('c.both', null, lang)];
  };
  C.sides = sidesFor('he');
  C.sideLabel = (v) => {
    const i = C.sides.indexOf(v);
    return i >= 0 ? sidesFor(C.lang)[i] || v : v;
  };

  // Fill [data-i18n] texts and attributes.
  C.applyI18n = (root) => {
    (root || document).querySelectorAll('[data-i18n]').forEach((el) => (el.textContent = C.t(el.dataset.i18n)));
    (root || document).querySelectorAll('[data-i18n-ph]').forEach((el) => (el.placeholder = C.t(el.dataset.i18nPh)));
    (root || document).querySelectorAll('[data-i18n-aria]').forEach((el) => el.setAttribute('aria-label', C.t(el.dataset.i18nAria)));
    (root || document).querySelectorAll('[data-i18n-title]').forEach((el) => (el.title = C.t(el.dataset.i18nTitle)));
  };

  // Language switch: remember the choice and reload in the other language.
  C.switchLang = () => {
    const next = C.lang === 'he' ? 'en' : 'he';
    try {
      localStorage.setItem('wedding-lang', JSON.stringify(next));
    } catch (e) {}
    const u = new URL(location.href);
    u.searchParams.delete('lang');
    location.replace(u.toString());
  };
  C.langButton = (el) => {
    el.textContent = C.t('c.langBtn');
    el.setAttribute('aria-label', C.t('c.langAria'));
    el.setAttribute('lang', C.lang === 'he' ? 'en' : 'he');
    el.addEventListener('click', C.switchLang);
  };

  /* ---------- text ---------- */
  C.esc = (v) =>
    String(v == null ? '' : v).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);

  C.uid = (len = 10) => {
    const a = new Uint8Array(len);
    (window.crypto || window.msCrypto).getRandomValues(a);
    const abc = 'abcdefghijkmnpqrstuvwxyz23456789';
    return Array.from(a, (b) => abc[b % abc.length]).join('');
  };

  C.num = (n) => new Intl.NumberFormat(C.locale).format(n || 0);

  /* ---------- dates ---------- */
  // "2027-06-17T19:30" is read as local time (Israel for the couple and nearly all guests).
  C.parseLocal = (s) => {
    const m = String(s || '').match(/^(\d{4})-(\d{2})-(\d{2})(?:T(\d{2}):(\d{2}))?/);
    if (!m) return null;
    return new Date(+m[1], +m[2] - 1, +m[3], +(m[4] || 0), +(m[5] || 0));
  };
  C.eventDate = C.parseLocal(cfg.date);

  C.fmtDate = (d, opts, lang) => new Intl.DateTimeFormat(lang === 'en' ? 'en-GB' : lang === 'he' ? 'he-IL' : C.locale, opts).format(d);
  C.longDate = (d, lang) => C.fmtDate(d, { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' }, lang);
  C.shortDate = (d) => C.fmtDate(d, { day: 'numeric', month: 'numeric', year: 'numeric' });
  C.timeOf = (d) => C.fmtDate(d, { hour: '2-digit', minute: '2-digit', hour12: false });

  // Hebrew numerals: 12 → י״ב, 15 → ט״ו, 5787 → תשפ״ז
  C.gematria = (n, dropThousands = true) => {
    if (dropThousands) n = n % 1000;
    const ones = ['', 'א', 'ב', 'ג', 'ד', 'ה', 'ו', 'ז', 'ח', 'ט'];
    const tens = ['', 'י', 'כ', 'ל', 'מ', 'נ', 'ס', 'ע', 'פ', 'צ'];
    const hundreds = ['', 'ק', 'ר', 'ש', 'ת', 'תק', 'תר', 'תש', 'תת', 'תתק'];
    let s = hundreds[Math.floor(n / 100)];
    let r = n % 100;
    if (r === 15) s += 'טו';
    else if (r === 16) s += 'טז';
    else s += tens[Math.floor(r / 10)] + ones[r % 10];
    if (s.length === 1) return s + '׳';
    return s.slice(0, -1) + '״' + s.slice(-1);
  };

  C.hebrewDate = (d) => {
    const custom = C.L(cfg.hebrewDate);
    if (custom) return custom;
    if (C.lang === 'en') {
      try {
        return new Intl.DateTimeFormat('en-u-ca-hebrew', { day: 'numeric', month: 'long', year: 'numeric' }).format(d);
      } catch (e) {
        return '';
      }
    }
    try {
      const parts = new Intl.DateTimeFormat('he-IL-u-ca-hebrew', { day: 'numeric', month: 'long', year: 'numeric' }).formatToParts(d);
      const get = (t) => (parts.find((p) => p.type === t) || {}).value;
      const day = parseInt(get('day'), 10);
      const year = parseInt(get('year'), 10);
      const month = get('month');
      if (!day || !year || !month) return '';
      return `${C.gematria(day)} ב${month} ${C.gematria(year)}`;
    } catch (e) {
      return '';
    }
  };

  C.daysUntil = (d) => {
    if (!d) return null;
    const now = new Date();
    const a = new Date(now.getFullYear(), now.getMonth(), now.getDate());
    const b = new Date(d.getFullYear(), d.getMonth(), d.getDate());
    return Math.round((b - a) / 86400000);
  };

  C.relTime = (t) => {
    const s = Math.round((Date.now() - t) / 1000);
    if (s < 60) return C.t('c.now');
    const m = Math.round(s / 60);
    if (m < 60) return C.t('c.min', { n: m });
    const h = Math.round(m / 60);
    if (h < 24) return C.t('c.hr', { n: h });
    const days = Math.round(h / 24);
    if (days < 7) return days === 1 ? C.t('c.yesterday') : C.t('c.days', { n: days });
    return C.shortDate(new Date(t));
  };

  /* ---------- links ---------- */
  C.siteUrl = (() => {
    const here = location.href.replace(/[?#].*$/, '').replace(/(index|admin|en)\.html$/, '');
    const u = cfg.siteUrl || '';
    // Served from somewhere else (local preview, another host): share the address we are on.
    if (/^https?:\/\//.test(location.href) && !location.href.startsWith(u)) return here;
    return u || here;
  })();

  // The English page is en.html: same site, English texts, and an English WhatsApp preview.
  C.link = (params, lang) => {
    const q = new URLSearchParams();
    Object.entries(params || {}).forEach(([k, v]) => v && q.set(k, v));
    const qs = q.toString();
    return C.siteUrl + ((lang || C.lang) === 'en' ? 'en.html' : '') + (qs ? '?' + qs : '');
  };

  C.normalizePhone = (p) => {
    let d = String(p || '').replace(/[^\d+]/g, '');
    if (!d) return '';
    if (d.startsWith('+')) return d.slice(1);
    if (d.startsWith('00')) return d.slice(2);
    if (d.startsWith('0')) return (cfg.countryCode || '972') + d.slice(1);
    return d;
  };
  C.displayPhone = (p) => {
    const n = C.normalizePhone(p);
    const cc = cfg.countryCode || '972';
    if (n.startsWith(cc) && n.length >= 11) {
      const local = '0' + n.slice(cc.length);
      return local.slice(0, 3) + '-' + local.slice(3);
    }
    return p || '';
  };

  const isIOS = /iP(hone|ad|od)/.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);

  C.shareUrls = (text, url, phone) => {
    const n = C.normalizePhone(phone);
    const full = text.includes(url) ? text : text + '\n' + url;
    const textNoUrl = text.replace(url, '').replace(/\n{2,}/g, '\n').trim();
    return {
      whatsapp: n ? `https://wa.me/${n}?text=${encodeURIComponent(full)}` : `https://wa.me/?text=${encodeURIComponent(full)}`,
      telegram: `https://t.me/share/url?url=${encodeURIComponent(url)}&text=${encodeURIComponent(textNoUrl)}`,
      sms: `sms:${n ? '+' + n : ''}${isIOS ? '&' : '?'}body=${encodeURIComponent(full)}`,
    };
  };

  C.mapsLinks = () => {
    const v = cfg.venue || {};
    const q = encodeURIComponent([C.L(v.name), C.L(v.address)].filter(Boolean).join(', '));
    return {
      waze: v.waze || `https://waze.com/ul?q=${q}&navigate=yes`,
      maps: v.maps || `https://www.google.com/maps/search/?api=1&query=${q}`,
    };
  };

  const pad = (n) => String(n).padStart(2, '0');
  const icsStamp = (d) => `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}T${pad(d.getHours())}${pad(d.getMinutes())}00`;

  C.calendar = () => {
    const start = C.eventDate;
    if (!start) return null;
    const end = new Date(start.getTime() + 5 * 3600 * 1000);
    const v = cfg.venue || {};
    const title = C.t('g.calTitle', { couple: C.coupleText });
    const where = [C.L(v.name), C.L(v.address)].filter(Boolean).join(', ');
    const google =
      'https://calendar.google.com/calendar/render?action=TEMPLATE' +
      `&text=${encodeURIComponent(title)}&dates=${icsStamp(start)}/${icsStamp(end)}` +
      `&ctz=Asia/Jerusalem&location=${encodeURIComponent(where)}&details=${encodeURIComponent(C.siteUrl)}`;
    const ics = [
      'BEGIN:VCALENDAR', 'VERSION:2.0', 'PRODID:-//wedding//he', 'CALSCALE:GREGORIAN', 'BEGIN:VEVENT',
      `UID:${icsStamp(start)}-wedding@invite`, `DTSTAMP:${icsStamp(new Date())}`,
      `DTSTART;TZID=Asia/Jerusalem:${icsStamp(start)}`, `DTEND;TZID=Asia/Jerusalem:${icsStamp(end)}`,
      `SUMMARY:${title}`, `LOCATION:${where.replace(/,/g, '\\,')}`, `URL:${C.siteUrl}`,
      'BEGIN:VALARM', 'TRIGGER:-P1D', 'ACTION:DISPLAY', `DESCRIPTION:${title}`, 'END:VALARM',
      'END:VEVENT', 'END:VCALENDAR',
    ].join('\r\n');
    return { google, ics, title };
  };

  C.download = (filename, content, type) => {
    const blob = content instanceof Blob ? content : new Blob([content], { type: type || 'text/plain;charset=utf-8' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    setTimeout(() => {
      URL.revokeObjectURL(a.href);
      a.remove();
    }, 1000);
  };

  // CSV that Excel opens correctly in Hebrew (UTF-8 BOM) and that never runs as a formula.
  C.csv = (rows) =>
    '﻿' +
    rows
      .map((r) =>
        r
          .map((v) => {
            let s = String(v == null ? '' : v);
            if (/^[=+\-@]/.test(s)) s = "'" + s;
            return /[",\n\r]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
          })
          .join(',')
      )
      .join('\r\n');

  C.copy = async (text) => {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch (e) {
      const ta = document.createElement('textarea');
      ta.value = text;
      ta.style.position = 'fixed';
      ta.style.opacity = '0';
      document.body.appendChild(ta);
      ta.select();
      let ok = false;
      try {
        ok = document.execCommand('copy');
      } catch (_) {}
      ta.remove();
      return ok;
    }
  };

  C.device = () => (/Mobi|Android|iPhone|iPod/i.test(navigator.userAgent) ? 'mobile' : /iPad|Tablet/i.test(navigator.userAgent) ? 'tablet' : 'desktop');

  C.store = {
    get(k, d) {
      try {
        const v = localStorage.getItem(k);
        return v == null ? d : JSON.parse(v);
      } catch (e) {
        return d;
      }
    },
    set(k, v) {
      try {
        localStorage.setItem(k, JSON.stringify(v));
      } catch (e) {}
    },
    del(k) {
      try {
        localStorage.removeItem(k);
      } catch (e) {}
    },
  };

  let toastTimer;
  C.toast = (msg) => {
    let el = document.getElementById('toast');
    if (!el) {
      el = document.createElement('div');
      el.id = 'toast';
      el.className = 'toast';
      el.setAttribute('role', 'status');
      el.setAttribute('aria-live', 'polite');
      document.body.appendChild(el);
    }
    el.textContent = msg;
    el.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => el.classList.remove('show'), 2600);
  };

  /* ---------- icons (inline SVG, currentColor) ---------- */
  const svg = (body, vb = '0 0 24 24') =>
    `<svg viewBox="${vb}" aria-hidden="true" focusable="false" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">${body}</svg>`;
  C.icon = {
    whatsapp: `<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path fill="currentColor" d="M12 2.2A9.8 9.8 0 0 0 3.6 17l-1.3 4.8 4.9-1.3A9.8 9.8 0 1 0 12 2.2Zm0 17.9a8.1 8.1 0 0 1-4.1-1.1l-.3-.2-2.9.8.8-2.8-.2-.3A8.1 8.1 0 1 1 12 20.1Zm4.5-6.1c-.2-.1-1.5-.7-1.7-.8-.2-.1-.4-.1-.6.1l-.8 1c-.1.2-.3.2-.5.1a6.6 6.6 0 0 1-3.3-2.9c-.2-.4.2-.4.7-1.3.1-.2 0-.3 0-.4l-.8-1.8c-.2-.5-.4-.4-.6-.4h-.5a1 1 0 0 0-.7.3 3 3 0 0 0-.9 2.2 5.2 5.2 0 0 0 1.1 2.7 11.8 11.8 0 0 0 4.5 4c1.7.7 2.3.8 3.2.6.5-.1 1.5-.6 1.7-1.2.2-.6.2-1.1.2-1.2-.1-.1-.3-.2-.5-.3Z"/></svg>`,
    telegram: `<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path fill="currentColor" d="M21.4 4.2 2.9 11.3c-1.3.5-1.2 1.2-.2 1.5l4.7 1.5 1.8 5.6c.2.6.4.8.9.8.4 0 .6-.2.9-.4l2.3-2.2 4.7 3.5c.9.5 1.5.2 1.7-.8l3.1-14.5c.3-1.3-.5-1.9-1.4-1.5ZM9.6 14.4l-.4 4-1.4-4.6 10.4-6.6c.5-.3.9-.1.5.2l-9.1 7Z"/></svg>`,
    sms: svg('<path d="M4 5h16a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H9l-5 4v-4H4a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1Z"/><path d="M8 11h.01M12 11h.01M16 11h.01" stroke-width="2.6"/>'),
    link: svg('<path d="M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1"/><path d="M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1"/>'),
    share: svg('<path d="M12 3v12M7 8l5-5 5 5"/><path d="M5 13v6a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-6"/>'),
    download: svg('<path d="M12 4v11M7 10l5 5 5-5"/><path d="M5 20h14"/>'),
    close: svg('<path d="M6 6l12 12M18 6 6 18"/>'),
    zoomIn: svg('<circle cx="11" cy="11" r="6.5"/><path d="M20 20l-4.2-4.2M11 8.5v5M8.5 11h5"/>'),
    zoomOut: svg('<circle cx="11" cy="11" r="6.5"/><path d="M20 20l-4.2-4.2M8.5 11h5"/>'),
    calendar: svg('<rect x="3.5" y="5" width="17" height="15.5" rx="3"/><path d="M3.5 10h17M8 3v4M16 3v4"/>'),
    pin: svg('<path d="M12 21s-6.5-6-6.5-11a6.5 6.5 0 0 1 13 0c0 5-6.5 11-6.5 11Z"/><circle cx="12" cy="10" r="2.3"/>'),
    waze: svg('<path d="M5 14.5c-1.4-.3-2-1.2-2-2.2 1.2 0 1.6-.5 1.8-1.5C5.6 6.8 8.7 4 13 4c4.4 0 8 3.3 8 7.4 0 3.2-2.2 5.5-5.2 6.4"/><circle cx="8.5" cy="18.5" r="2"/><circle cx="16" cy="18.5" r="2"/><path d="M10.5 18.6h3.5M10.5 11h.01M15.5 11h.01M10.5 14c1 1 3.2 1 4.2 0" stroke-width="1.8"/>'),
    clock: svg('<circle cx="12" cy="12" r="8.5"/><path d="M12 7.5V12l3 2"/>'),
    check: svg('<path d="M5 12.5l4.5 4.5L19 7.5"/>'),
    x: svg('<path d="M7 7l10 10M17 7 7 17"/>'),
    maybe: svg('<path d="M9.2 9a3 3 0 1 1 3.8 2.9c-.6.2-1 .7-1 1.3V14"/><path d="M12 17.5h.01" stroke-width="2.6"/>'),
    plus: svg('<path d="M12 5v14M5 12h14"/>'),
    minus: svg('<path d="M5 12h14"/>'),
    heart: svg('<path d="M12 20s-7.5-4.6-7.5-10.2A4.3 4.3 0 0 1 12 7.4a4.3 4.3 0 0 1 7.5 2.4C19.5 15.4 12 20 12 20Z"/>'),
    edit: svg('<path d="M4 20h4L19 9a2.8 2.8 0 0 0-4-4L4 16v4Z"/><path d="M13.5 6.5l4 4"/>'),
    trash: svg('<path d="M4.5 7h15M9.5 7V4.5h5V7M6.5 7l1 13h9l1-13"/>'),
    refresh: svg('<path d="M20 11a8 8 0 0 0-14.6-4.5M4 4.5V9h4.5"/><path d="M4 13a8 8 0 0 0 14.6 4.5M20 19.5V15h-4.5"/>'),
    search: svg('<circle cx="11" cy="11" r="6.5"/><path d="M20 20l-4.2-4.2"/>'),
    users: svg('<circle cx="9" cy="8.5" r="3.5"/><path d="M2.5 20a6.5 6.5 0 0 1 13 0"/><path d="M16 5.2a3.5 3.5 0 0 1 0 6.6M18.5 14a6.5 6.5 0 0 1 3 6"/>'),
    chart: svg('<path d="M4 20V10M10 20V4M16 20v-7M22 20H2"/>'),
    list: svg('<path d="M9 6h11M9 12h11M9 18h11M4.5 6h.01M4.5 12h.01M4.5 18h.01" stroke-width="2"/>'),
    eye: svg('<path d="M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12Z"/><circle cx="12" cy="12" r="2.8"/>'),
    send: svg('<path d="M21 3 10 14M21 3l-7 18-4-7-7-4 18-7Z"/>'),
    upload: svg('<path d="M12 20V9M7 14l5-5 5 5"/><path d="M5 4h14"/>'),
    sheet: svg('<rect x="4" y="3" width="16" height="18" rx="2.5"/><path d="M4 9h16M4 15h16M10 9v12"/>'),
    print: svg('<path d="M7 9V3.5h10V9"/><rect x="3.5" y="9" width="17" height="8" rx="2"/><path d="M7 14h10v6.5H7z"/>'),
    qr: svg('<rect x="4" y="4" width="6" height="6" rx="1"/><rect x="14" y="4" width="6" height="6" rx="1"/><rect x="4" y="14" width="6" height="6" rx="1"/><path d="M14 14h2v2h-2zM18 18h2v2h-2zM14 18h.01M18 14h2"/>'),
    logout: svg('<path d="M14 4h4a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-4"/><path d="M10 16l-4-4 4-4M6 12h10"/>'),
    sparkle: svg('<path d="M12 3v4M12 17v4M3 12h4M17 12h4M6 6l2.5 2.5M15.5 15.5 18 18M6 18l2.5-2.5M15.5 8.5 18 6"/>'),
  };

  C.applyI18n();
  document.querySelectorAll('[data-lang-toggle]').forEach(C.langButton);
  document.documentElement.classList.remove('i18n-pending');
})();
