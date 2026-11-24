/* Admin dashboard */
(function () {
  'use strict';
  const W = window.W;
  const api = window.API;
  const cfg = W.cfg;
  const I = W.icon;
  const Charts = window.Charts;
  const t = W.t;
  const $ = (s) => document.querySelector(s);
  const esc = W.esc;
  const span = (text) => `<span>${esc(text)}</span>`;

  const ICON_MOON = '<svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5Z"/></svg>';
  const ICON_SUN = '<svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><circle cx="12" cy="12" r="4"/><path d="M12 2.5v2M12 19.5v2M2.5 12h2M19.5 12h2M5.3 5.3l1.4 1.4M17.3 17.3l1.4 1.4M5.3 18.7l1.4-1.4M17.3 6.7l1.4-1.4"/></svg>';

  const VIEWS = [
    ['overview', I.chart],
    ['rsvps', I.check],
    ['guests', I.users],
    ['share', I.send],
    ['activity', I.list],
  ];
  const EVENT_TYPES = ['view', 'open_invite', 'download_pdf', 'share', 'copy_link', 'nav', 'calendar', 'rsvp_start', 'rsvp'];
  const srcLabel = (s) => {
    const k = 'src.' + (s || 'none');
    const v = t(k);
    return v === k ? s : v;
  };
  const statusLabel = (s) => t('a.s.' + s);
  const STATUS_ICON = { yes: I.check, maybe: I.maybe, no: I.x };
  const gstateLabel = (s) => (s === 'yes' || s === 'maybe' || s === 'no' ? statusLabel(s) : t('a.gs.' + s));
  const langLabel = (l) => t(l === 'en' ? 'lang.en' : 'lang.he');
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
    shareLang: W.lang,
  };

  /* =========================================================== data */

  const startOfDay = (ts) => {
    const d = new Date(ts);
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
      W.toast(t('a.refreshFail'));
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
      W.toast(t('a.failed'));
      return false;
    }
  }

  function updatedText() {
    $('#updated').textContent = loadedAt ? t('a.updated', { t: W.relTime(loadedAt) }) : '';
  }

  /* =========================================================== shell */

  function showApp() {
    $('#login').hidden = true;
    $('#app').hidden = false;
  }

  function buildShell() {
    document.title = `${t('a.title')} · ${W.coupleText}`;
    $('#brandCouple').textContent = W.coupleText;
    $('#loginCouple').textContent = W.coupleText;
    const left = W.daysUntil(W.eventDate);
    $('#brandSub').textContent = left > 0 ? t('a.subDays', { n: left }) : left === 0 ? t('a.subToday') : t('a.sub');

    const tabHtml = () =>
      VIEWS.map(([id, icon]) => `<button class="tab" role="tab" type="button" data-tab="${id}" aria-selected="false">${icon}${span(t('a.t.' + id))}${id === 'rsvps' ? '<span class="badge" hidden></span>' : ''}</button>`).join('');
    $('#tabs').innerHTML = tabHtml();
    $('#bottomTabs').innerHTML = tabHtml();
    document.querySelectorAll('[data-tab]').forEach((b) => b.addEventListener('click', () => go(b.dataset.tab)));
    document.querySelectorAll('[data-goto]').forEach((b) => b.addEventListener('click', () => go(b.dataset.goto)));

    $('#refreshBtn').innerHTML = I.refresh;
    $('#refreshBtn').addEventListener('click', () => load());
    $('#sheetBtn').innerHTML = I.sheet;
    $('#siteBtn').innerHTML = I.eye;
    $('#siteBtn').href = W.lang === 'en' ? 'en.html' : './';
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
        W.toast(t('a.seeded'));
      });
      $('#resetBtn').addEventListener('click', async () => {
        if (!confirm(t('a.resetQ'))) return;
        await api.admin('resetDemo');
        await load();
      });
    }

    const label = (sel, icon, key) => ($(sel).innerHTML = `${icon}${span(t(key))}`);
    label('#addRsvpBtn', I.plus, 'a.addManual');
    label('#exportRsvpBtn', I.download, 'a.exportExcel');
    label('#printBtn', I.print, 'a.print');
    label('#sendQueueBtn', I.send, 'a.sendQueue');
    label('#addGuestBtn', I.plus, 'a.addGuest');
    label('#importBtn', I.upload, 'a.import');
    label('#exportGuestsBtn', I.download, 'a.export');
    label('#exportEventsBtn', I.download, 'a.export');
    label('#copyLinkBtn', I.link, 'a.copy');
    label('#qrDownload', I.download, 'a.qrDownload');

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

  function tile(label, value, sub, dot) {
    return `<div class="tile"><span class="t-label">${dot ? `<span class="t-dot" style="background:var(${dot})"></span>` : ''}${esc(label)}</span><span class="t-value">${value}</span><span class="t-sub">${sub ? esc(sub) : '&nbsp;'}</span></div>`;
  }

  function renderOverview() {
    const m = M;
    const yesN = m.by.yes.length;
    const responses = m.rsvps.length;
    $('#heroNum').textContent = W.num(m.yesPeople);
    $('#heroUnit').textContent = t('a.pplWord', { n: m.yesPeople });
    const parts = [t('a.replies', { n: yesN })];
    // Replies through the general link can outnumber the guest list; a share above 100% would only confuse.
    if (m.invitedPeople && m.yesPeople <= m.invitedPeople) parts.push(t('a.heroOf', { n: m.invitedPeople, p: Math.round((m.yesPeople / m.invitedPeople) * 100) }));
    if (m.maybePeople) parts.push(t('a.heroMaybe', { n: m.maybePeople }));
    $('#heroSub').textContent = parts.join(' · ');
    const meter = $('#heroMeter');
    meter.hidden = !m.invitedPeople;
    if (m.invitedPeople) requestAnimationFrame(() => (meter.firstElementChild.style.width = Math.min(100, (m.yesPeople / m.invitedPeople) * 100) + '%'));

    const visitors = m.visitors.size;
    const opened = [...m.visitors.values()].filter((v) => v.types.has('open_invite')).length;
    const sentCount = m.guests.filter((g) => g.sent).length;
    $('#tiles').innerHTML = [
      tile(t('a.tReplies'), W.num(responses), m.guests.length ? t('a.tRepliesOf', { x: t('a.inv', { n: m.guests.length }) }) : t('a.tRepliesSub')),
      tile(t('a.tNo'), W.num(m.by.no.length), t('a.invWord', { n: m.by.no.length }), '--s-no'),
      tile(t('a.tMaybe'), W.num(m.maybePeople), `${t('a.pplWord', { n: m.maybePeople })} · ${t('a.inv', { n: m.by.maybe.length })}`, '--s-maybe'),
      tile(t('a.tPending'), m.guests.length ? W.num(m.pendingGuests.length) : '—', m.guests.length ? t('a.tPendingSub', { n: sentCount }) : t('a.tAddList')),
      tile(t('a.tVisitors'), W.num(visitors), t('a.tViews', { n: m.views })),
      tile(t('a.tOpened'), W.num(opened), visitors ? t('a.tOpenedSub', { p: Math.round((opened / visitors) * 100) }) : ''),
    ].join('');

    Charts.segmented(
      $('#segChart'),
      [
        { label: statusLabel('yes'), value: m.by.yes.length, color: '--s-yes', on: '--on-yes' },
        { label: statusLabel('maybe'), value: m.by.maybe.length, color: '--s-maybe', on: '--on-maybe' },
        { label: statusLabel('no'), value: m.by.no.length, color: '--s-no', on: '--on-no' },
        { label: t('a.s.pending'), value: m.pendingGuests.length, color: '--s-pending', on: '--on-pending' },
      ],
      { unit: (v) => t('a.invWord', { n: v }), empty: t('a.cRepliesEmpty') }
    );
    $('#segNote').textContent = m.guests.length ? t('a.cRepliesNote') : t('a.cRepliesNoList');

    const vs = [...m.visitors.values()];
    Charts.hbars(
      $('#funnelChart'),
      [
        { label: t('a.fVisited'), value: vs.length },
        { label: t('a.fStarted'), value: vs.filter((v) => v.types.has('rsvp_start') || v.types.has('rsvp')).length },
        { label: t('a.fSent'), value: vs.filter((v) => v.types.has('rsvp')).length },
      ],
      { base: vs.length, empty: t('a.noVisits') }
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
      for (let ts = first; ts <= end; ts += DAY) {
        const add = yes.filter((r) => startOfDay(r.created || r.updated) === ts).reduce((s, r) => s + r.count, 0);
        run += add;
        pts.push({ t: ts, v: run, delta: add });
      }
    }
    Charts.line($('#lineChart'), pts, { unit: (v) => t('a.pplWord', { n: v }), perDay: (n) => t('a.perDay', { n }), label: t('a.cLine'), empty: t('a.cLineEmpty') });

    const end = startOfDay(Date.now());
    const cols = [];
    for (let ts = end - 29 * DAY; ts <= end; ts += DAY) cols.push({ t: ts, v: 0, set: new Set() });
    m.events.forEach((e) => {
      if (e.type !== 'view') return;
      const c = cols[Math.round((startOfDay(e.t) - cols[0].t) / DAY)];
      if (c) c.set.add(e.visitor || e.t);
    });
    cols.forEach((c) => (c.v = c.set.size));
    Charts.columns($('#colChart'), cols, { unit: () => t('a.visitorsUnit'), label: t('a.cCols'), empty: t('a.noVisits') });

    const srcCount = {};
    vs.forEach((v) => (srcCount[v.src] = (srcCount[v.src] || 0) + 1));
    const srcRows = Object.entries(srcCount)
      .map(([k, v]) => ({ label: srcLabel(k), value: v }))
      .sort((a, b) => b.value - a.value);
    Charts.hbars($('#srcChart'), srcRows.slice(0, 6), { empty: t('a.noVisits') });
    const mobile = vs.filter((v) => v.device === 'mobile' || v.device === 'tablet').length;
    $('#deviceNote').textContent = vs.length ? t('a.mobileShare', { p: Math.round((mobile / vs.length) * 100) }) : '';

    // sides
    const sides = {};
    W.sides.forEach((s) => (sides[s] = { invited: 0, yes: 0 }));
    m.guests.forEach((g) => {
      const s = g.side || '';
      sides[s] = sides[s] || { invited: 0, yes: 0 };
      sides[s].invited += Number(g.invited) || 0;
      const r = m.rsvpFor.get(g.id);
      if (r && r.status === 'yes') sides[s].yes += r.count;
    });
    const sideRows = Object.entries(sides).filter(([, v]) => v.invited || v.yes);
    $('#sidesBox').innerHTML = sideRows.length
      ? `<div class="sides">${sideRows
          .map(([name, v]) => {
            const label = name ? W.sideLabel(name) : t('a.noSide');
            return `<div class="side-row"><div class="top"><b>${esc(label)}</b><span>${esc(t('a.sideOf', { a: W.num(v.yes), b: W.num(v.invited) }))}</span></div><div class="meter" role="img" aria-label="${esc(label)}: ${esc(t('a.sideOf', { a: v.yes, b: v.invited }))}"><span style="width:${v.invited ? Math.min(100, (v.yes / v.invited) * 100) : 0}%"></span></div></div>`;
          })
          .join('')}</div>`
      : `<div class="chart-empty">${esc(t('a.sidesEmpty'))}</div>`;

    const notes = m.rsvps.filter((r) => r.note).sort((a, b) => b.updated - a.updated).slice(0, 12);
    $('#notesBox').innerHTML = notes.length
      ? notes.map((r) => `<li dir="auto">${esc(r.note)}<small>${esc(r.name)} · ${esc(W.relTime(r.updated))}</small></li>`).join('')
      : `<li class="muted" style="background:none;padding:0">${esc(t('a.notesEmpty'))}</li>`;

    $('#recentFeed').innerHTML = feedHtml(m.events.slice(-8).reverse());
  }

  /* =========================================================== feed */

  function feedHtml(events) {
    if (!events.length) return `<li class="empty-li">${esc(t('a.feedEmpty'))}</li>`;
    return events
      .map((e) => {
        const g = e.guest && M.gById.get(e.guest);
        let text = t('ev.' + e.type);
        let icoClass = '';
        let icon = I.eye;
        let who = g ? g.name : '';
        if (e.type === 'rsvp') {
          const r = parseRsvpDetail(e.detail);
          who = who || r.name;
          text = r.status === 'yes' ? t('ev.rsvpYes', { x: t('a.ppl', { n: r.count }) }) : r.status === 'maybe' ? t('ev.rsvpMaybe') : t('ev.rsvpNo');
          icoClass = r.status === 'no' ? 'i-no' : 'i-rsvp';
          icon = STATUS_ICON[r.status] || I.check;
        } else if (e.type === 'share' || e.type === 'copy_link') {
          if (e.type === 'share') text = t('ev.shareVia', { x: srcLabel(e.detail) });
          icoClass = 'i-share';
          icon = I.share;
        } else if (e.type === 'open_invite' || e.type === 'download_pdf') {
          icoClass = 'i-open';
          icon = e.type === 'download_pdf' ? I.download : I.eye;
        } else if (e.type === 'nav') {
          text = t('ev.navVia', { x: e.detail === 'waze' ? 'Waze' : 'Google Maps' });
          icon = I.pin;
        } else if (e.type === 'calendar') {
          icon = I.calendar;
        } else if (e.type === 'rsvp_start') {
          icon = I.edit;
        }
        const meta = [who ? '' : t('a.generalLink'), srcLabel(e.src || ''), e.device === 'desktop' ? t('a.desktop') : e.device ? t('a.mobile') : ''].filter(Boolean).join(' · ');
        const when = new Date(e.t);
        return `<li><span class="f-ico ${icoClass}">${icon}</span><span class="f-main">${who ? `<b dir="auto">${esc(who)}</b> · ` : ''}${esc(text)}<span class="f-meta">${esc(meta)}</span></span><time datetime="${when.toISOString()}" title="${esc(W.shortDate(when) + ' ' + W.timeOf(when))}">${esc(W.relTime(e.t))}</time></li>`;
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
      if (b.dataset.act === 'del' && confirm(t('a.delRsvpQ', { name: r.name }))) act('deleteRsvp', { id: r.id }, t('a.deleted'));
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
        lang: f.dataset.lang || 'he',
      };
      if (!rsvp.name) return f.name.focus();
      $('#rsvpDialog').close();
      act('saveRsvp', { rsvp }, t('a.saved'));
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
    f.dataset.lang = r ? r.lang || 'he' : 'he';
    $('#rsvpDialogTitle').textContent = r ? t('a.dRsvpEdit') : t('a.dRsvpNew');
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
      ['all', t('a.all'), ''],
      ['yes', statusLabel('yes'), '--s-yes'],
      ['maybe', statusLabel('maybe'), '--s-maybe'],
      ['no', statusLabel('no'), '--s-no'],
    ];
    $('#rsvpChips').innerHTML = chipDefs
      .map(([k, l, c]) => `<button type="button" class="chip" data-f="${k}" aria-pressed="${ui.rsvpFilter === k}">${c ? `<span class="t-dot" style="background:var(${c})"></span>` : ''}${esc(l)} <span class="n">${counts[k]}</span></button>`)
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
      name: (a, b) => a.name.localeCompare(b.name, W.locale),
      count: (a, b) => b.count - a.count,
    };
    list.sort(sorters[ui.rsvpSort]);

    $('#rsvpTable tbody').innerHTML = list
      .map((r) => {
        const g = m.guestOfRsvp.get(r.id);
        const phone = r.phone || (g && g.phone) || '';
        const wa = phone ? `https://wa.me/${W.normalizePhone(phone)}` : '';
        return `<tr>
          <td class="name" dir="auto">${esc(r.name)}${r.lang === 'en' ? '<span class="lang-tag">EN</span>' : ''}${g && g.side ? ` <span class="src-tag">· ${esc(W.sideLabel(g.side))}</span>` : ''}</td>
          <td class="c-status"><span class="pill ${r.status}">${STATUS_ICON[r.status]}${esc(statusLabel(r.status))}</span></td>
          <td class="num" data-l="${esc(t('a.lGuests'))}">${r.status === 'no' ? '—' : W.num(r.count)}</td>
          <td class="phone" data-l="">${phone ? `<a href="tel:+${esc(W.normalizePhone(phone))}">${esc(W.displayPhone(phone))}</a>` : ''}</td>
          <td class="note-cell wide" dir="auto">${esc(r.note || '')}</td>
          <td data-l="${esc(t('a.lSrc'))}"><span class="src-tag">${esc(srcLabel(r.src || ''))}</span></td>
          <td class="dim" data-l="">${r.updated ? `<time title="${esc(W.shortDate(new Date(r.updated)))}">${esc(W.relTime(r.updated))}</time>${r.edits ? ' ' + esc(t('a.edited')) : ''}` : ''}</td>
          <td class="c-actions"><div class="row-actions">
            ${wa ? `<a class="icon-btn" href="${esc(wa)}" target="_blank" rel="noopener" title="WhatsApp" aria-label="${esc(t('a.waTo', { name: r.name }))}">${I.whatsapp}</a>` : ''}
            <button class="icon-btn" type="button" data-act="edit" data-id="${esc(r.id)}" title="${esc(t('a.edit'))}" aria-label="${esc(t('a.editX', { name: r.name }))}">${I.edit}</button>
            <button class="icon-btn" type="button" data-act="del" data-id="${esc(r.id)}" title="${esc(t('a.del'))}" aria-label="${esc(t('a.delX', { name: r.name }))}">${I.trash}</button>
          </div></td>
        </tr>`;
      })
      .join('');
    const emptyEl = $('#rsvpEmpty');
    emptyEl.hidden = list.length > 0;
    emptyEl.textContent = m.rsvps.length ? t('a.noResults') : t('a.rsvpEmpty');
    const people = list.filter((r) => r.status === 'yes').reduce((s, r) => s + r.count, 0);
    $('#rsvpTotal').textContent = list.length ? t('a.rsvpTotal', { n: list.length, p: people }) : '';
  }

  const fmtFull = (ts) => (ts ? `${W.shortDate(new Date(ts))} ${W.timeOf(new Date(ts))}` : '');
  const stamp = () => W.shortDate(new Date()).replace(/[/.]/g, '-');

  function exportRsvps() {
    const rows = [t('csv.rsvps')];
    M.rsvps
      .slice()
      .sort((a, b) => a.name.localeCompare(b.name, W.locale))
      .forEach((r) => {
        const g = M.guestOfRsvp.get(r.id);
        rows.push([r.name, statusLabel(r.status), r.status === 'no' ? 0 : r.count, W.displayPhone(r.phone), r.note, g ? W.sideLabel(g.side) : '', langLabel(r.lang), srcLabel(r.src || ''), fmtFull(r.created), fmtFull(r.updated)]);
      });
    W.download(`${t('a.fileRsvps')} ${stamp()}.csv`, W.csv(rows), 'text/csv;charset=utf-8');
  }

  /* =========================================================== guests */

  const eventDateText = (lang) => W.dateText(lang);
  const guestLang = (g) => (g.lang === 'en' ? 'en' : 'he');
  const personalLink = (g, src) => W.link({ g: g.id, src }, guestLang(g));
  const personalText = (g, link) => {
    const l = guestLang(g);
    return W.fill(W.L(cfg.personalShareText, l) || W.L(cfg.shareText, l), { name: g.name, link, couple: W.coupleTextFor(l), date: eventDateText(l) });
  };

  function sideOptions(withAll) {
    return (withAll ? `<option value="">${esc(t('a.allSides'))}</option>` : '<option value="">—</option>') + W.sides.map((s) => `<option value="${esc(s)}">${esc(W.sideLabel(s))}</option>`).join('');
  }

  function wireGuests() {
    $('#guestSearch').addEventListener('input', (e) => {
      ui.guestSearch = e.target.value.trim();
      renderGuests();
    });
    const sideSel = $('#sideFilter');
    sideSel.innerHTML = sideOptions(true);
    sideSel.addEventListener('change', (e) => {
      ui.guestSide = e.target.value;
      renderGuests();
    });
    $('#guestEditForm [name="side"]').innerHTML = sideOptions(false);
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
      if (a === 'del' && confirm(t('a.delGuestQ', { name: g.name }))) act('deleteGuest', { id: g.id }, t('a.deleted'));
      if (a === 'copy') {
        const link = personalLink(g, 'copy');
        (await W.copy(link)) ? W.toast(t('a.linkCopiedX', { name: g.name })) : prompt(t('a.linkPrompt'), link);
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
        lang: f.lang.value === 'en' ? 'en' : 'he',
      });
      if (!g.name) return f.name.focus();
      $('#guestDialog').close();
      act('saveGuests', { guests: [g] }, prev ? t('a.saved') : t('a.guestAdded'));
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
      act('saveGuests', { guests: list }, t('a.importedN', { n: list.length }));
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
    $('#guestDialogTitle').textContent = g ? t('a.dGuestEdit') : t('a.dGuestNew');
    f.lang.value = g ? guestLang(g) : W.lang;
    if (g) {
      f.name.value = g.name;
      f.phone.value = g.phone || '';
      f.side.value = g.side || '';
      f.invited.value = g.invited || 0;
      f.notes.value = g.notes || '';
    }
    $('#guestDialog').showModal();
  }

  // Accepts side names in either language and stores the Hebrew form.
  function matchSide(cell) {
    const c = cell.trim().toLowerCase();
    if (!c) return '';
    for (const l of ['he', 'en']) {
      const list = W.L(cfg.sides, l);
      const sides = Array.isArray(list) && list.length ? list : [...W.couple(l), t('c.both', null, l)];
      const i = sides.findIndex((s) => String(s).toLowerCase() === c);
      if (i >= 0) return W.sides[i];
    }
    return '';
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
        let lang = '';
        cells.forEach((c) => {
          const digits = c.replace(/\D/g, '');
          if (!phone && digits.length >= 9 && /^[\d\s()+\-]+$/.test(c)) phone = c;
          else if (!invited && /^\d{1,2}$/.test(c)) invited = parseInt(c, 10);
          else if (!lang && /^(en|eng|english|אנגלית)$/i.test(c)) lang = 'en';
          else if (!lang && /^(he|heb|hebrew|עברית)$/i.test(c)) lang = 'he';
          else if (!side && matchSide(c)) side = matchSide(c);
          else if (!name && c) name = c;
        });
        if (!name || (i === 0 && /^(שם|name)$/i.test(name))) return;
        const prev = (phone && existingByPhone.get(W.normalizePhone(phone))) || existingByName.get(name);
        out.push(
          Object.assign({}, prev || { id: W.uid(8), created: Date.now(), sent: 0, notes: '' }, {
            name,
            phone: phone || (prev && prev.phone) || '',
            invited: invited || (prev && prev.invited) || 2,
            side: side || (prev && prev.side) || '',
            lang: lang || (prev && prev.lang) || 'he',
          })
        );
      });
    return out;
  }
  function previewImport() {
    const list = parseImport($('#importText').value);
    const people = list.reduce((s, g) => s + g.invited, 0);
    const updates = list.filter((g) => M.gById.has(g.id)).length;
    $('#importPreview').textContent = list.length ? t('a.importPreview', { n: list.length, p: people }) + (updates ? t('a.importUpdates', { n: updates }) : '') : '';
    $('#importGo').disabled = !list.length;
  }
  function openImport() {
    $('#importText').value = '';
    $('#importFile').value = '';
    previewImport();
    $('#importDialog').showModal();
  }

  const guestStateGroup = (s) => (s === 'yes' || s === 'no' || s === 'maybe' ? 'answered' : s);

  function renderGuests() {
    const m = M;
    const states = m.guests.map((g) => guestStateGroup(m.guestState(g)));
    const count = (k) => states.filter((s) => s === k).length;
    const pipes = [
      ['all', m.guests.length],
      ['new', count('new')],
      ['sent', count('sent')],
      ['viewed', count('viewed')],
      ['answered', count('answered')],
    ];
    $('#pipeline').innerHTML = pipes.map(([k, n]) => `<button type="button" class="pipe" data-s="${k}" aria-pressed="${ui.guestState === k}"><b>${W.num(n)}</b><span>${esc(t('a.p.' + k))}</span></button>`).join('');
    $('#pipeline').querySelectorAll('.pipe').forEach((p) =>
      p.addEventListener('click', () => {
        ui.guestState = ui.guestState === p.dataset.s ? 'all' : p.dataset.s;
        renderGuests();
      })
    );

    const q = ui.guestSearch.toLowerCase();
    const list = m.guests.filter((g) => {
      if (ui.guestSide && g.side !== ui.guestSide) return false;
      if (ui.guestState !== 'all' && guestStateGroup(m.guestState(g)) !== ui.guestState) return false;
      if (q && !(g.name.toLowerCase().includes(q) || W.normalizePhone(g.phone).includes(W.normalizePhone(q)) || (g.phone || '').includes(q))) return false;
      return true;
    });
    list.sort((a, b) => a.name.localeCompare(b.name, W.locale));

    $('#guestTable tbody').innerHTML = list
      .map((g) => {
        const st = m.guestState(g);
        const r = m.rsvpFor.get(g.id);
        const stText = st === 'yes' && r ? t('a.comingN', { n: r.count }) : gstateLabel(st);
        const seen = m.lastSeen.get(g.id);
        const sub = st === 'sent' && g.sent ? t('a.sentAgo', { t: W.relTime(g.sent) }) : st === 'viewed' && seen ? t('a.seenAgo', { t: W.relTime(seen) }) : '';
        const has = !!g.phone;
        const u = (src) => W.shareUrls(personalText(g, personalLink(g, src)), personalLink(g, src), g.phone);
        return `<tr>
          <td class="name" dir="auto">${esc(g.name)}${guestLang(g) === 'en' ? '<span class="lang-tag">EN</span>' : ''}${g.notes ? `<div class="src-tag">${esc(g.notes)}</div>` : ''}</td>
          <td class="c-status"><span class="pill ${st}">${STATUS_ICON[st] || ''}${esc(stText)}</span>${sub ? `<div class="dim">${esc(sub)}</div>` : ''}</td>
          <td class="phone" data-l="">${has ? `<a href="tel:+${esc(W.normalizePhone(g.phone))}">${esc(W.displayPhone(g.phone))}</a>` : `<span class="dim">${esc(t('a.noPhone'))}</span>`}</td>
          <td data-l="${esc(t('a.lSide'))}">${esc(g.side ? W.sideLabel(g.side) : '')}</td>
          <td class="num" data-l="${esc(t('a.lInvited'))}">${W.num(g.invited)}</td>
          <td class="c-actions"><div class="row-actions">
            <div class="send-actions">
              <a class="send-btn wa" data-act="wa" data-id="${g.id}" href="${esc(u('wa').whatsapp)}" target="_blank" rel="noopener" title="${esc(t('a.sendWa'))}" aria-label="${esc(t('a.sendWaTo', { name: g.name }))}" ${has ? '' : 'aria-disabled="true"'}>${I.whatsapp}</a>
              <a class="send-btn sms" data-act="sms" data-id="${g.id}" href="${esc(u('sms').sms)}" title="${esc(t('a.sendSms'))}" aria-label="${esc(t('a.sendSmsTo', { name: g.name }))}" ${has ? '' : 'aria-disabled="true"'}>${I.sms}</a>
              <a class="send-btn tg" data-act="tg" data-id="${g.id}" href="${esc(u('tg').telegram)}" target="_blank" rel="noopener" title="${esc(t('a.sendTg'))}" aria-label="${esc(t('a.sendTgTo', { name: g.name }))}">${I.telegram}</a>
              <button class="send-btn copy" type="button" data-act="copy" data-id="${g.id}" title="${esc(t('a.copyLink'))}" aria-label="${esc(t('a.copyLinkTo', { name: g.name }))}">${I.link}</button>
            </div>
            <div class="send-actions">
              <button class="icon-btn" type="button" data-act="edit" data-id="${g.id}" title="${esc(t('a.edit'))}" aria-label="${esc(t('a.editX', { name: g.name }))}">${I.edit}</button>
              <button class="icon-btn" type="button" data-act="del" data-id="${g.id}" title="${esc(t('a.del'))}" aria-label="${esc(t('a.delX', { name: g.name }))}">${I.trash}</button>
            </div>
          </div></td>
        </tr>`;
      })
      .join('');

    const emptyEl = $('#guestEmpty');
    emptyEl.hidden = list.length > 0;
    if (!m.guests.length) {
      emptyEl.innerHTML = `<p>${esc(t('a.guestsEmpty'))}<br>${esc(t('a.guestsEmpty2'))}</p><button class="btn btn-primary" type="button" id="emptyImport">${I.upload}${span(t('a.importExcel'))}</button>`;
      $('#emptyImport').addEventListener('click', openImport);
    } else emptyEl.textContent = t('a.noMatch');

    const invited = list.reduce((s, g) => s + (Number(g.invited) || 0), 0);
    $('#guestTotal').textContent = list.length ? t('a.guestTotal', { a: t('a.inv', { n: list.length }), b: W.num(invited) }) : '';
    const queue = queueList();
    $('#sendQueueBtn').disabled = !queue.length;
    $('#sendQueueBtn').querySelector('span').textContent = queue.length ? t('a.sendQueueN', { n: queue.length }) : t('a.sendQueue');
  }

  function exportGuests() {
    const rows = [t('csv.guests')];
    M.guests
      .slice()
      .sort((a, b) => a.name.localeCompare(b.name, W.locale))
      .forEach((g) => {
        const r = M.rsvpFor.get(g.id);
        rows.push([g.name, W.displayPhone(g.phone), W.sideLabel(g.side), g.invited, langLabel(guestLang(g)), gstateLabel(M.guestState(g)), r && r.status === 'yes' ? r.count : 0, g.notes, fmtFull(g.sent), personalLink(g, '')]);
      });
    W.download(`${t('a.fileGuests')} ${stamp()}.csv`, W.csv(rows), 'text/csv;charset=utf-8');
  }

  /* ---------- send one after another ---------- */
  const queueList = () => (M ? M.guests.filter((g) => g.phone && !g.sent && !M.rsvpFor.has(g.id)).sort((a, b) => a.name.localeCompare(b.name, W.locale)) : []);
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
    $('#queueInfo').textContent = queue.length ? t('a.queueInfo', { n: queue.length }) : '';
    if (qi >= queue.length) {
      card.innerHTML = `<p class="q-name">${esc(t('a.queueDone'))}</p><p class="muted">${esc(t('a.queueDoneSub'))}</p>`;
      return;
    }
    const g = queue[qi];
    const text = personalText(g, personalLink(g, 'wa'));
    const urls = W.shareUrls(text, personalLink(g, 'wa'), g.phone);
    const smsUrls = W.shareUrls(personalText(g, personalLink(g, 'sms')), personalLink(g, 'sms'), g.phone);
    card.innerHTML = `
      <div class="queue-progress"><span style="width:${(qi / queue.length) * 100}%"></span></div>
      <span class="muted small">${esc(t('a.queueOf', { a: qi + 1, b: queue.length }))}</span>
      <span class="q-name" dir="auto">${esc(g.name)}${guestLang(g) === 'en' ? '<span class="lang-tag">EN</span>' : ''}</span>
      <span class="phone" dir="ltr" style="justify-self:end">${esc(W.displayPhone(g.phone))}</span>
      <p class="q-msg" dir="auto">${esc(text)}</p>
      <div class="q-actions">
        <a class="btn wa" id="qWa" href="${esc(urls.whatsapp)}" target="_blank" rel="noopener">${I.whatsapp}${span(t('a.sendWa'))}</a>
        <a class="btn sms" id="qSms" href="${esc(smsUrls.sms)}">${I.sms}<span>SMS</span></a>
        <button class="btn btn-ghost" type="button" id="qSkip">${esc(t('a.skip'))}</button>
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

  const msgKey = (l) => 'wedding-share-msg-' + l;
  const shareMessage = (l) => W.store.get(msgKey(l), '') || W.L(cfg.shareText, l) || '';

  function wireShare() {
    const msg = $('#shareMsg');
    const setLang = (l) => {
      ui.shareLang = l;
      document.querySelectorAll('input[name="shareLang"]').forEach((i) => (i.checked = i.value === l));
      msg.value = shareMessage(l);
      msg.dir = l === 'en' ? 'ltr' : 'rtl';
      $('#waBubble').dir = l === 'en' ? 'ltr' : 'rtl';
    };
    document.querySelectorAll('input[name="shareLang"]').forEach((i) =>
      i.addEventListener('change', () => {
        setLang(i.value);
        renderShare();
      })
    );
    setLang(ui.shareLang);
    msg.addEventListener('input', () => {
      W.store.set(msgKey(ui.shareLang), msg.value);
      renderShare();
    });
    $('#resetMsgBtn').addEventListener('click', () => {
      W.store.del(msgKey(ui.shareLang));
      msg.value = shareMessage(ui.shareLang);
      renderShare();
    });
    $('#copyLinkBtn').addEventListener('click', async () => {
      const link = W.link({ src: 'copy' }, ui.shareLang);
      (await W.copy(link)) ? W.toast(t('g.copied')) : prompt(t('a.linkPrompt'), link);
    });
    $('#qrDownload').addEventListener('click', downloadQr);
  }

  function fillShare(src) {
    const l = ui.shareLang;
    const link = W.link({ src }, l);
    return { link, text: W.fill($('#shareMsg').value, { couple: W.coupleTextFor(l), link, date: eventDateText(l), name: '' }) };
  }

  function renderShare() {
    const l = ui.shareLang;
    $('#shareLink').textContent = W.link({}, l);
    const btns = $('#shareBtns');
    const defs = [
      ['wa', t('g.wa'), I.whatsapp, 'b-wa'],
      ['tg', t('g.tg'), I.telegram, 'b-tg'],
      ['sms', 'SMS', I.sms, 'b-sms'],
    ];
    if (navigator.share) defs.push(['share', t('g.more'), I.share, 'b-more']);
    btns.innerHTML = defs.map(([k, lbl, ic, c]) => `<button type="button" class="btn ${c}" data-ch="${k}">${ic}${span(lbl)}</button>`).join('');
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

    // WhatsApp-style preview, in the language being shared
    const IP = window.INVITATION_PAGES || {};
    const set = (IP[l] && IP[l].og ? IP[l] : IP.he) || {};
    $('#ogImg').src = set.og || 'invitation/og.jpg';
    $('#ogTitle').textContent = t('g.ogTitle', { couple: W.coupleTextFor(l) }, l);
    const venue = W.L((cfg.venue || {}).name, l);
    $('#ogDesc').textContent = [W.L(cfg.dateLine, l) || (W.eventDate ? W.longDate(W.eventDate, l) : ''), venue].filter(Boolean).join(' · ');
    try {
      $('#ogHost').textContent = new URL(W.siteUrl).host;
    } catch (e) {}
    $('#waText').textContent = fillShare('wa').text;

    // QR
    const box = $('#qrBox');
    const link = W.link({ src: 'qr' }, l);
    if (box.dataset.link !== link && window.qrcode) {
      const qr = window.qrcode(0, 'M');
      qr.addData(link);
      qr.make();
      box.innerHTML = qr.createSvgTag({ cellSize: 6, margin: 2, scalable: true });
      box.querySelector('svg').setAttribute('aria-label', t('a.qrAria'));
      box.querySelector('svg').setAttribute('role', 'img');
      box.dataset.link = link;
    }
  }

  function downloadQr() {
    if (!window.qrcode) return;
    const qr = window.qrcode(0, 'M');
    qr.addData(W.link({ src: 'qr' }, ui.shareLang));
    qr.make();
    const n = qr.getModuleCount();
    const margin = 4;
    const cell = Math.floor(1200 / (n + margin * 2));
    const c = document.createElement('canvas');
    c.width = c.height = cell * (n + margin * 2);
    const ctx = c.getContext('2d');
    ctx.fillStyle = '#fff';
    ctx.fillRect(0, 0, c.width, c.height);
    ctx.fillStyle = '#221f36';
    for (let r = 0; r < n; r++) for (let col = 0; col < n; col++) if (qr.isDark(r, col)) ctx.fillRect((col + margin) * cell, (r + margin) * cell, cell, cell);
    c.toBlob((blob) => W.download(`qr-${ui.shareLang}.png`, blob));
  }

  /* =========================================================== activity */

  function wireActivity() {
    const sel = $('#eventFilter');
    sel.innerHTML = `<option value="">${esc(t('a.allEvents'))}</option>${EVENT_TYPES.map((k) => `<option value="${k}">${esc(t('ev.' + k))}</option>`).join('')}`;
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
      const rows = [t('csv.events')];
      M.events.forEach((e) => {
        const g = e.guest && M.gById.get(e.guest);
        rows.push([fmtFull(e.t), t('ev.' + e.type), g ? g.name : '', e.detail, srcLabel(e.src || ''), e.device, e.visitor]);
      });
      W.download(`${t('a.fileEvents')} ${stamp()}.csv`, W.csv(rows), 'text/csv;charset=utf-8');
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
      if (e.code === 'bad_password') return 'bad';
      if (e.code === 'password_not_set') return t('a.pwNotSet');
      if (e.code === 'unreadable') return 'blocked';
      return t('a.noServer');
    }
  }

  // The reply host is blocked on this connection: say so plainly and offer the sheet itself.
  function showBlocked() {
    showLogin('');
    const box = $('#loginError');
    box.hidden = false;
    box.innerHTML = `${esc(t('a.blocked'))}${cfg.sheetUrl ? ` <a href="${esc(cfg.sheetUrl)}" target="_blank" rel="noopener">${esc(t('a.openSheet'))}</a>` : ''}`;
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
      btn.classList.add('loading');
      const err = await tryLogin($('#password').value);
      btn.disabled = false;
      btn.classList.remove('loading');
      if (err === 'blocked') showBlocked();
      else if (err) showLogin(err === 'bad' ? t('a.badPw') : err);
    });
    if (api.mode === 'demo') {
      showApp();
      load();
    } else if (password) {
      tryLogin(password).then((err) => (err === 'blocked' ? showBlocked() : err && showLogin(err === 'bad' ? '' : err)));
    } else showLogin('');
  }

  start();
})();
