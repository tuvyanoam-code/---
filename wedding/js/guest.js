/* Guest invitation page */
(function () {
  'use strict';
  const W = window.W;
  const api = window.API;
  const cfg = W.cfg;
  const I = W.icon;
  const $ = (s) => document.querySelector(s);

  /* ---------- who is visiting ---------- */
  const params = new URLSearchParams(location.search);
  const guestId = (params.get('g') || '').replace(/[^\w-]/g, '').slice(0, 64);
  const src = (params.get('src') || '').replace(/[^\w-]/g, '').slice(0, 20);
  let visitor = W.store.get('wedding-visitor', '');
  if (!visitor) {
    visitor = W.uid(10);
    W.store.set('wedding-visitor', visitor);
  }
  api.context = { visitor, guest: guestId, src, device: W.device() };
  let ref = '';
  try {
    ref = document.referrer ? new URL(document.referrer).hostname : '';
  } catch (e) {}
  api.track('view', ref);

  const rsvpKey = 'wedding-rsvp:' + (guestId || 'general');
  let saved = W.store.get(rsvpKey, null);
  const rsvpId = guestId ? 'g:' + guestId : (saved && saved.id) || W.uid(12);
  const rsvpCfg = Object.assign({ maxGuests: 10, askPhone: true, askNote: true }, cfg.rsvp || {});

  /* ---------- hero ---------- */
  document.querySelectorAll('.names .name').forEach((el) => {
    el.textContent = W.couple()[+el.dataset.i] || '';
  });
  $('#names').setAttribute('aria-label', W.coupleText);
  $('#sign').textContent = W.coupleText;
  const d = W.eventDate;
  if (d) {
    $('#date').textContent = `${W.longDate(d)} · ${W.timeOf(d)}`;
    $('#hdate').textContent = W.hebrewDate(d);
    const left = W.daysUntil(d);
    const cd = $('#countdown');
    if (left > 1) {
      cd.innerHTML = W.t('g.daysLeft', { n: `<b>${W.num(left)}</b>` });
      cd.hidden = false;
    } else if (left === 1) {
      cd.innerHTML = `<b>${W.esc(W.t('g.tomorrow'))}</b>`;
      cd.hidden = false;
    } else if (left === 0) {
      cd.innerHTML = `<b>${W.esc(W.t('g.today'))}</b>`;
      cd.hidden = false;
    }
  }

  if (guestId) {
    api.guest(guestId).then((g) => {
      if (!g || !g.name) return;
      const el = $('#greeting');
      el.textContent = W.t('g.greeting', { name: g.name });
      el.hidden = false;
      const name = $('#name');
      if (!name.value) name.value = g.name;
      if (!saved && g.invited > 0) setCount(g.invited);
    });
  }

  /* ---------- invitation preview + viewer ---------- */
  // Each language may have its own PDF; English falls back to the Hebrew one.
  const IP = window.INVITATION_PAGES || {};
  const pagesSet = (IP[W.lang] && IP[W.lang].pages && IP[W.lang].pages.length ? IP[W.lang] : IP.he) || {};
  const pdfUrl = pagesSet.pdf || W.L(cfg.invitationPdf) || 'invitation.pdf';
  const pdfName = W.t('g.pdfName', { couple: W.coupleText });
  const pages = pagesSet.pages || [];
  $('#invitePreview').alt = W.t('g.inviteAlt');
  const preview = $('#invitePreview');
  const placeholder = $('#invitePlaceholder');

  $('#openViewer').innerHTML = `${I.eye}<span>${W.esc(W.t('g.view'))}</span>`;
  const dl = $('#downloadPdf');
  dl.innerHTML = `${I.download}<span>${W.esc(W.t('g.download'))}</span>`;
  [dl, $('#vDownload')].forEach((a) => {
    a.href = pdfUrl;
    a.setAttribute('download', pdfName);
    a.addEventListener('click', () => api.track('download_pdf'));
  });
  $('#vDownload').innerHTML = I.download;
  $('#vClose').innerHTML = I.close;
  $('#vZoomIn').innerHTML = I.zoomIn;
  $('#vZoomOut').innerHTML = I.zoomOut;
  $('#vShare').innerHTML = I.share;

  if (pages.length) {
    const p = pages[0];
    preview.width = p.w;
    preview.height = p.h;
    preview.src = p.src;
    if (p.src2x) preview.srcset = `${p.src} 1x, ${p.src2x} 2x`;
    preview.hidden = true;
    const show = () => {
      preview.hidden = false;
      placeholder.hidden = true;
    };
    if (preview.complete && preview.naturalWidth) show();
    preview.addEventListener('load', show);
    preview.addEventListener('error', () => previewFailed());
  } else {
    preview.hidden = true;
    previewFailed();
  }
  function previewFailed() {
    placeholder.classList.add('failed');
    placeholder.lastElementChild.textContent = W.t('g.tapOpen');
  }

  const viewer = $('#viewer');
  const wrap = $('#vWrap');
  const scroller = $('#vScroll');
  const zoomLevels = [1, 1.6, 2.4];
  let zoomIdx = 0;
  let built = false;
  let lastFocus = null;

  function buildViewer() {
    if (built) return;
    built = true;
    if (pages.length) {
      pages.forEach((p, i) => {
        const img = document.createElement('img');
        img.width = p.w;
        img.height = p.h;
        img.alt = W.t('g.page', { n: i + 1 });
        img.decoding = 'async';
        img.loading = i === 0 ? 'eager' : 'lazy';
        img.src = p.src2x || p.src;
        img.dataset.page = i + 1;
        wrap.appendChild(img);
      });
      const io = new IntersectionObserver(
        (entries) => {
          entries.forEach((e) => {
            if (e.isIntersecting) $('#vPages').textContent = pages.length > 1 ? `${e.target.dataset.page} / ${pages.length}` : '';
          });
        },
        { root: scroller, threshold: 0.5 }
      );
      wrap.querySelectorAll('img').forEach((img) => io.observe(img));
    } else {
      // No pre-rendered pages (local preview without build): let the browser show the PDF.
      const frame = document.createElement('iframe');
      frame.className = 'viewer-frame';
      frame.src = pdfUrl;
      frame.title = W.t('g.inviteTitle');
      wrap.appendChild(frame);
      $('#vZoomIn').hidden = $('#vZoomOut').hidden = true;
    }
  }

  function setZoom(i, focusPoint) {
    const prev = zoomLevels[zoomIdx];
    zoomIdx = Math.max(0, Math.min(zoomLevels.length - 1, i));
    const z = zoomLevels[zoomIdx];
    // Keep the point under the finger (or the centre) where it was.
    const fx = focusPoint ? focusPoint.x : scroller.clientWidth / 2;
    const fy = focusPoint ? focusPoint.y : scroller.clientHeight / 2;
    const cx = (scroller.scrollLeft + fx) / prev;
    const cy = (scroller.scrollTop + fy) / prev;
    wrap.style.setProperty('--zoom', z);
    scroller.scrollLeft = cx * z - fx;
    scroller.scrollTop = cy * z - fy;
    $('#vZoomOut').disabled = zoomIdx === 0;
    $('#vZoomIn').disabled = zoomIdx === zoomLevels.length - 1;
  }

  function openViewer() {
    buildViewer();
    lastFocus = document.activeElement;
    viewer.hidden = false;
    document.body.style.overflow = 'hidden';
    setZoom(0);
    scroller.scrollTop = 0;
    $('#vClose').focus();
    history.pushState({ viewer: true }, '');
    api.track('open_invite');
  }
  function closeViewer(fromPop) {
    if (viewer.hidden) return;
    viewer.hidden = true;
    document.body.style.overflow = '';
    if (lastFocus) lastFocus.focus();
    if (!fromPop && history.state && history.state.viewer) history.back();
  }
  $('#inviteCard').addEventListener('click', openViewer);
  $('#openViewer').addEventListener('click', openViewer);
  $('#vClose').addEventListener('click', () => closeViewer(false));
  $('#vZoomIn').addEventListener('click', () => setZoom(zoomIdx + 1));
  $('#vZoomOut').addEventListener('click', () => setZoom(zoomIdx - 1));
  $('#vShare').addEventListener('click', () => shareTo('more'));
  window.addEventListener('popstate', () => closeViewer(true));
  document.addEventListener('keydown', (e) => {
    if (viewer.hidden) return;
    if (e.key === 'Escape') closeViewer(false);
    if (e.key === '+' || e.key === '=') setZoom(zoomIdx + 1);
    if (e.key === '-') setZoom(zoomIdx - 1);
  });
  // Double tap / double click toggles zoom around the tapped point.
  let lastTap = 0;
  wrap.addEventListener('click', (e) => {
    const now = Date.now();
    if (now - lastTap < 320 || e.detail === 2) {
      const r = scroller.getBoundingClientRect();
      setZoom(zoomIdx === 0 ? 1 : 0, { x: e.clientX - r.left, y: e.clientY - r.top });
      lastTap = 0;
    } else lastTap = now;
  });

  /* ---------- floating RSVP button ---------- */
  const floatCta = $('#floatCta');
  const vis = { hero: true, rsvp: false };
  function updateFloat() {
    const hide = vis.hero || vis.rsvp || !!saved;
    floatCta.hidden = false;
    floatCta.classList.toggle('away', hide);
  }
  if ('IntersectionObserver' in window) {
    const io = new IntersectionObserver((entries) => {
      entries.forEach((e) => {
        vis[e.target === $('#heroCta') ? 'hero' : 'rsvp'] = e.isIntersecting;
      });
      updateFloat();
    });
    io.observe($('#heroCta'));
    io.observe($('#rsvp'));
  }

  /* ---------- RSVP ---------- */
  const form = $('#rsvpForm');
  const fields = $('#rsvpFields');
  const countEl = $('#count');
  let count = 2;

  if (rsvpCfg.deadline) {
    const dd = W.parseLocal(rsvpCfg.deadline);
    if (dd) $('#rsvpSub').textContent = W.t('g.deadline', { date: W.fmtDate(dd, { day: 'numeric', month: 'long' }) });
  } else $('#rsvpSub').textContent = W.t('g.noDeadline');

  form.querySelector('.c-yes .choice-ico').innerHTML = I.check;
  form.querySelector('.c-maybe .choice-ico').innerHTML = I.maybe;
  form.querySelector('.c-no .choice-ico').innerHTML = I.x;
  form.querySelector('[data-step="1"]').innerHTML = I.plus;
  form.querySelector('[data-step="-1"]').innerHTML = I.minus;
  if (!rsvpCfg.askPhone) $('#phoneField').hidden = true;
  if (!rsvpCfg.askNote) $('#noteField').hidden = true;

  function setCount(n, animate) {
    count = Math.max(1, Math.min(rsvpCfg.maxGuests || 10, n));
    countEl.textContent = count;
    $('#countWords').textContent = count === 1 ? W.t('g.justMe') : W.t('g.mePlus', { n: count - 1 });
    form.querySelector('[data-step="-1"]').disabled = count <= 1;
    form.querySelector('[data-step="1"]').disabled = count >= (rsvpCfg.maxGuests || 10);
    if (animate) {
      countEl.classList.remove('bump');
      void countEl.offsetWidth;
      countEl.classList.add('bump');
    }
  }
  setCount(2);
  form.querySelectorAll('.step').forEach((b) => b.addEventListener('click', () => setCount(count + +b.dataset.step, true)));

  function status() {
    const r = form.querySelector('input[name="status"]:checked');
    return r ? r.value : '';
  }
  let started = false;
  function syncStatus() {
    const s = status();
    fields.classList.toggle('collapsed', !s);
    $('#countField').hidden = s === 'no';
    $('#submitBtn').textContent = W.t(s === 'no' ? 'g.submitNo' : s === 'maybe' ? 'g.submitMaybe' : 'g.submitYes');
    if (s && !started) {
      started = true;
      api.track('rsvp_start', s);
    }
  }
  form.addEventListener('change', (e) => {
    if (e.target.name === 'status') syncStatus();
  });

  function fillForm(r) {
    form.querySelectorAll('input[name="status"]').forEach((i) => (i.checked = i.value === r.status));
    $('#name').value = r.name || '';
    $('#phone').value = r.phone || '';
    $('#note').value = r.note || '';
    if (r.count) setCount(r.count);
    syncStatus();
  }

  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    const err = $('#formError');
    err.hidden = true;
    const s = status();
    const nameEl = $('#name');
    const name = nameEl.value.trim();
    if (!s) return;
    if (name.length < 2) {
      nameEl.classList.remove('invalid');
      void nameEl.offsetWidth;
      nameEl.classList.add('invalid');
      nameEl.focus();
      err.textContent = W.t('g.errName');
      err.hidden = false;
      return;
    }
    nameEl.classList.remove('invalid');
    const phone = $('#phone').value.trim();
    if (phone && phone.replace(/\D/g, '').length < 9) {
      $('#phone').classList.add('invalid');
      err.textContent = W.t('g.errPhone');
      err.hidden = false;
      return;
    }
    $('#phone').classList.remove('invalid');
    const btn = $('#submitBtn');
    btn.classList.add('loading');
    const data = {
      id: rsvpId,
      name,
      status: s,
      count: s === 'no' ? 0 : count,
      phone,
      note: $('#note').value.trim(),
      guest: guestId,
      src,
      device: W.device(),
      lang: W.lang,
    };
    try {
      await api.rsvp(data);
      saved = Object.assign({ t: Date.now() }, data);
      W.store.set(rsvpKey, saved);
      api.track('rsvp', `${s}:${data.count}|${name}`);
      showThanks(true);
    } catch (ex) {
      err.textContent = W.t('g.errSend');
      err.hidden = false;
    } finally {
      btn.classList.remove('loading');
    }
  });

  function showThanks(celebrate) {
    const r = saved;
    const badge = $('#thanksBadge');
    badge.className = 'thanks-badge b-' + r.status;
    badge.innerHTML = r.status === 'yes' ? I.check : r.status === 'maybe' ? I.maybe : I.heart;
    const first = (r.name || '').split(' ')[0];
    if (r.status === 'yes') {
      $('#thanksTitle').textContent = first ? W.t('g.thxYesName', { name: first }) : W.t('g.thxYes');
      $('#thanksText').textContent = r.count === 1 ? W.t('g.thxYesOne') : W.t('g.thxYesMany', { n: r.count });
    } else if (r.status === 'maybe') {
      $('#thanksTitle').textContent = W.t('g.thxMaybeT');
      $('#thanksText').textContent = W.t('g.thxMaybe');
    } else {
      $('#thanksTitle').textContent = W.t('g.thxNoT');
      $('#thanksText').textContent = W.t('g.thxNo');
    }
    $('#thanksCalendar').hidden = r.status === 'no';
    form.hidden = true;
    $('#thanks').hidden = false;
    updateFloat();
    if (celebrate) {
      $('#thanks').focus({ preventScroll: true });
      if (r.status === 'yes') confetti();
    }
  }

  $('#editRsvp').innerHTML = `${I.edit}<span>${W.esc(W.t('g.edit'))}</span>`;
  $('#editRsvp').addEventListener('click', () => {
    fillForm(saved);
    $('#thanks').hidden = true;
    form.hidden = false;
    form.querySelector('input[name="status"]:checked').focus();
  });

  if (saved) {
    fillForm(saved);
    showThanks(false);
  } else syncStatus();

  /* ---------- where & when ---------- */
  if (d) $('#dDate').textContent = W.longDate(d);
  const sched = $('#schedule');
  (cfg.schedule || []).forEach((s) => {
    const li = document.createElement('li');
    const t = document.createElement('time');
    t.textContent = s.time;
    const span = document.createElement('span');
    span.textContent = W.L(s.label);
    li.append(t, span);
    sched.appendChild(li);
  });
  const v = cfg.venue || {};
  $('#dVenue').textContent = W.L(v.name);
  $('#dAddress').textContent = W.L(v.address);
  document.querySelectorAll('.detail-card .detail-ico')[0].innerHTML = I.calendar;
  document.querySelectorAll('.detail-card .detail-ico')[1].innerHTML = I.pin;
  const maps = W.mapsLinks();
  const waze = $('#waze');
  waze.href = maps.waze;
  waze.innerHTML = `${I.waze}<span>Waze</span>`;
  waze.addEventListener('click', () => api.track('nav', 'waze'));
  const gm = $('#maps');
  gm.href = maps.maps;
  gm.innerHTML = `${I.pin}<span>Google Maps</span>`;
  gm.addEventListener('click', () => api.track('nav', 'maps'));

  const cal = W.calendar();
  if (cal) {
    const box = $('#calActions');
    const g = document.createElement('a');
    g.className = 'btn btn-sm';
    g.href = cal.google;
    g.target = '_blank';
    g.rel = 'noopener';
    g.innerHTML = `${I.calendar}<span>${W.esc(W.t('g.gcal'))}</span>`;
    g.addEventListener('click', () => api.track('calendar', 'google'));
    const ics = document.createElement('button');
    ics.type = 'button';
    ics.className = 'btn btn-sm';
    ics.innerHTML = `${I.calendar}<span>${W.esc(W.t('g.ical'))}</span>`;
    ics.addEventListener('click', () => {
      W.download('wedding.ics', cal.ics, 'text/calendar;charset=utf-8');
      api.track('calendar', 'ics');
    });
    box.append(g, ics);
    const tc = $('#thanksCalendar');
    tc.href = cal.google;
    tc.innerHTML = `${I.calendar}<span>${W.esc(W.t('g.addCal'))}</span>`;
    tc.addEventListener('click', () => api.track('calendar', 'google'));
  } else $('#thanksCalendar').hidden = true;

  /* ---------- share ---------- */
  const dateText = d ? `${W.fmtDate(d, { weekday: 'long', day: 'numeric', month: 'long' })}` : '';
  function shareText(link) {
    return W.fill(W.L(cfg.shareText), { couple: W.coupleText, link, date: dateText, name: '' });
  }
  // channel: wa | tg | sms | copy | more — the same codes the dashboard groups by
  async function shareTo(channel) {
    const link = W.link({ src: channel === 'more' ? 'share' : channel });
    const text = shareText(link);
    const urls = W.shareUrls(text, link);
    const target = { wa: urls.whatsapp, tg: urls.telegram, sms: urls.sms }[channel];
    api.track(channel === 'copy' ? 'copy_link' : 'share', channel);
    if (channel === 'copy') {
      (await W.copy(link)) ? W.toast(W.t('g.copied')) : W.toast(link);
      return;
    }
    if (channel === 'more') {
      if (navigator.share) {
        try {
          await navigator.share({ title: W.t('g.ogTitle', { couple: W.coupleText }), text: text.replace(link, '').trim(), url: link });
        } catch (e) {}
      } else {
        (await W.copy(link)) && W.toast(W.t('g.copied'));
      }
      return;
    }
    if (channel === 'sms') location.href = target;
    else window.open(target, '_blank', 'noopener');
  }
  const shareDefs = [
    ['wa', W.t('g.wa'), I.whatsapp, 'd-wa'],
    ['tg', W.t('g.tg'), I.telegram, 'd-tg'],
    ['sms', W.t('g.sms'), I.sms, 'd-sms'],
    ['copy', W.t('g.copy'), I.link, 'd-copy'],
  ];
  if (navigator.share) shareDefs.push(['more', W.t('g.more'), I.share, 'd-more']);
  const row = $('#shareRow');
  shareDefs.forEach(([ch, label, icon, cls]) => {
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'share-btn';
    b.innerHTML = `<span class="disc ${cls}">${icon}</span><span>${W.esc(label)}</span>`;
    b.addEventListener('click', () => shareTo(ch));
    row.appendChild(b);
  });

  /* ---------- ticket notch follows the perforation line ---------- */
  function placeNotch() {
    const head = document.querySelector('.rsvp-head');
    document.querySelector('.rsvp-card').style.setProperty('--notch', head.offsetHeight - 13 + 'px');
  }
  placeNotch();
  window.addEventListener('resize', placeNotch);
  if (document.fonts) document.fonts.ready.then(placeNotch);

  /* ---------- confetti ---------- */
  function confetti() {
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;
    const c = $('#confetti');
    const ctx = c.getContext('2d');
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    c.width = innerWidth * dpr;
    c.height = innerHeight * dpr;
    ctx.scale(dpr, dpr);
    const colors = ['#d6336c', '#ffc23d', '#2fbf9b', '#5b8def', '#a78bfa', '#ff8a65'];
    const parts = Array.from({ length: 170 }, (_, i) => ({
      x: innerWidth / 2 + (Math.random() - 0.5) * 80,
      y: innerHeight * 0.55,
      vx: (Math.random() - 0.5) * 16,
      vy: -10 - Math.random() * 12,
      w: 6 + Math.random() * 7,
      h: 8 + Math.random() * 10,
      r: Math.random() * Math.PI,
      vr: (Math.random() - 0.5) * 0.4,
      color: colors[i % colors.length],
      round: Math.random() < 0.3,
    }));
    const start = performance.now();
    function frame(now) {
      const t = now - start;
      ctx.clearRect(0, 0, innerWidth, innerHeight);
      parts.forEach((p) => {
        p.vy += 0.38;
        p.vx *= 0.985;
        p.x += p.vx;
        p.y += p.vy;
        p.r += p.vr;
        ctx.save();
        ctx.globalAlpha = Math.max(0, 1 - t / 3200);
        ctx.translate(p.x, p.y);
        ctx.rotate(p.r);
        ctx.fillStyle = p.color;
        if (p.round) {
          ctx.beginPath();
          ctx.arc(0, 0, p.w / 2, 0, Math.PI * 2);
          ctx.fill();
        } else ctx.fillRect(-p.w / 2, -p.h / 2, p.w, p.h * Math.abs(Math.cos(p.r * 2)) + 2);
        ctx.restore();
      });
      if (t < 3200) requestAnimationFrame(frame);
      else ctx.clearRect(0, 0, innerWidth, innerHeight);
    }
    requestAnimationFrame(frame);
  }
})();
