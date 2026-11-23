/* Admin dashboard */
(function () {
  'use strict';
  const W = window.W;
  const api = window.API;
  const cfg = W.cfg;
  const I = W.icon;
  const Charts = window.Charts;
  const $ = (s) => document.querySelector(s);
  const esc = W.esc;

  const ICON_MOON = '<svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5Z"/></svg>';
  const ICON_SUN = '<svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><circle cx="12" cy="12" r="4"/><path d="M12 2.5v2M12 19.5v2M2.5 12h2M19.5 12h2M5.3 5.3l1.4 1.4M17.3 17.3l1.4 1.4M5.3 18.7l1.4-1.4M17.3 6.7l1.4-1.4"/></svg>';

  const VIEWS = [
    ['overview', 'סקירה', I.chart],
    ['rsvps', 'אישורים', I.check],
    ['guests', 'מוזמנים', I.users],
    ['share', 'שיתוף', I.send],
    ['activity', 'פעילות', I.list],
  ];
  const SRC = { wa: 'וואטסאפ', tg: 'טלגרם', sms: 'SMS', copy: 'קישור שהועתק', share: 'שיתוף מהטלפון', qr: 'קוד QR', admin: 'הוזן ידנית', '': 'ישיר / לא ידוע' };
  const srcLabel = (s) => (SRC[s] != null ? SRC[s] : s);
  const STATUS = { yes: 'מגיעים', maybe: 'אולי', no: 'לא מגיעים' };
  const STATUS_ICON = { yes: I.check, maybe: I.maybe, no: I.x };
  const GSTATE = { new: 'טרם נשלח', sent: 'נשלח', viewed: 'צפו בהזמנה', yes: 'מגיעים', maybe: 'אולי', no: 'לא מגיעים' };
  const EVENT = {
    view: 'כניסה להזמנה',
    open_invite: 'פתיחת ההזמנה',
    download_pdf: 'הורדת ה-PDF',
    share: 'שיתוף',
    copy_link: 'העתקת הקישור',
    nav: 'ניווט',
    calendar: 'הוספה ליומן',
    rsvp_start: 'התחלת מילוי אישור',
    rsvp: 'אישור הגעה',
  };
  const DAY = 86400000;

  let password = W.store.get('wedding-admin-pw', '');
  let data = { rsvps: [], guests: [], events: [], sheetUrl: '' };
  let M = null;
  let loadedAt = 0;
  let loading = false;
  let view = 'overview';
  const ui = {
    rsvpFilter: 'all',
    rsvpSearch: '',
    rsvpSort: 'updated',
    guestState: 'all',
    guestSide: '',
    guestSearch: '',
    eventFilter: '',
    eventLimit: 80,
  };

  /* =========================================================== data */

  const startOfDay = (t) => {
    const d = new Date(t);
    return new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
  };

  function parseRsvpDetail(detail) {
    const [head, name] = String(detail || '').split('|');
    const [status, count] = head.split(':');
    return { status, count: parseInt(count, 10) || 0, name: name || '' };
  }

  function derive() {
    const rsvps = data.rsvps.slice();
    const guests = data.guests.slice();
    const events = data.events.slice().sort((a, b) => a.t - b.t);
    const gById = new Map(guests.map((g) => [g.id, g]));

    // Link responses to invited guests: by personal link, otherwise by phone number.
    const rsvpFor = new Map();
    const guestOfRsvp = new Map();
    rsvps.forEach((r) => {
      if (r.guest && gById.has(r.guest)) {
        rsvpFor.set(r.guest, r);
        guestOfRsvp.set(r.id, gById.get(r.guest));
      }
    });
    const byPhone = new Map(guests.filter((g) => g.phone).map((g) => [W.normalizePhone(g.phone), g]));
    rsvps.forEach((r) => {
      if (guestOfRsvp.has(r.id) || !r.phone) return;
      const g = byPhone.get(W.normalizePhone(r.phone));
      if (g && !rsvpFor.has(g.id)) {
        rsvpFor.set(g.id, r);
        guestOfRsvp.set(r.id, g);
      }
    });

    const viewed = new Set(events.filter((e) => e.guest && e.type === 'view').map((e) => e.guest));
    const lastSeen = new Map();
    events.forEach((e) => e.guest && lastSeen.set(e.guest, e.t));
    const guestState = (g) => {
      const r = rsvpFor.get(g.id);
      if (r) return r.status;
      if (viewed.has(g.id)) return 'viewed';
      if (g.sent) return 'sent';
      return 'new';
    };

    const visitors = new Map();
    events.forEach((e) => {
      if (!e.visitor) return;
      let v = visitors.get(e.visitor);
      if (!v) {
        v = { id: e.visitor, first: e.t, src: e.src || '', device: e.device || '', guest: e.guest || '', types: new Set() };
        visitors.set(e.visitor, v);
      }
      v.types.add(e.type);
      if (!v.guest && e.guest) v.guest = e.guest;
    });

    const by = { yes: [], maybe: [], no: [] };
    rsvps.forEach((r) => (by[r.status] || by.yes).push(r));
    const sum = (list) => list.reduce((s, r) => s + (Number(r.count) || 0), 0);
    const invitedPeople = guests.reduce((s, g) => s + (Number(g.invited) || 0), 0);
    const pendingGuests = guests.filter((g) => !rsvpFor.has(g.id));

    return {
      rsvps,
      guests,
      events,
      gById,
      rsvpFor,
      guestOfRsvp,
      guestState,
      lastSeen,
      visitors,
      by,
      yesPeople: sum(by.yes),
      maybePeople: sum(by.maybe),
      invitedPeople,
      pendingGuests,
      views: events.filter((e) => e.type === 'view').length,
    };
  }

  async function load(opts = {}) {
    if (loading) return;
    loading = true;
    const btn = $('#refreshBtn');
    btn.classList.add('spin');
    try {
      const res = await api.admin('data', null, password);
      data = { rsvps: res.rsvps || [], guests: res.guests || [], events: res.events || [], sheetUrl: res.sheetUrl || '' };
      loadedAt = Date.now();
      M = derive();
      showApp();
      renderAll();
      return true;
    } catch (e) {
      if (opts.fromLogin || e.code === 'bad_password' || e.code === 'password_not_set') throw e;
      W.toast('לא הצלחנו לרענן. ננסה שוב בעוד רגע.');
      return false;
    } finally {
      loading = false;
      btn.classList.remove('spin');
      updatedText();
    }
  }

  async function act(op, payload, okMsg) {
    try {
      await api.admin(op, payload, password);
      if (okMsg) W.toast(okMsg);
      await load();
      return true;
    } catch (e) {
      W.toast('הפעולה לא נשמרה. בדקו את החיבור ונסו שוב.');
      return false;
    }
  }

  function updatedText() {
    const el = $('#updated');
    el.textContent = loadedAt ? 'עודכן ' + W.relTime(loadedAt) : '';
  }

  /* =========================================================== shell */

  function showApp() {
    $('#login').hidden = true;
    $('#app').hidden = false;
  }

  function buildShell() {
    $('#brandCouple').textContent = W.coupleText;
    $('#loginCouple').textContent = W.coupleText;
    const left = W.daysUntil(W.eventDate);
    $('#brandSub').textContent = left > 0 ? `לוח ניהול · עוד ${W.num(left)} ימים` : left === 0 ? 'לוח ניהול · היום החתונה!' : 'לוח ניהול';

    const tabHtml = (bottom) =>
      VIEWS.map(([id, label, icon]) => `<button class="tab" role="tab" type="button" data-tab="${id}" aria-selected="false">${icon}<span>${label}</span>${id === 'rsvps' ? '<span class="badge" hidden></span>' : ''}</button>`).join('');
    $('#tabs').innerHTML = tabHtml(false);
    $('#bottomTabs').innerHTML = tabHtml(true);
    document.querySelectorAll('[data-tab]').forEach((b) => b.addEventListener('click', () => go(b.dataset.tab)));
    document.querySelectorAll('[data-goto]').forEach((b) => b.addEventListener('click', () => go(b.dataset.goto)));

    $('#refreshBtn').innerHTML = I.refresh;
    $('#refreshBtn').addEventListener('click', () => load());
    $('#sheetBtn').innerHTML = I.sheet;
    $('#siteBtn').innerHTML = I.eye;
    $('#logoutBtn').innerHTML = I.logout;
    $('#logoutBtn').addEventListener('click', () => {
      W.store.del('wedding-admin-pw');
      password = '';
      location.reload();
    });
    if (api.mode === 'live') $('#logoutBtn').hidden = false;

    // theme
    const themeBtn = $('#themeBtn');
    const effective = () => document.documentElement.getAttribute('data-theme') || (matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
    const paintTheme = () => {
      themeBtn.innerHTML = effective() === 'dark' ? ICON_SUN : ICON_MOON;
      document.querySelector('meta[name="theme-color"]').content = effective() === 'dark' ? '#121119' : '#fff8f0';
    };
    themeBtn.addEventListener('click', () => {
      const next = effective() === 'dark' ? 'light' : 'dark';
      document.documentElement.setAttribute('data-theme', next);
      W.store.set('wedding-admin-theme', next);
      paintTheme();
      if (M) renderOverview();
    });
    paintTheme();

    // demo
    if (api.mode === 'demo') {
      $('#demoBanner').hidden = false;
      $('#seedBtn').addEventListener('click', async () => {
        await api.admin('seedDemo');
        await load();
        W.toast('מילאנו נתוני דוגמה');
      });
      $('#resetBtn').addEventListener('click', async () => {
        if (!confirm('למחוק את כל נתוני ההדגמה מהדפדפן?')) return;
        await api.admin('resetDemo');
        await load();
      });
    }

    // static icons on buttons
    const label = (sel, icon, text) => ($(sel).innerHTML = `${icon}<span>${text}</span>`);
    label('#addRsvpBtn', I.plus, 'הוספה ידנית');
    label('#exportRsvpBtn', I.download, 'ייצוא לאקסל');
    label('#printBtn', I.print, 'הדפסה');
    label('#sendQueueBtn', I.send, 'שליחה ברצף');
    label('#addGuestBtn', I.plus, 'מוזמן');
    label('#importBtn', I.upload, 'ייבוא רשימה');
    label('#exportGuestsBtn', I.download, 'ייצוא');
    label('#exportEventsBtn', I.download, 'ייצוא');
    label('#copyLinkBtn', I.link, 'העתקה');
    label('#qrDownload', I.download, 'הורדה כתמונה');

    window.addEventListener('hashchange', () => go(location.hash.slice(1) || 'overview', true));
    let rt;
    window.addEventListener('resize', () => {
      clearTimeout(rt);
      rt = setTimeout(() => M && view === 'overview' && renderOverview(), 200);
    });
    document.addEventListener('scroll', Charts.hideTip, { passive: true });
    setInterval(updatedText, 30000);
    setInterval(() => {
      if (document.visibilityState === 'visible' && !document.querySelector('dialog[open]') && !$('#app').hidden) load();
    }, 60000);
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible' && !$('#app').hidden && Date.now() - loadedAt > 30000) load();
    });

    document.querySelectorAll('dialog [data-close]').forEach((b) => b.addEventListener('click', () => b.closest('dialog').close()));
    wireRsvps();
    wireGuests();
    wireShare();
    wireActivity();
  }

  function go(id, fromHash) {
    if (!VIEWS.some((v) => v[0] === id)) id = 'overview';
    view = id;
    document.querySelectorAll('.view').forEach((v) => (v.hidden = v.dataset.view !== id));
    document.querySelectorAll('[data-tab]').forEach((b) => b.setAttribute('aria-selected', String(b.dataset.tab === id)));
    if (!fromHash && location.hash.slice(1) !== id) history.replaceState(null, '', '#' + id);
    if (id === 'rsvps') {
      W.store.set('wedding-admin-seen', Date.now());
      badge();
    }
    Charts.hideTip();
    if (M) renderView(id);
    if (!fromHash) window.scrollTo({ top: 0 });
  }

  function badge() {
    const seen = W.store.get('wedding-admin-seen', 0);
    const n = M ? M.rsvps.filter((r) => (r.updated || 0) > seen).length : 0;
    document.querySelectorAll('[data-tab="rsvps"] .badge').forEach((b) => {
      b.hidden = !n || view === 'rsvps' || !seen;
      b.textContent = n;
    });
  }

  function renderAll() {
    $('#sheetBtn').hidden = !data.sheetUrl;
    if (data.sheetUrl) $('#sheetBtn').href = data.sheetUrl;
    renderView(view);
    badge();
  }
  function renderView(id) {
    ({ overview: renderOverview, rsvps: renderRsvps, guests: renderGuests, share: renderShare, activity: renderActivity })[id]();
  }

  /* =========================================================== overview */

  const invitations = (n) => (n === 1 ? 'הזמנה אחת' : `${W.num(n)} הזמנות`);

  function tile(label, value, sub, dot) {
    return `<div class="tile"><span class="t-label">${dot ? `<span class="t-dot" style="background:var(${dot})"></span>` : ''}${esc(label)}</span><span class="t-value">${value}</span><span class="t-sub">${sub || '&nbsp;'}</span></div>`;
  }

  function renderOverview() {
    const m = M;
    const yesN = m.by.yes.length;
    const responses = m.rsvps.length;
    $('#heroNum').textContent = W.num(m.yesPeople);
    const parts = [yesN === 1 ? 'אישור אחד' : `${W.num(yesN)} אישורים`];
    if (m.invitedPeople) parts.push(`מתוך ${W.num(m.invitedPeople)} מוזמנים (${Math.round((m.yesPeople / m.invitedPeople) * 100)}%)`);
    if (m.maybePeople) parts.push(`ועוד ${W.num(m.maybePeople)} אולי`);
    $('#heroSub').textContent = parts.join(' · ');
    const meter = $('#heroMeter');
    meter.hidden = !m.invitedPeople;
    if (m.invitedPeople) requestAnimationFrame(() => (meter.firstElementChild.style.width = Math.min(100, (m.yesPeople / m.invitedPeople) * 100) + '%'));

    const visitors = m.visitors.size;
    const opened = [...m.visitors.values()].filter((v) => v.types.has('open_invite')).length;
    const sentCount = m.guests.filter((g) => g.sent).length;
    $('#tiles').innerHTML = [
      tile('תשובות', W.num(responses), m.guests.length ? `מתוך ${invitations(m.guests.length)}` : 'הזמנות שענו'),
      tile('לא מגיעים', W.num(m.by.no.length), m.by.no.length === 1 ? 'הזמנה' : 'הזמנות', '--s-no'),
      tile('אולי', W.num(m.maybePeople), `${m.maybePeople === 1 ? 'אורח' : 'אורחים'} · ${invitations(m.by.maybe.length)}`, '--s-maybe'),
      tile('טרם ענו', m.guests.length ? W.num(m.pendingGuests.length) : '—', m.guests.length ? `הזמנות · נשלחו ${W.num(sentCount)}` : 'הוסיפו רשימת מוזמנים'),
      tile('מבקרים', W.num(visitors), `${W.num(m.views)} כניסות`),
      tile('פתחו את ההזמנה', W.num(opened), visitors ? `${Math.round((opened / visitors) * 100)}% מהמבקרים` : ''),
    ].join('');

    Charts.segmented(
      $('#segChart'),
      [
        { label: 'מגיעים', value: m.by.yes.length, color: '--s-yes', on: '--on-yes' },
        { label: 'אולי', value: m.by.maybe.length, color: '--s-maybe', on: '--on-maybe' },
        { label: 'לא מגיעים', value: m.by.no.length, color: '--s-no', on: '--on-no' },
        { label: 'טרם ענו', value: m.pendingGuests.length, color: '--s-pending', on: '--on-pending' },
      ],
      { unit: 'הזמנות', empty: 'כשיגיעו תשובות, תראו כאן את החלוקה' }
    );
    $('#segNote').textContent = m.guests.length ? 'לפי הזמנות (משפחה / זוג / אורח)' : 'לפי הזמנות. הוסיפו רשימת מוזמנים כדי לראות גם מי טרם ענה';

    const vs = [...m.visitors.values()];
    Charts.hbars(
      $('#funnelChart'),
      [
        { label: 'נכנסו להזמנה', value: vs.length },
        { label: 'התחילו לענות', value: vs.filter((v) => v.types.has('rsvp_start') || v.types.has('rsvp')).length },
        { label: 'שלחו תשובה', value: vs.filter((v) => v.types.has('rsvp')).length },
      ],
      { base: vs.length, empty: 'עוד אין כניסות להזמנה' }
    );

    // cumulative confirmed guests per day (by the day each current "yes" first answered)
    const yes = m.by.yes.slice().sort((a, b) => (a.created || a.updated) - (b.created || b.updated));
    const pts = [];
    if (yes.length) {
      const start = startOfDay(yes[0].created || yes[0].updated);
      const end = startOfDay(Date.now());
      const days = Math.min(180, Math.round((end - start) / DAY));
      const first = end - days * DAY;
      let run = yes.filter((r) => startOfDay(r.created || r.updated) < first).reduce((s, r) => s + r.count, 0);
      for (let t = first; t <= end; t += DAY) {
        const add = yes.filter((r) => startOfDay(r.created || r.updated) === t).reduce((s, r) => s + r.count, 0);
        run += add;
        pts.push({ t, v: run, delta: add });
      }
    }
    Charts.line($('#lineChart'), pts, { unit: 'אורחים', label: 'אורחים שאישרו לאורך זמן', empty: 'האישורים הראשונים יופיעו כאן' });

    const end = startOfDay(Date.now());
    const cols = [];
    for (let t = end - 29 * DAY; t <= end; t += DAY) cols.push({ t, v: 0, set: new Set() });
    m.events.forEach((e) => {
      if (e.type !== 'view') return;
      const c = cols[Math.round((startOfDay(e.t) - cols[0].t) / DAY)];
      if (c) c.set.add(e.visitor || e.t);
    });
    cols.forEach((c) => (c.v = c.set.size));
    Charts.columns($('#colChart'), cols, { unit: 'מבקרים', label: 'מבקרים ביום', empty: 'עוד אין כניסות להזמנה' });

    const srcCount = {};
    vs.forEach((v) => (srcCount[v.src] = (srcCount[v.src] || 0) + 1));
    const srcRows = Object.entries(srcCount)
      .map(([k, v]) => ({ label: srcLabel(k), value: v }))
      .sort((a, b) => b.value - a.value);
    Charts.hbars($('#srcChart'), srcRows.slice(0, 6), { empty: 'עוד אין כניסות להזמנה' });
    const mobile = vs.filter((v) => v.device === 'mobile' || v.device === 'tablet').length;
    $('#deviceNote').textContent = vs.length ? `${Math.round((mobile / vs.length) * 100)}% נכנסו מהטלפון` : '';

    // sides
    const sides = {};
    W.sides.forEach((s) => (sides[s] = { invited: 0, yes: 0 }));
    m.guests.forEach((g) => {
      const s = g.side || 'ללא צד';
      sides[s] = sides[s] || { invited: 0, yes: 0 };
      sides[s].invited += Number(g.invited) || 0;
      const r = m.rsvpFor.get(g.id);
      if (r && r.status === 'yes') sides[s].yes += r.count;
    });
    const sideRows = Object.entries(sides).filter(([, v]) => v.invited || v.yes);
    $('#sidesBox').innerHTML = sideRows.length
      ? `<div class="sides">${sideRows
          .map(
            ([name, v]) =>
              `<div class="side-row"><div class="top"><b>${esc(name)}</b><span>${W.num(v.yes)} מתוך ${W.num(v.invited)}</span></div><div class="meter" role="img" aria-label="${esc(name)}: ${v.yes} מתוך ${v.invited}"><span style="width:${v.invited ? Math.min(100, (v.yes / v.invited) * 100) : 0}%"></span></div></div>`
          )
          .join('')}</div>`
      : '<div class="chart-empty">הוסיפו מוזמנים עם צד (בלשונית מוזמנים) כדי לראות כאן כמה אישרו מכל צד</div>';

    const notes = m.rsvps.filter((r) => r.note).sort((a, b) => b.updated - a.updated).slice(0, 12);
    $('#notesBox').innerHTML = notes.length
      ? notes.map((r) => `<li>${esc(r.note)}<small>${esc(r.name)} · ${esc(W.relTime(r.updated))}</small></li>`).join('')
      : '<li class="muted" style="background:none;padding:0">הברכות שהאורחים כותבים יופיעו כאן</li>';

    $('#recentFeed').innerHTML = feedHtml(m.events.slice(-8).reverse());
  }

  /* =========================================================== feed */

  function feedHtml(events) {
    if (!events.length) return '<li class="empty-li">עוד אין פעילות. שתפו את ההזמנה והנתונים יתחילו להגיע.</li>';
    return events
      .map((e) => {
        const g = e.guest && M.gById.get(e.guest);
        let text = EVENT[e.type] || e.type;
        let icoClass = '';
        let icon = I.eye;
        let who = g ? g.name : '';
        if (e.type === 'rsvp') {
          const r = parseRsvpDetail(e.detail);
          who = who || r.name;
          text = r.status === 'yes' ? `אישור הגעה · ${r.count === 1 ? 'אורח אחד' : W.num(r.count) + ' אורחים'}` : r.status === 'maybe' ? 'תשובה: אולי' : 'לא יוכלו להגיע';
          icoClass = r.status === 'no' ? 'i-no' : 'i-rsvp';
          icon = STATUS_ICON[r.status] || I.check;
        } else if (e.type === 'share' || e.type === 'copy_link') {
          if (e.type === 'share') text = e.detail === 'more' ? 'שיתוף מהטלפון' : `שיתוף ב${srcLabel(e.detail)}`;
          icoClass = 'i-share';
          icon = I.share;
        } else if (e.type === 'open_invite' || e.type === 'download_pdf') {
          icoClass = 'i-open';
          icon = e.type === 'download_pdf' ? I.download : I.eye;
        } else if (e.type === 'nav') {
          text = `ניווט ב-${e.detail === 'waze' ? 'Waze' : 'Google Maps'}`;
          icon = I.pin;
        } else if (e.type === 'calendar') {
          icon = I.calendar;
        } else if (e.type === 'rsvp_start') {
          icon = I.edit;
        }
        const meta = [who ? '' : 'קישור כללי', srcLabel(e.src || ''), e.device === 'desktop' ? 'מחשב' : e.device ? 'טלפון' : ''].filter(Boolean).join(' · ');
        return `<li><span class="f-ico ${icoClass}">${icon}</span><span class="f-main">${who ? `<b>${esc(who)}</b> · ` : ''}${esc(text)}<span class="f-meta">${esc(meta)}</span></span><time datetime="${new Date(e.t).toISOString()}" title="${esc(W.shortDate(new Date(e.t)) + ' ' + W.timeOf(new Date(e.t)))}">${esc(W.relTime(e.t))}</time></li>`;
      })
      .join('');
  }

  /* =========================================================== rsvps */

  function wireRsvps() {
    $('#rsvpSearch').addEventListener('input', (e) => {
      ui.rsvpSearch = e.target.value.trim();
      renderRsvps();
    });
    $('#rsvpSort').addEventListener('change', (e) => {
      ui.rsvpSort = e.target.value;
      renderRsvps();
    });
    $('#addRsvpBtn').addEventListener('click', () => openRsvpDialog(null));
    $('#exportRsvpBtn').addEventListener('click', exportRsvps);
    $('#printBtn').addEventListener('click', () => window.print());
    $('#rsvpTable tbody').addEventListener('click', (e) => {
      const b = e.target.closest('[data-act]');
      if (!b) return;
      const r = M.rsvps.find((x) => x.id === b.dataset.id);
      if (!r) return;
      if (b.dataset.act === 'edit') openRsvpDialog(r);
      if (b.dataset.act === 'del' && confirm(`למחוק את התשובה של ${r.name}?`)) act('deleteRsvp', { id: r.id }, 'נמחק');
    });
    $('#rsvpEditForm').addEventListener('submit', (e) => {
      if (e.submitter && e.submitter.value !== 'save') return;
      e.preventDefault();
      const f = e.target;
      const status = f.status.value;
      const rsvp = {
        id: f.dataset.id || 'm:' + W.uid(10),
        name: f.name.value.trim(),
        status,
        count: status === 'no' ? 0 : parseInt(f.count.value, 10) || 1,
        phone: f.phone.value.trim(),
        note: f.note.value.trim(),
        guest: f.dataset.guest || '',
        src: f.dataset.src || 'admin',
        device: f.dataset.device || '',
      };
      if (!rsvp.name) return f.name.focus();
      $('#rsvpDialog').close();
      act('saveRsvp', { rsvp }, 'נשמר');
    });
    $('#rsvpEditForm').addEventListener('change', (e) => {
      if (e.target.name === 'status') e.currentTarget.count.closest('.field').hidden = e.target.value === 'no';
    });
  }

  function openRsvpDialog(r) {
    const f = $('#rsvpEditForm');
    f.reset();
    f.dataset.id = r ? r.id : '';
    f.dataset.guest = r ? r.guest || '' : '';
    f.dataset.src = r ? r.src || '' : 'admin';
    f.dataset.device = r ? r.device || '' : '';
    $('#rsvpDialogTitle').textContent = r ? 'עריכת תשובה' : 'הוספת אישור (למשל, מי שענה בטלפון)';
    if (r) {
      f.name.value = r.name;
      f.status.value = r.status;
      f.count.value = r.count || 1;
      f.phone.value = r.phone || '';
      f.note.value = r.note || '';
    }
    f.count.closest('.field').hidden = f.status.value === 'no';
    $('#rsvpDialog').showModal();
  }

  function renderRsvps() {
    const m = M;
    const counts = { all: m.rsvps.length, yes: m.by.yes.length, maybe: m.by.maybe.length, no: m.by.no.length };
    const chipDefs = [
      ['all', 'הכל', ''],
      ['yes', 'מגיעים', '--s-yes'],
      ['maybe', 'אולי', '--s-maybe'],
      ['no', 'לא מגיעים', '--s-no'],
    ];
    $('#rsvpChips').innerHTML = chipDefs
      .map(([k, l, c]) => `<button type="button" class="chip" data-f="${k}" aria-pressed="${ui.rsvpFilter === k}">${c ? `<span class="t-dot" style="background:var(${c})"></span>` : ''}${l} <span class="n">${counts[k]}</span></button>`)
      .join('');
    $('#rsvpChips').querySelectorAll('.chip').forEach((c) =>
      c.addEventListener('click', () => {
        ui.rsvpFilter = c.dataset.f;
        renderRsvps();
      })
    );

    const q = ui.rsvpSearch.toLowerCase();
    let list = m.rsvps.filter((r) => ui.rsvpFilter === 'all' || r.status === ui.rsvpFilter);
    if (q) list = list.filter((r) => [r.name, r.phone, r.note, W.normalizePhone(r.phone)].join(' ').toLowerCase().includes(q.replace(/^0/, '')) || (r.name || '').toLowerCase().includes(q));
    const sorters = {
      updated: (a, b) => (b.updated || 0) - (a.updated || 0),
      name: (a, b) => a.name.localeCompare(b.name, 'he'),
      count: (a, b) => b.count - a.count,
    };
    list.sort(sorters[ui.rsvpSort]);

    const body = $('#rsvpTable tbody');
    body.innerHTML = list
      .map((r) => {
        const g = m.guestOfRsvp.get(r.id);
        const phone = r.phone || (g && g.phone) || '';
        const wa = phone ? W.shareUrls('', '', phone).whatsapp.replace(/\?text=$/, '') : '';
        return `<tr>
          <td class="name">${esc(r.name)}${g && g.side ? ` <span class="src-tag">· ${esc(g.side)}</span>` : ''}</td>
          <td class="c-status"><span class="pill ${r.status}">${STATUS_ICON[r.status]}${STATUS[r.status]}</span></td>
          <td class="num" data-l="אורחים:">${r.status === 'no' ? '—' : W.num(r.count)}</td>
          <td class="phone" data-l="">${phone ? `<a href="tel:+${esc(W.normalizePhone(phone))}">${esc(W.displayPhone(phone))}</a>` : ''}</td>
          <td class="note-cell wide">${esc(r.note || '')}</td>
          <td data-l="מקור:"><span class="src-tag">${esc(srcLabel(r.src || ''))}</span></td>
          <td class="dim" data-l="">${r.updated ? `<time title="${esc(W.shortDate(new Date(r.updated)))}">${esc(W.relTime(r.updated))}</time>${r.edits ? ' · עודכן' : ''}` : ''}</td>
          <td class="c-actions"><div class="row-actions">
            ${wa ? `<a class="icon-btn" href="${esc(wa)}" target="_blank" rel="noopener" title="וואטסאפ" aria-label="וואטסאפ ל${esc(r.name)}">${I.whatsapp}</a>` : ''}
            <button class="icon-btn" type="button" data-act="edit" data-id="${esc(r.id)}" title="עריכה" aria-label="עריכת ${esc(r.name)}">${I.edit}</button>
            <button class="icon-btn" type="button" data-act="del" data-id="${esc(r.id)}" title="מחיקה" aria-label="מחיקת ${esc(r.name)}">${I.trash}</button>
          </div></td>
        </tr>`;
      })
      .join('');
    const emptyEl = $('#rsvpEmpty');
    emptyEl.hidden = list.length > 0;
    emptyEl.textContent = m.rsvps.length ? 'אין תוצאות לחיפוש הזה' : 'עוד אין אישורי הגעה. ברגע שמישהו יענה, הוא יופיע כאן.';
    const people = list.filter((r) => r.status === 'yes').reduce((s, r) => s + r.count, 0);
    $('#rsvpTotal').textContent = list.length ? `מוצגות ${W.num(list.length)} תשובות · ${W.num(people)} אורחים מגיעים` : '';
  }

  function exportRsvps() {
    const rows = [['שם', 'תשובה', 'מספר אורחים', 'טלפון', 'ברכה / הערה', 'צד', 'מקור', 'התקבל', 'עודכן']];
    M.rsvps
      .slice()
      .sort((a, b) => a.name.localeCompare(b.name, 'he'))
      .forEach((r) => {
        const g = M.guestOfRsvp.get(r.id);
        rows.push([r.name, STATUS[r.status], r.status === 'no' ? 0 : r.count, W.displayPhone(r.phone), r.note, g ? g.side : '', srcLabel(r.src || ''), fmtFull(r.created), fmtFull(r.updated)]);
      });
    W.download(`אישורי הגעה ${W.shortDate(new Date()).replace(/\//g, '-')}.csv`, W.csv(rows), 'text/csv;charset=utf-8');
  }
  const fmtFull = (t) => (t ? `${W.shortDate(new Date(t))} ${W.timeOf(new Date(t))}` : '');

  /* =========================================================== guests */

  const personalText = (g, link) => W.fill(cfg.personalShareText || cfg.shareText, { name: g.name, link, couple: W.coupleText, date: shortEventDate() });
  const shortEventDate = () => (W.eventDate ? W.fmtDate(W.eventDate, { weekday: 'long', day: 'numeric', month: 'long' }) : '');
  const personalLink = (g, src) => W.link({ g: g.id, src });

  function wireGuests() {
    $('#guestSearch').addEventListener('input', (e) => {
      ui.guestSearch = e.target.value.trim();
      renderGuests();
    });
    const sideSel = $('#sideFilter');
    sideSel.innerHTML = `<option value="">כל הצדדים</option>${W.sides.map((s) => `<option>${esc(s)}</option>`).join('')}`;
    sideSel.addEventListener('change', (e) => {
      ui.guestSide = e.target.value;
      renderGuests();
    });
    $('#guestEditForm [name="side"]').innerHTML = `<option value="">—</option>${W.sides.map((s) => `<option>${esc(s)}</option>`).join('')}`;
    $('#addGuestBtn').addEventListener('click', () => openGuestDialog(null));
    $('#importBtn').addEventListener('click', openImport);
    $('#exportGuestsBtn').addEventListener('click', exportGuests);
    $('#sendQueueBtn').addEventListener('click', openQueue);

    $('#guestTable tbody').addEventListener('click', async (e) => {
      const b = e.target.closest('[data-act]');
      if (!b) return;
      const g = M.gById.get(b.dataset.id);
      if (!g) return;
      const a = b.dataset.act;
      if (a === 'edit') openGuestDialog(g);
      if (a === 'del' && confirm(`למחוק את ${g.name} מרשימת המוזמנים?`)) act('deleteGuest', { id: g.id }, 'נמחק');
      if (a === 'copy') {
        const link = personalLink(g, 'copy');
        (await W.copy(link)) ? W.toast(`הקישור של ${g.name} הועתק`) : prompt('הקישור:', link);
        markSent([g.id]);
      }
      if (a === 'wa' || a === 'tg' || a === 'sms') markSent([g.id]); // the link itself opens the app
    });

    $('#guestEditForm').addEventListener('submit', (e) => {
      if (e.submitter && e.submitter.value !== 'save') return;
      e.preventDefault();
      const f = e.target;
      const prev = f.dataset.id ? M.gById.get(f.dataset.id) : null;
      const g = Object.assign({}, prev || { id: W.uid(8), created: Date.now(), sent: 0 }, {
        name: f.name.value.trim(),
        phone: f.phone.value.trim(),
        side: f.side.value,
        invited: parseInt(f.invited.value, 10) || 0,
        notes: f.notes.value.trim(),
      });
      if (!g.name) return f.name.focus();
      $('#guestDialog').close();
      act('saveGuests', { guests: [g] }, prev ? 'נשמר' : 'נוסף לרשימה');
    });

    const text = $('#importText');
    text.addEventListener('input', previewImport);
    $('#importFile').addEventListener('change', async (e) => {
      const file = e.target.files[0];
      if (!file) return;
      text.value = await file.text();
      previewImport();
    });
    $('#importForm').addEventListener('submit', (e) => {
      if (e.submitter && e.submitter.value !== 'save') return;
      e.preventDefault();
      const list = parseImport(text.value);
      if (!list.length) return;
      $('#importDialog').close();
      act('saveGuests', { guests: list }, `${list.length} מוזמנים נשמרו`);
    });
  }

  async function markSent(ids) {
    const fresh = ids.filter((id) => {
      const g = M.gById.get(id);
      return g && !g.sent;
    });
    if (!fresh.length) return;
    fresh.forEach((id) => {
      const g = data.guests.find((x) => x.id === id);
      if (g) g.sent = Date.now();
    });
    M = derive();
    if (view === 'guests') renderGuests();
    try {
      await api.admin('markSent', { ids: fresh }, password);
    } catch (e) {}
  }

  function openGuestDialog(g) {
    const f = $('#guestEditForm');
    f.reset();
    f.dataset.id = g ? g.id : '';
    $('#guestDialogTitle').textContent = g ? 'עריכת מוזמן' : 'מוזמן חדש';
    if (g) {
      f.name.value = g.name;
      f.phone.value = g.phone || '';
      f.side.value = g.side || '';
      f.invited.value = g.invited || 0;
      f.notes.value = g.notes || '';
    }
    $('#guestDialog').showModal();
  }

  function parseImport(text) {
    const existingByPhone = new Map(M.guests.filter((g) => g.phone).map((g) => [W.normalizePhone(g.phone), g]));
    const existingByName = new Map(M.guests.map((g) => [g.name.trim(), g]));
    const out = [];
    String(text || '')
      .split(/\r?\n/)
      .map((l) => l.trim())
      .filter(Boolean)
      .forEach((line, i) => {
        const cells = line.split(line.includes('\t') ? '\t' : /[,;]/).map((c) => c.trim().replace(/^"|"$/g, ''));
        let name = '';
        let phone = '';
        let invited = 0;
        let side = '';
        cells.forEach((c) => {
          const digits = c.replace(/\D/g, '');
          if (!phone && digits.length >= 9 && /^[\d\s()+\-]+$/.test(c)) phone = c;
          else if (!invited && /^\d{1,2}$/.test(c)) invited = parseInt(c, 10);
          else if (!side && W.sides.includes(c)) side = c;
          else if (!name && c) name = c;
        });
        if (!name || (i === 0 && /^(שם|name)$/i.test(name))) return;
        const prev = (phone && existingByPhone.get(W.normalizePhone(phone))) || existingByName.get(name);
        out.push(Object.assign({}, prev || { id: W.uid(8), created: Date.now(), sent: 0, notes: '' }, { name, phone: phone || (prev && prev.phone) || '', invited: invited || (prev && prev.invited) || 2, side: side || (prev && prev.side) || '' }));
      });
    return out;
  }
  function previewImport() {
    const list = parseImport($('#importText').value);
    const people = list.reduce((s, g) => s + g.invited, 0);
    const updates = list.filter((g) => M.gById.has(g.id)).length;
    $('#importPreview').textContent = list.length ? `נמצאו ${W.num(list.length)} מוזמנים (${W.num(people)} אנשים)${updates ? ` · ${updates} כבר ברשימה ויעודכנו` : ''}` : '';
    $('#importGo').disabled = !list.length;
  }
  function openImport() {
    $('#importText').value = '';
    $('#importFile').value = '';
    previewImport();
    $('#importDialog').showModal();
  }

  function guestStateGroup(s) {
    return s === 'yes' || s === 'no' || s === 'maybe' ? 'answered' : s;
  }

  function renderGuests() {
    const m = M;
    const states = m.guests.map((g) => guestStateGroup(m.guestState(g)));
    const count = (k) => states.filter((s) => s === k).length;
    const pipes = [
      ['all', 'כל המוזמנים', m.guests.length],
      ['new', 'טרם נשלח', count('new')],
      ['sent', 'נשלח, טרם נצפה', count('sent')],
      ['viewed', 'צפו, טרם ענו', count('viewed')],
      ['answered', 'ענו', count('answered')],
    ];
    $('#pipeline').innerHTML = pipes.map(([k, l, n]) => `<button type="button" class="pipe" data-s="${k}" aria-pressed="${ui.guestState === k}"><b>${W.num(n)}</b><span>${l}</span></button>`).join('');
    $('#pipeline').querySelectorAll('.pipe').forEach((p) =>
      p.addEventListener('click', () => {
        ui.guestState = ui.guestState === p.dataset.s ? 'all' : p.dataset.s;
        renderGuests();
      })
    );

    const q = ui.guestSearch.toLowerCase();
    let list = m.guests.filter((g) => {
      if (ui.guestSide && g.side !== ui.guestSide) return false;
      if (ui.guestState !== 'all' && guestStateGroup(m.guestState(g)) !== ui.guestState) return false;
      if (q && !(g.name.toLowerCase().includes(q) || W.normalizePhone(g.phone).includes(W.normalizePhone(q)) || (g.phone || '').includes(q))) return false;
      return true;
    });
    list.sort((a, b) => a.name.localeCompare(b.name, 'he'));

    $('#guestTable tbody').innerHTML = list
      .map((g) => {
        const st = m.guestState(g);
        const r = m.rsvpFor.get(g.id);
        const pillClass = { new: 'new', sent: 'sent', viewed: 'viewed', yes: 'yes', maybe: 'maybe', no: 'no' }[st];
        const stText = st === 'yes' && r ? `מגיעים · ${r.count}` : GSTATE[st];
        const seen = m.lastSeen.get(g.id);
        const sub = st === 'sent' && g.sent ? `נשלח ${W.relTime(g.sent)}` : st === 'viewed' && seen ? `נצפה ${W.relTime(seen)}` : '';
        const has = !!g.phone;
        const u = (src) => W.shareUrls(personalText(g, personalLink(g, src)), personalLink(g, src), g.phone);
        return `<tr>
          <td class="name">${esc(g.name)}${g.notes ? `<div class="src-tag">${esc(g.notes)}</div>` : ''}</td>
          <td class="c-status"><span class="pill ${pillClass}">${STATUS_ICON[st] || ''}${esc(stText)}</span>${sub ? `<div class="dim">${esc(sub)}</div>` : ''}</td>
          <td class="phone" data-l="">${has ? `<a href="tel:+${esc(W.normalizePhone(g.phone))}">${esc(W.displayPhone(g.phone))}</a>` : '<span class="dim">אין טלפון</span>'}</td>
          <td data-l="צד:">${esc(g.side || '')}</td>
          <td class="num" data-l="מוזמנים:">${W.num(g.invited)}</td>
          <td class="c-actions"><div class="row-actions">
            <div class="send-actions">
              <a class="send-btn wa" data-act="wa" data-id="${g.id}" href="${esc(u('wa').whatsapp)}" target="_blank" rel="noopener" title="שליחה בוואטסאפ" aria-label="שליחה בוואטסאפ ל${esc(g.name)}" ${has ? '' : 'aria-disabled="true"'}>${I.whatsapp}</a>
              <a class="send-btn sms" data-act="sms" data-id="${g.id}" href="${esc(u('sms').sms)}" title="שליחה ב-SMS" aria-label="שליחה ב-SMS ל${esc(g.name)}" ${has ? '' : 'aria-disabled="true"'}>${I.sms}</a>
              <a class="send-btn tg" data-act="tg" data-id="${g.id}" href="${esc(u('tg').telegram)}" target="_blank" rel="noopener" title="שליחה בטלגרם" aria-label="שליחה בטלגרם ל${esc(g.name)}">${I.telegram}</a>
              <button class="send-btn copy" type="button" data-act="copy" data-id="${g.id}" title="העתקת קישור אישי" aria-label="העתקת קישור אישי ל${esc(g.name)}">${I.link}</button>
            </div>
            <div class="send-actions">
              <button class="icon-btn" type="button" data-act="edit" data-id="${g.id}" title="עריכה" aria-label="עריכת ${esc(g.name)}">${I.edit}</button>
              <button class="icon-btn" type="button" data-act="del" data-id="${g.id}" title="מחיקה" aria-label="מחיקת ${esc(g.name)}">${I.trash}</button>
            </div>
          </div></td>
        </tr>`;
      })
      .join('');


    const emptyEl = $('#guestEmpty');
    emptyEl.hidden = list.length > 0;
    if (!m.guests.length) {
      emptyEl.innerHTML = `<p>עוד אין רשימת מוזמנים.<br>הוסיפו מוזמנים כדי לשלוח לכל אחד קישור אישי ולדעת בדיוק מי צפה ומי ענה.</p><button class="btn btn-primary" type="button" id="emptyImport">${I.upload}<span>ייבוא מאקסל</span></button>`;
      $('#emptyImport').addEventListener('click', openImport);
    } else emptyEl.textContent = 'אין מוזמנים שמתאימים לסינון';

    const invited = list.reduce((s, g) => s + (Number(g.invited) || 0), 0);
    $('#guestTotal').textContent = list.length ? `${W.num(list.length)} הזמנות · ${W.num(invited)} מוזמנים` : '';
    const queue = queueList();
    $('#sendQueueBtn').disabled = !queue.length;
    $('#sendQueueBtn').querySelector('span').textContent = queue.length ? `שליחה ברצף (${queue.length})` : 'שליחה ברצף';
  }

  function exportGuests() {
    const rows = [['שם', 'טלפון', 'צד', 'מוזמנים', 'מצב', 'אישרו', 'הערות', 'נשלח', 'קישור אישי']];
    M.guests
      .slice()
      .sort((a, b) => a.name.localeCompare(b.name, 'he'))
      .forEach((g) => {
        const r = M.rsvpFor.get(g.id);
        rows.push([g.name, W.displayPhone(g.phone), g.side, g.invited, GSTATE[M.guestState(g)], r && r.status === 'yes' ? r.count : 0, g.notes, fmtFull(g.sent), personalLink(g, '')]);
      });
    W.download(`מוזמנים ${W.shortDate(new Date()).replace(/\//g, '-')}.csv`, W.csv(rows), 'text/csv;charset=utf-8');
  }

  /* ---------- send one after another ---------- */
  const queueList = () => (M ? M.guests.filter((g) => g.phone && !g.sent && !M.rsvpFor.has(g.id)).sort((a, b) => a.name.localeCompare(b.name, 'he')) : []);
  let queue = [];
  let qi = 0;
  function openQueue() {
    queue = queueList();
    qi = 0;
    renderQueue();
    $('#queueDialog').showModal();
  }
  function renderQueue() {
    const card = $('#queueCard');
    $('#queueInfo').textContent = queue.length ? `${W.num(queue.length)} מוזמנים עם טלפון שעוד לא קיבלו הזמנה. לוחצים, שולחים בוואטסאפ, חוזרים לכאן — והבא בתור מחכה.` : '';
    if (qi >= queue.length) {
      card.innerHTML = `<p class="q-name">סיימתם!</p><p class="muted">כל ההזמנות ברשימה נשלחו.</p>`;
      return;
    }
    const g = queue[qi];
    const text = personalText(g, personalLink(g, 'wa'));
    const urls = W.shareUrls(text, personalLink(g, 'wa'), g.phone);
    const smsUrls = W.shareUrls(personalText(g, personalLink(g, 'sms')), personalLink(g, 'sms'), g.phone);
    card.innerHTML = `
      <div class="queue-progress"><span style="width:${(qi / queue.length) * 100}%"></span></div>
      <span class="muted small">${qi + 1} מתוך ${queue.length}</span>
      <span class="q-name">${esc(g.name)}</span>
      <span class="phone" dir="ltr" style="justify-self:end">${esc(W.displayPhone(g.phone))}</span>
      <p class="q-msg">${esc(text)}</p>
      <div class="q-actions">
        <a class="btn wa" id="qWa" href="${esc(urls.whatsapp)}" target="_blank" rel="noopener">${I.whatsapp}<span>שליחה בוואטסאפ</span></a>
        <a class="btn sms" id="qSms" href="${esc(smsUrls.sms)}">${I.sms}<span>SMS</span></a>
        <button class="btn btn-ghost" type="button" id="qSkip">דילוג</button>
      </div>`;
    const next = () => {
      qi++;
      setTimeout(renderQueue, 300);
    };
    $('#qWa').addEventListener('click', () => {
      markSent([g.id]);
      next();
    });
    $('#qSms').addEventListener('click', () => {
      markSent([g.id]);
      next();
    });
    $('#qSkip').addEventListener('click', () => {
      qi++;
      renderQueue();
    });
  }

  /* =========================================================== share */

  function shareMessage() {
    return W.store.get('wedding-share-msg', '') || cfg.shareText || '';
  }

  function wireShare() {
    const msg = $('#shareMsg');
    msg.value = shareMessage();
    msg.addEventListener('input', () => {
      W.store.set('wedding-share-msg', msg.value);
      renderShare();
    });
    $('#resetMsgBtn').addEventListener('click', () => {
      W.store.del('wedding-share-msg');
      msg.value = shareMessage();
      renderShare();
    });
    $('#copyLinkBtn').addEventListener('click', async () => {
      (await W.copy(W.link({ src: 'copy' }))) ? W.toast('הקישור הועתק') : prompt('הקישור:', W.link({ src: 'copy' }));
    });
    $('#qrDownload').addEventListener('click', downloadQr);
  }

  function fillShare(src) {
    const link = W.link({ src });
    return { link, text: W.fill($('#shareMsg').value, { couple: W.coupleText, link, date: shortEventDate(), name: '' }) };
  }

  function renderShare() {
    $('#shareLink').textContent = W.siteUrl;
    const btns = $('#shareBtns');
    const defs = [
      ['wa', 'וואטסאפ', I.whatsapp, 'b-wa'],
      ['tg', 'טלגרם', I.telegram, 'b-tg'],
      ['sms', 'SMS', I.sms, 'b-sms'],
    ];
    if (navigator.share) defs.push(['share', 'עוד…', I.share, 'b-more']);
    btns.innerHTML = defs.map(([k, l, ic, c]) => `<button type="button" class="btn ${c}" data-ch="${k}">${ic}<span>${l}</span></button>`).join('');
    btns.querySelectorAll('[data-ch]').forEach((b) =>
      b.addEventListener('click', async () => {
        const ch = b.dataset.ch;
        const { link, text } = fillShare(ch);
        const urls = W.shareUrls(text, link);
        if (ch === 'share') {
          try {
            await navigator.share({ text: text.replace(link, '').trim(), url: link });
          } catch (e) {}
        } else if (ch === 'sms') location.href = urls.sms;
        else window.open(ch === 'wa' ? urls.whatsapp : urls.telegram, '_blank', 'noopener');
      })
    );

    // WhatsApp-style preview
    const v = (window.INVITATION_PAGES && window.INVITATION_PAGES.v) || '';
    $('#ogImg').src = 'invitation/og.jpg' + (v ? '?v=' + v : '');
    $('#ogTitle').textContent = `${W.coupleText} מתחתנים!`;
    const venue = (cfg.venue && cfg.venue.name) || '';
    $('#ogDesc').textContent = [W.eventDate ? W.longDate(W.eventDate) : '', venue].filter(Boolean).join(' · ');
    try {
      $('#ogHost').textContent = new URL(W.siteUrl).host;
    } catch (e) {}
    $('#waText').textContent = fillShare('wa').text;

    // QR
    const box = $('#qrBox');
    const link = W.link({ src: 'qr' });
    if (box.dataset.link !== link && window.qrcode) {
      const qr = window.qrcode(0, 'M');
      qr.addData(link);
      qr.make();
      box.innerHTML = qr.createSvgTag({ cellSize: 6, margin: 2, scalable: true });
      box.querySelector('svg').setAttribute('aria-label', 'קוד QR להזמנה');
      box.dataset.link = link;
    }
  }

  function downloadQr() {
    if (!window.qrcode) return;
    const qr = window.qrcode(0, 'M');
    qr.addData(W.link({ src: 'qr' }));
    qr.make();
    const n = qr.getModuleCount();
    const size = 1200;
    const margin = 4;
    const cell = Math.floor(size / (n + margin * 2));
    const c = document.createElement('canvas');
    c.width = c.height = cell * (n + margin * 2);
    const ctx = c.getContext('2d');
    ctx.fillStyle = '#fff';
    ctx.fillRect(0, 0, c.width, c.height);
    ctx.fillStyle = '#221f36';
    for (let r = 0; r < n; r++) for (let col = 0; col < n; col++) if (qr.isDark(r, col)) ctx.fillRect((col + margin) * cell, (r + margin) * cell, cell, cell);
    c.toBlob((blob) => W.download('qr-הזמנה.png', blob));
  }

  /* =========================================================== activity */

  function wireActivity() {
    const sel = $('#eventFilter');
    sel.innerHTML = `<option value="">כל הפעולות</option>${Object.entries(EVENT)
      .map(([k, l]) => `<option value="${k}">${l}</option>`)
      .join('')}`;
    sel.addEventListener('change', () => {
      ui.eventFilter = sel.value;
      ui.eventLimit = 80;
      renderActivity();
    });
    $('#moreEvents').addEventListener('click', () => {
      ui.eventLimit += 120;
      renderActivity();
    });
    $('#exportEventsBtn').addEventListener('click', () => {
      const rows = [['זמן', 'פעולה', 'מוזמן', 'פרטים', 'מקור', 'מכשיר', 'מזהה מבקר']];
      M.events.forEach((e) => {
        const g = e.guest && M.gById.get(e.guest);
        rows.push([fmtFull(e.t), EVENT[e.type] || e.type, g ? g.name : '', e.detail, srcLabel(e.src || ''), e.device, e.visitor]);
      });
      W.download('פעילות.csv', W.csv(rows), 'text/csv;charset=utf-8');
    });
  }

  function renderActivity() {
    const all = M.events.filter((e) => !ui.eventFilter || e.type === ui.eventFilter).reverse();
    $('#activityFeed').innerHTML = feedHtml(all.slice(0, ui.eventLimit));
    $('#moreEvents').hidden = all.length <= ui.eventLimit;
  }

  /* =========================================================== login & start */

  async function tryLogin(pw) {
    password = pw;
    try {
      await load({ fromLogin: true });
      W.store.set('wedding-admin-pw', pw);
      return '';
    } catch (e) {
      if (e.code === 'bad_password') return 'הסיסמה לא נכונה';
      if (e.code === 'password_not_set') return 'צריך לבחור סיסמה בקובץ Code.gs (ADMIN_PASSWORD) ולפרוס מחדש';
      return 'אין חיבור לשרת. בדקו את backendUrl בקובץ config.js';
    }
  }

  function showLogin(err) {
    $('#app').hidden = true;
    $('#login').hidden = false;
    const e = $('#loginError');
    e.hidden = !err;
    e.textContent = err || '';
    $('#password').focus();
  }

  function start() {
    buildShell();
    go(location.hash.slice(1) || 'overview', true);
    $('#loginForm').addEventListener('submit', async (e) => {
      e.preventDefault();
      const btn = $('#loginBtn');
      btn.disabled = true;
      const err = await tryLogin($('#password').value);
      btn.disabled = false;
      if (err) showLogin(err);
    });
    if (api.mode === 'demo') {
      showApp();
      load();
    } else if (password) {
      tryLogin(password).then((err) => err && showLogin(err === 'הסיסמה לא נכונה' ? '' : err));
    } else showLogin('');
  }

  start();
})();
