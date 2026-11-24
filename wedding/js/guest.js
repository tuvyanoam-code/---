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

  /* ---------- title ---------- */
  $('#pageTitle').textContent = W.t('g.ogTitle', { couple: W.coupleText });

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

  /* ---------- the invitation as a small book: drag to turn the page, tap to zoom ---------- */
  // Hebrew books turn right-to-left (spine on the right), English left-to-right (spine on the left).
  const bookEl = $('#book');
  const rtl = W.rtl;
  let current = 0;
  let turnedOnce = false;
  const leaves = [];

  function buildBook() {
    if (!pages.length) {
      const b = document.createElement('button');
      b.type = 'button';
      b.className = 'btn btn-primary';
      b.innerHTML = `${I.eye}<span>${W.esc(W.t('g.tapOpen'))}</span>`;
      b.addEventListener('click', () => openViewer(0));
      bookEl.replaceWith(b);
      return;
    }
    bookEl.style.aspectRatio = `${pages[0].w} / ${pages[0].h}`;
    bookEl.classList.toggle('rtl', rtl);
    bookEl.setAttribute('aria-label', W.t('g.book', { n: pages.length }));
    pages.forEach((p, i) => {
      const leaf = document.createElement('div');
      leaf.className = 'leaf';
      const front = document.createElement('div');
      front.className = 'face front';
      const img = document.createElement('img');
      img.width = p.w;
      img.height = p.h;
      img.alt = i === 0 ? W.t('g.inviteAlt') : W.t('g.page', { n: i + 1 });
      img.decoding = 'async';
      img.loading = i < 2 ? 'eager' : 'lazy';
      if (i === 0) img.fetchPriority = 'high';
      img.draggable = false;
      img.src = p.src;
      if (p.src2x) img.srcset = `${p.src} 1x, ${p.src2x} 2x`;
      front.appendChild(img);
      const back = document.createElement('div');
      back.className = 'face back';
      leaf.append(front, back);
      bookEl.appendChild(leaf);
      leaves.push(leaf);
    });
    if (pages.length > 1) {
      $('#bookNav').hidden = false;
      $('#prevPage').innerHTML = I.chevronLeft;
      $('#nextPage').innerHTML = I.chevronRight;
      $('#prevPage').setAttribute('aria-label', W.t('g.prevPage'));
      $('#nextPage').setAttribute('aria-label', W.t('g.nextPage'));
      $('#dots').innerHTML = pages.map(() => '<i></i>').join('');
      $('#prevPage').addEventListener('click', () => turnTo(current - 1));
      $('#nextPage').addEventListener('click', () => turnTo(current + 1));
    }
    $('#bookHint').textContent = W.t(pages.length > 1 ? 'g.hintFlip' : 'g.hintZoom');
    layout();
    // A gentle peek of the first page's edge, so it is clear the invitation can be turned.
    if (pages.length > 1 && !matchMedia('(prefers-reduced-motion: reduce)').matches) {
      setTimeout(() => {
        if (turnedOnce) return;
        setAngle(leaves[0], dirSign() * 26, true);
        setTimeout(() => !turnedOnce && setAngle(leaves[0], 0, true), 650);
      }, 1400);
    }
  }

  const dirSign = () => (rtl ? 1 : -1); // forward rotation: +180° around the right spine, -180° around the left

  function setAngle(leaf, deg, animate) {
    leaf.style.transition = animate ? '' : 'none';
    leaf.style.transform = `rotateY(${deg}deg)`;
    leaf.style.setProperty('--shade', (Math.abs(Math.sin((deg * Math.PI) / 180)) * 0.35).toFixed(3));
  }

  // Pages before `current` are turned over; the rest lie flat, the next one under the current.
  // A page being turned stays on top until its turn has played.
  let lifted = null;
  let liftTimer = 0;
  const restZ = (i) => (i < current ? i + 1 : leaves.length * 2 - i);
  function lift(leaf) {
    lifted = leaf;
    clearTimeout(liftTimer);
    liftTimer = setTimeout(() => {
      lifted = null;
      leaves.forEach((l, i) => (l.style.zIndex = restZ(i)));
    }, 800);
  }
  function layout(animate) {
    leaves.forEach((leaf, i) => {
      const turned = i < current;
      leaf.style.zIndex = leaf === lifted ? leaves.length * 3 : restZ(i);
      leaf.classList.toggle('turned', turned);
      setAngle(leaf, turned ? dirSign() * 180 : 0, animate);
    });
    if (pages.length > 1) {
      $('#prevPage').disabled = current === 0;
      $('#nextPage').disabled = current === leaves.length - 1;
      $('#dots').querySelectorAll('i').forEach((d, i) => d.classList.toggle('on', i === current));
      $('#pageStatus').textContent = W.t('g.pageOf', { a: current + 1, b: leaves.length });
    }
  }

  function turnTo(i) {
    const next = Math.max(0, Math.min(leaves.length - 1, i));
    if (next === current) return;
    lift(next > current ? leaves[current] : leaves[next]);
    current = next;
    markTurned();
    requestAnimationFrame(() => layout(true));
  }

  function markTurned() {
    if (turnedOnce) return;
    turnedOnce = true;
    api.track('open_invite', 'flip');
  }

  // Dragging: the leaf follows the finger; let go past a quarter of the width (or flick) to turn it.
  let drag = null;
  bookEl.addEventListener('pointerdown', (e) => {
    if (e.button !== 0 || !leaves.length) return;
    drag = { x: e.clientX, y: e.clientY, t: performance.now(), dx: 0, leaf: null, forward: null, active: false, id: e.pointerId };
  });
  bookEl.addEventListener('pointermove', (e) => {
    if (!drag || e.pointerId !== drag.id) return;
    drag.dx = e.clientX - drag.x;
    const dy = e.clientY - drag.y;
    if (!drag.active) {
      if (Math.abs(drag.dx) < 8 || Math.abs(drag.dx) < Math.abs(dy)) return;
      const forward = rtl ? drag.dx > 0 : drag.dx < 0;
      if ((forward && current >= leaves.length - 1) || (!forward && current === 0)) return;
      drag.active = true;
      drag.forward = forward;
      drag.leaf = forward ? leaves[current] : leaves[current - 1];
      drag.leaf.classList.remove('turned');
      lift(drag.leaf);
      clearTimeout(liftTimer);
      bookEl.setPointerCapture(e.pointerId);
    }
    const w = bookEl.clientWidth;
    // A little ahead of the finger, so the page lifts visibly from the first movement.
    const p = Math.pow(Math.max(0, Math.min(1, Math.abs(drag.dx) / w)), 0.8);
    const deg = drag.forward ? dirSign() * 180 * p : dirSign() * 180 * (1 - p);
    setAngle(drag.leaf, deg, false);
  });
  const endDrag = (e) => {
    if (!drag || e.pointerId !== drag.id) return;
    const d = drag;
    drag = null;
    if (!d.active) {
      // A tap, not a drag: open the page large for zooming.
      if (e.type === 'pointerup' && Math.abs(d.dx) < 8) openViewer(current);
      return;
    }
    const w = bookEl.clientWidth;
    const speed = Math.abs(d.dx) / Math.max(1, performance.now() - d.t);
    const done = Math.abs(d.dx) > w * 0.25 || speed > 0.5;
    if (done) {
      current += d.forward ? 1 : -1;
      markTurned();
    }
    lift(d.leaf);
    layout(true);
  };
  bookEl.addEventListener('pointerup', endDrag);
  bookEl.addEventListener('pointercancel', endDrag);
  bookEl.addEventListener('keydown', (e) => {
    const nextKey = rtl ? 'ArrowLeft' : 'ArrowRight';
    const prevKey = rtl ? 'ArrowRight' : 'ArrowLeft';
    if (e.key === nextKey) turnTo(current + 1);
    else if (e.key === prevKey) turnTo(current - 1);
    else if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      openViewer(current);
    } else return;
    e.preventDefault();
  });
  buildBook();

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

  function openViewer(index) {
    buildViewer();
    lastFocus = document.activeElement;
    viewer.hidden = false;
    document.body.style.overflow = 'hidden';
    setZoom(0);
    const target = wrap.children[index || 0];
    scroller.scrollTop = target && index ? target.offsetTop - 4 : 0;
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
  const vis = { rsvp: false };
  function updateFloat() {
    const hide = vis.rsvp || !!saved;
    floatCta.hidden = false;
    floatCta.classList.toggle('away', hide);
  }
  if ('IntersectionObserver' in window) {
    const io = new IntersectionObserver((entries) => {
      entries.forEach((e) => {
        vis.rsvp = e.isIntersecting;
      });
      updateFloat();
    });
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
    $('#countWords').textContent = count === 1 ? W.t('g.justMe') : W.t('g.mePlus', { n: count });
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

  /* ---------- what the invitation itself can't do: navigate and add to the calendar ---------- */
  const practical = $('#practical');
  const action = (href, icon, label, onClick, newTab) => {
    const el = document.createElement(href ? 'a' : 'button');
    el.className = 'btn btn-sm';
    if (href) {
      el.href = href;
      if (newTab) {
        el.target = '_blank';
        el.rel = 'noopener';
      }
    } else el.type = 'button';
    el.innerHTML = `${icon}<span>${W.esc(label)}</span>`;
    el.addEventListener('click', onClick);
    practical.appendChild(el);
    return el;
  };
  const maps = W.mapsLinks();
  action(maps.waze, I.waze, 'Waze', () => api.track('nav', 'waze'), true);
  action(maps.maps, I.pin, 'Google Maps', () => api.track('nav', 'maps'), true);
  const cal = W.calendar();
  if (cal) {
    action(cal.google, I.calendar, W.t('g.gcal'), () => api.track('calendar', 'google'), true);
    action(null, I.calendar, W.t('g.ical'), () => {
      W.download('wedding.ics', cal.ics, 'text/calendar;charset=utf-8');
      api.track('calendar', 'ics');
    });
    const tc = $('#thanksCalendar');
    tc.href = cal.google;
    tc.innerHTML = `${I.calendar}<span>${W.esc(W.t('g.addCal'))}</span>`;
    tc.addEventListener('click', () => api.track('calendar', 'google'));
  } else $('#thanksCalendar').hidden = true;

  /* ---------- share ---------- */
  const dateText = W.dateText();
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
    // Soft petals drifting down: gold, olive, dusty rose and cream
    const colors = ['#c39a4e', '#7d8f5f', '#b78b8b', '#e6cfa3', '#d9b8ae'];
    const parts = Array.from({ length: 70 }, (_, i) => ({
      x: Math.random() * innerWidth,
      y: -20 - Math.random() * innerHeight * 0.5,
      vx: (Math.random() - 0.5) * 1.2,
      vy: 1.2 + Math.random() * 1.6,
      w: 7 + Math.random() * 6,
      h: 3.5 + Math.random() * 2.5,
      r: Math.random() * Math.PI,
      vr: (Math.random() - 0.5) * 0.06,
      sway: Math.random() * Math.PI * 2,
      color: colors[i % colors.length],
      round: false,
    }));
    const start = performance.now();
    function frame(now) {
      const t = now - start;
      ctx.clearRect(0, 0, innerWidth, innerHeight);
      parts.forEach((p) => {
        p.sway += 0.03;
        p.x += p.vx + Math.sin(p.sway) * 0.6;
        p.y += p.vy;
        p.r += p.vr;
        ctx.save();
        ctx.globalAlpha = 0.85 * Math.max(0, Math.min(1, (4200 - t) / 900));
        ctx.translate(p.x, p.y);
        ctx.rotate(p.r);
        ctx.fillStyle = p.color;
        ctx.beginPath();
        ctx.ellipse(0, 0, p.w / 2, p.h / 2, 0, 0, Math.PI * 2);
        ctx.fill();
        ctx.restore();
      });
      if (t < 4200) requestAnimationFrame(frame);
      else ctx.clearRect(0, 0, innerWidth, innerHeight);
    }
    requestAnimationFrame(frame);
  }
})();
