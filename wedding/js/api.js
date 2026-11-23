/*
 * Data layer. With WEDDING.backendUrl set, everything goes to the Google Apps Script web app
 * (backend/Code.gs), which writes to a Google Sheet. Without it, a demo store in localStorage
 * implements the same operations so the whole site can be tried before the backend exists.
 */
(function () {
  'use strict';
  const W = window.W;
  const url = (W.cfg.backendUrl || '').trim();
  const api = (window.API = { mode: url ? 'live' : 'demo' });

  /* ---------- transport ---------- */
  // text/plain keeps the request "simple", so the browser sends no CORS preflight (Apps Script can't answer one).
  async function post(body, { timeout = 20000 } = {}) {
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), timeout);
    try {
      const res = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'text/plain;charset=utf-8' },
        body: JSON.stringify(body),
        signal: ctrl.signal,
        redirect: 'follow',
      });
      const data = await res.json();
      if (!data || data.ok === false) {
        const err = new Error((data && data.error) || 'server');
        err.code = (data && data.error) || 'server';
        throw err;
      }
      return data;
    } finally {
      clearTimeout(timer);
    }
  }

  /* ---------- demo store (same shapes as the sheet) ---------- */
  const DEMO_KEY = 'wedding-demo-db';
  const demo = {
    load() {
      return W.store.get(DEMO_KEY, null) || { rsvps: [], events: [], guests: [] };
    },
    save(db) {
      W.store.set(DEMO_KEY, db);
    },
    upsert(list, item) {
      const i = list.findIndex((x) => x.id === item.id);
      if (i >= 0) list[i] = Object.assign({}, list[i], item);
      else list.push(item);
      return i >= 0 ? list[i] : item;
    },
  };

  const clean = (s, max = 300) => String(s == null ? '' : s).trim().slice(0, max);

  function normalizeRsvp(r) {
    const status = ['yes', 'no', 'maybe'].includes(r.status) ? r.status : 'yes';
    const count = status === 'no' ? 0 : Math.max(1, Math.min(50, parseInt(r.count, 10) || 1));
    return {
      id: clean(r.id, 64),
      name: clean(r.name, 80),
      status,
      count,
      phone: clean(r.phone, 30),
      note: clean(r.note, 600),
      guest: clean(r.guest, 64),
      src: clean(r.src, 20),
      device: clean(r.device, 12),
      lang: r.lang === 'en' ? 'en' : 'he',
    };
  }

  /* ---------- public: guest-side ---------- */
  api.rsvp = async (data) => {
    const r = normalizeRsvp(data);
    if (!r.id || !r.name) throw new Error('missing');
    if (api.mode === 'live') return (await post({ action: 'rsvp', rsvp: r })).rsvp;
    const db = demo.load();
    const now = Date.now();
    const prev = db.rsvps.find((x) => x.id === r.id);
    const saved = demo.upsert(db.rsvps, Object.assign(r, { updated: now, created: prev ? prev.created : now, edits: prev ? (prev.edits || 0) + 1 : 0 }));
    demo.save(db);
    return saved;
  };

  api.guest = async (id) => {
    if (!id) return null;
    if (api.mode === 'live') {
      try {
        return (await post({ action: 'guest', id }, { timeout: 8000 })).guest || null;
      } catch (e) {
        return null;
      }
    }
    const g = demo.load().guests.find((x) => x.id === id);
    return g ? { id: g.id, name: g.name, invited: g.invited } : null;
  };

  /* ---------- usage tracking ---------- */
  const queue = [];
  let flushTimer = null;

  function flush(useBeacon) {
    clearTimeout(flushTimer);
    flushTimer = null;
    if (!queue.length) return;
    const events = queue.splice(0, queue.length);
    if (api.mode === 'demo') {
      const db = demo.load();
      db.events.push(...events);
      demo.save(db);
      return;
    }
    const body = JSON.stringify({ action: 'track', events });
    if (useBeacon && navigator.sendBeacon) {
      try {
        if (navigator.sendBeacon(url, new Blob([body], { type: 'text/plain;charset=utf-8' }))) return;
      } catch (e) {}
    }
    fetch(url, { method: 'POST', headers: { 'Content-Type': 'text/plain;charset=utf-8' }, body, keepalive: true }).catch(() => {});
  }

  api.track = (type, detail, ctx) => {
    queue.push(
      Object.assign(
        { t: Date.now(), type, detail: detail == null ? '' : String(detail).slice(0, 200) },
        ctx || api.context || {}
      )
    );
    if (!flushTimer) flushTimer = setTimeout(() => flush(false), 1500);
  };

  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'hidden') flush(true);
  });
  window.addEventListener('pagehide', () => flush(true));

  /* ---------- admin ---------- */
  api.admin = async (op, payload, password) => {
    if (api.mode === 'live') return post(Object.assign({ action: 'admin', op, password }, payload || {}), { timeout: 30000 });
    const db = demo.load();
    const now = Date.now();
    let out = {};
    switch (op) {
      case 'data':
        out = { rsvps: db.rsvps, events: db.events, guests: db.guests, sheetUrl: '' };
        break;
      case 'saveGuests':
        (payload.guests || []).forEach((g) => demo.upsert(db.guests, Object.assign({ created: now }, g)));
        break;
      case 'deleteGuest':
        db.guests = db.guests.filter((g) => g.id !== payload.id);
        break;
      case 'markSent':
        db.guests.forEach((g) => {
          if ((payload.ids || []).includes(g.id)) g.sent = now;
        });
        break;
      case 'saveRsvp': {
        const r = normalizeRsvp(payload.rsvp);
        const prev = db.rsvps.find((x) => x.id === r.id);
        demo.upsert(db.rsvps, Object.assign(r, { src: r.src || 'admin', updated: now, created: prev ? prev.created : now, edits: prev ? (prev.edits || 0) + 1 : 0 }));
        break;
      }
      case 'deleteRsvp':
        db.rsvps = db.rsvps.filter((r) => r.id !== payload.id);
        break;
      case 'resetDemo':
        demo.save({ rsvps: [], events: [], guests: [] });
        return { ok: true };
      case 'seedDemo':
        demo.save(seed());
        return { ok: true };
      default:
        throw new Error('unknown op');
    }
    if (op !== 'data') demo.save(db);
    return Object.assign({ ok: true }, out);
  };

  /* ---------- demo data generator (admin "fill with sample data" button) ---------- */
  function seed() {
    const english = ['The Smith family', 'Daniel Brown', 'Emma & Jacob Miller', 'Sarah Levy'];
    const first = ['דנה', 'יוסי', 'מיכל', 'אבי', 'שירה', 'עומר', 'רוני', 'תמר', 'גל', 'הילה', 'אורי', 'נטע', 'עידו', 'מאיה', 'יונתן', 'ליאת', 'אלון', 'רותם', 'שקד', 'בן', 'קרן', 'אסף', 'ענבל', 'דור', 'יעל', 'ניר', 'ספיר', 'אייל', 'מור', 'טל'];
    const last = ['כהן', 'לוי', 'מזרחי', 'פרץ', 'ביטון', 'אברהם', 'פרידמן', 'שפירא', 'גולן', 'אזולאי', 'דהן', 'רוזן', 'בן דוד', 'קפלן', 'חדד'];
    const rnd = (a) => a[Math.floor(Math.random() * a.length)];
    const now = Date.now();
    const day = 86400000;
    const guests = [];
    const rsvps = [];
    const events = [];
    const srcs = ['wa', 'wa', 'wa', 'wa', 'sms', 'tg', 'copy', ''];
    for (let i = 0; i < 46; i++) {
      const en = i % 11 === 5 && english.length;
      const name = en ? english.pop() : i % 3 === 0 ? `משפחת ${rnd(last)}` : `${rnd(first)} ${rnd(last)}`;
      const g = {
        lang: en ? 'en' : 'he',
        id: W.uid(8),
        name,
        phone: '05' + Math.floor(20000000 + Math.random() * 79999999),
        side: W.sides[i % W.sides.length],
        invited: i % 3 === 0 ? 4 : rnd([1, 2, 2, 2]),
        notes: '',
        created: now - 20 * day,
        sent: i < 40 ? now - (18 - (i % 6)) * day : 0,
      };
      guests.push(g);
      if (!g.sent) continue;
      const opened = Math.random() < 0.85;
      const visitor = W.uid(10);
      const src = rnd(srcs);
      const device = Math.random() < 0.86 ? 'mobile' : 'desktop';
      if (!opened) continue;
      const tView = g.sent + Math.random() * 2 * day;
      events.push({ t: tView, type: 'view', visitor, guest: g.id, src, device, detail: '' });
      if (Math.random() < 0.7) events.push({ t: tView + 30000, type: 'open_invite', visitor, guest: g.id, src, device, detail: '' });
      if (Math.random() < 0.2) events.push({ t: tView + 60000, type: 'calendar', visitor, guest: g.id, src, device, detail: 'google' });
      const answers = Math.random() < 0.8;
      if (answers || Math.random() < 0.4) events.push({ t: tView + 70000, type: 'rsvp_start', visitor, guest: g.id, src, device, detail: '' });
      if (answers) {
        const roll = Math.random();
        const status = roll < 0.74 ? 'yes' : roll < 0.88 ? 'no' : 'maybe';
        const t = tView + 90000 + Math.random() * 3 * day;
        rsvps.push({
          id: 'g:' + g.id, name: g.name, status, count: status === 'no' ? 0 : g.invited, phone: g.phone,
          note: Math.random() < 0.3 ? rnd(['מזל טוב!! מחכים לרקוד איתכם', 'נגיע בשמחה 💃', 'אוהבים אתכם, מזל טוב', 'מצטערים, נהיה בחו״ל. מזל טוב!']) : '',
          guest: g.id, src, device, created: t, updated: t, edits: 0, lang: g.lang,
        });
        events.push({ t, type: 'rsvp', visitor, guest: g.id, src, device, detail: `${status}:${status === 'no' ? 0 : g.invited}|${g.name}` });
      }
    }
    // A few visitors from the general (non-personal) link who shared it onward
    for (let i = 0; i < 14; i++) {
      const visitor = W.uid(10);
      const src = rnd(['wa', 'wa', 'tg', 'copy', 'qr', '']);
      const t = now - Math.random() * 12 * day;
      const device = Math.random() < 0.8 ? 'mobile' : 'desktop';
      events.push({ t, type: 'view', visitor, guest: '', src, device, detail: '' });
      if (Math.random() < 0.5) events.push({ t: t + 20000, type: 'open_invite', visitor, guest: '', src, device, detail: '' });
      if (Math.random() < 0.25) events.push({ t: t + 40000, type: 'share', visitor, guest: '', src, device, detail: rnd(['wa', 'tg', 'sms', 'copy']) });
      if (Math.random() < 0.5) {
        const status = Math.random() < 0.85 ? 'yes' : 'no';
        const r = { id: W.uid(12), name: `${rnd(first)} ${rnd(last)}`, status, count: status === 'no' ? 0 : rnd([1, 2, 2, 3]), phone: '', note: '', guest: '', src, device, created: t + 60000, updated: t + 60000, edits: 0 };
        rsvps.push(r);
        events.push({ t: t + 60000, type: 'rsvp', visitor, guest: '', src, device, detail: `${status}:${r.count}|${r.name}` });
      }
    }
    events.sort((a, b) => a.t - b.t);
    return { guests, rsvps, events };
  }
})();
