/* Small dependency-free SVG/HTML charts for the dashboard (thin marks, recessive axes, hover tooltips). */
(function () {
  'use strict';
  const W = window.W;
  const Charts = (window.Charts = {});
  const NS = 'http://www.w3.org/2000/svg';

  /* ---------- tooltip ---------- */
  const tip = () => document.getElementById('tooltip');
  Charts.showTip = (html, x, y) => {
    const t = tip();
    t.innerHTML = html;
    t.hidden = false;
    const r = t.getBoundingClientRect();
    let left = x - r.width / 2;
    let top = y - r.height - 12;
    left = Math.max(8, Math.min(innerWidth - r.width - 8, left));
    if (top < 8) top = y + 16;
    t.style.left = left + 'px';
    t.style.top = top + 'px';
  };
  Charts.hideTip = () => {
    tip().hidden = true;
  };
  function bindTip(el, html) {
    const show = (e) => {
      const r = el.getBoundingClientRect();
      const x = e && e.clientX != null ? e.clientX : r.left + r.width / 2;
      Charts.showTip(typeof html === 'function' ? html() : html, x, r.top);
    };
    el.addEventListener('pointerenter', show);
    el.addEventListener('pointermove', show);
    el.addEventListener('pointerleave', Charts.hideTip);
    el.addEventListener('focus', () => show());
    el.addEventListener('blur', Charts.hideTip);
  }

  const pct = (v, total) => (total ? Math.round((v / total) * 100) : 0);
  const unitOf = (opts, v) => (typeof opts.unit === 'function' ? opts.unit(v) : opts.unit || '');
  const empty = (el, text) => {
    el.innerHTML = `<div class="chart-empty">${W.esc(text)}</div>`;
  };

  function niceMax(v) {
    if (v <= 4) return 4;
    const p = Math.pow(10, Math.floor(Math.log10(v)));
    const n = v / p;
    const step = n <= 1 ? 1 : n <= 2 ? 2 : n <= 2.5 ? 2.5 : n <= 5 ? 5 : 10;
    return step * p;
  }
  const fmtTick = (v) => (v >= 10000 ? Math.round(v / 1000) + 'K' : W.num(v));
  const dayLabel = (t) => {
    const d = new Date(t);
    return `${d.getDate()}.${d.getMonth() + 1}`;
  };

  function svgEl(tag, attrs, parent) {
    const e = document.createElementNS(NS, tag);
    Object.entries(attrs || {}).forEach(([k, v]) => e.setAttribute(k, v));
    if (parent) parent.appendChild(e);
    return e;
  }

  /* ---------- tall segmented bar: one whole split into parts ---------- */
  Charts.segmented = (el, parts, opts = {}) => {
    const live = parts.filter((p) => p.value > 0);
    const total = live.reduce((s, p) => s + p.value, 0);
    if (!total) return empty(el, opts.empty || W.t('c.noData'));
    el.innerHTML = '';
    const bar = document.createElement('div');
    bar.className = 'segbar';
    bar.setAttribute('role', 'img');
    bar.setAttribute('aria-label', live.map((p) => `${p.label}: ${p.value} (${pct(p.value, total)}%)`).join(', '));
    live.forEach((p) => {
      const s = document.createElement('div');
      s.className = 'seg';
      s.tabIndex = 0;
      s.style.flex = `${p.value} 1 0`;
      s.style.background = `var(${p.color})`;
      s.style.color = `var(${p.on})`;
      s.textContent = pct(p.value, total) + '%';
      bindTip(s, `<b>${W.esc(p.label)}</b><br>${W.num(p.value)} ${W.esc(unitOf(opts, p.value))} · ${pct(p.value, total)}%`);
      bar.appendChild(s);
    });
    el.appendChild(bar);
    // A block too narrow for its label prints none (the value stays in the legend and the tooltip).
    requestAnimationFrame(() => {
      bar.querySelectorAll('.seg').forEach((s) => {
        if (s.scrollWidth > s.clientWidth + 1) s.textContent = '';
      });
    });
    const legend = document.createElement('ul');
    legend.className = 'legend';
    parts.forEach((p) => {
      const li = document.createElement('li');
      li.innerHTML = `<span class="sw" style="background:var(${p.color})"></span>${W.esc(p.label)} <b>${W.num(p.value)}</b>`;
      legend.appendChild(li);
    });
    el.appendChild(legend);
  };

  /* ---------- horizontal bars (RTL: grow from the right) ---------- */
  Charts.hbars = (el, rows, opts = {}) => {
    const max = Math.max(0, ...rows.map((r) => r.value));
    if (!max) return empty(el, opts.empty || W.t('c.noData'));
    const base = opts.base != null ? opts.base : rows.reduce((s, r) => s + r.value, 0);
    el.innerHTML = '';
    const box = document.createElement('div');
    box.className = 'hbars';
    rows.forEach((r) => {
      const row = document.createElement('div');
      row.className = 'hbar';
      const label = document.createElement('span');
      label.className = 'hbar-label';
      label.textContent = r.label;
      const track = document.createElement('span');
      track.className = 'hbar-track';
      const fill = document.createElement('span');
      fill.className = 'hbar-fill';
      fill.style.width = `calc((100% - 70px) * ${r.value / max})`;
      if (r.color) fill.style.background = `var(${r.color})`;
      const val = document.createElement('span');
      val.className = 'hbar-val';
      val.innerHTML = `<b>${W.num(r.value)}</b>${opts.pct !== false && base ? `<small>${pct(r.value, base)}%</small>` : ''}`;
      track.append(fill, val);
      row.append(label, track);
      box.appendChild(row);
    });
    el.appendChild(box);
  };

  /* ---------- line (single series, cumulative) ---------- */
  Charts.line = (el, points, opts = {}) => {
    if (!points.length || !points.some((p) => p.v > 0)) return empty(el, opts.empty || W.t('c.noData'));
    el.innerHTML = '';
    const width = Math.max(280, el.clientWidth);
    const height = opts.height || 210;
    const m = { l: 34, r: 34, t: 18, b: 26 };
    const iw = width - m.l - m.r;
    const ih = height - m.t - m.b;
    const t0 = points[0].t;
    const t1 = points[points.length - 1].t;
    const max = niceMax(Math.max(...points.map((p) => p.v)));
    const x = (t) => m.l + (t1 === t0 ? iw / 2 : ((t - t0) / (t1 - t0)) * iw);
    const y = (v) => m.t + ih - (v / max) * ih;

    const svg = svgEl('svg', { viewBox: `0 0 ${width} ${height}`, role: 'img', 'aria-label': opts.label || '' });
    [0, 0.5, 1].forEach((f) => {
      const v = max * f;
      svgEl('line', { x1: m.l, x2: width - m.r, y1: y(v), y2: y(v), class: f === 0 ? 'baseline' : 'gridline' }, svg);
      const tx = svgEl('text', { x: m.l - 8, y: y(v) + 4, 'text-anchor': 'end' }, svg);
      tx.textContent = fmtTick(v);
    });
    const ticks = points.length > 2 ? [points[0], points[Math.floor(points.length / 2)], points[points.length - 1]] : points;
    ticks.forEach((p, i) => {
      const tx = svgEl('text', { x: x(p.t), y: height - 6, 'text-anchor': ticks.length > 1 && i === 0 ? 'start' : i === ticks.length - 1 && ticks.length > 1 ? 'end' : 'middle' }, svg);
      tx.textContent = dayLabel(p.t);
    });

    const d = points.map((p, i) => `${i ? 'L' : 'M'}${x(p.t).toFixed(1)},${y(p.v).toFixed(1)}`).join('');
    svgEl('path', { d: `${d}L${x(t1)},${y(0)}L${x(t0)},${y(0)}Z`, style: 'fill: var(--series-wash)' }, svg);
    svgEl('path', { d, style: 'fill:none; stroke: var(--series); stroke-width: 2; stroke-linejoin: round; stroke-linecap: round' }, svg);
    const last = points[points.length - 1];
    svgEl('circle', { cx: x(last.t), cy: y(last.v), r: 4, style: 'fill: var(--series); stroke: var(--surface); stroke-width: 2' }, svg);
    const endLabel = svgEl('text', { x: x(last.t) + 8, y: y(last.v) + 4, class: 'end-label' }, svg);
    endLabel.textContent = W.num(last.v);

    // crosshair + tooltip
    const cross = svgEl('line', { y1: m.t, y2: m.t + ih, style: 'stroke: var(--axis); stroke-width: 1', visibility: 'hidden' }, svg);
    const dot = svgEl('circle', { r: 5, style: 'fill: var(--series); stroke: var(--surface); stroke-width: 2', visibility: 'hidden' }, svg);
    const hit = svgEl('rect', { x: m.l, y: 0, width: iw, height, fill: 'transparent' }, svg);
    const move = (e) => {
      const r = svg.getBoundingClientRect();
      const px = ((e.clientX - r.left) / r.width) * width;
      let best = points[0];
      points.forEach((p) => {
        if (Math.abs(x(p.t) - px) < Math.abs(x(best.t) - px)) best = p;
      });
      cross.setAttribute('x1', x(best.t));
      cross.setAttribute('x2', x(best.t));
      cross.setAttribute('visibility', 'visible');
      dot.setAttribute('cx', x(best.t));
      dot.setAttribute('cy', y(best.v));
      dot.setAttribute('visibility', 'visible');
      const sx = r.left + (x(best.t) / width) * r.width;
      const sy = r.top + (y(best.v) / height) * r.height;
      const extra = best.delta && opts.perDay ? ' ' + W.esc(opts.perDay(best.delta)) : '';
      Charts.showTip(`<b>${W.esc(W.fmtDate(new Date(best.t), { day: 'numeric', month: 'long' }))}</b><br>${W.num(best.v)} ${W.esc(unitOf(opts, best.v))}${extra}`, sx, sy);
    };
    hit.addEventListener('pointermove', move);
    hit.addEventListener('pointerdown', move);
    hit.addEventListener('pointerleave', () => {
      cross.setAttribute('visibility', 'hidden');
      dot.setAttribute('visibility', 'hidden');
      Charts.hideTip();
    });
    el.appendChild(svg);
  };

  /* ---------- columns (one per day) ---------- */
  Charts.columns = (el, bars, opts = {}) => {
    if (!bars.some((b) => b.v > 0)) return empty(el, opts.empty || W.t('c.noData'));
    el.innerHTML = '';
    const width = Math.max(280, el.clientWidth);
    const height = opts.height || 210;
    const m = { l: 34, r: 8, t: 18, b: 26 };
    const iw = width - m.l - m.r;
    const ih = height - m.t - m.b;
    const max = niceMax(Math.max(...bars.map((b) => b.v)));
    const band = iw / bars.length;
    const bw = Math.max(3, Math.min(24, band - 2));
    const y = (v) => m.t + ih - (v / max) * ih;
    const svg = svgEl('svg', { viewBox: `0 0 ${width} ${height}`, role: 'img', 'aria-label': opts.label || '' });
    [0, 0.5, 1].forEach((f) => {
      const v = max * f;
      svgEl('line', { x1: m.l, x2: width - m.r, y1: y(v), y2: y(v), class: f === 0 ? 'baseline' : 'gridline' }, svg);
      const tx = svgEl('text', { x: m.l - 8, y: y(v) + 4, 'text-anchor': 'end' }, svg);
      tx.textContent = fmtTick(v);
    });
    [0, Math.floor(bars.length / 2), bars.length - 1].forEach((i, k) => {
      const tx = svgEl('text', { x: m.l + band * i + band / 2, y: height - 6, 'text-anchor': k === 0 ? 'start' : k === 2 ? 'end' : 'middle' }, svg);
      tx.textContent = dayLabel(bars[i].t);
    });
    bars.forEach((b, i) => {
      const cx = m.l + band * i + band / 2;
      if (b.v > 0) {
        const top = y(b.v);
        const h = y(0) - top;
        const r = Math.min(4, h, bw / 2);
        const x0 = cx - bw / 2;
        const x1 = cx + bw / 2;
        // 4px rounded data end, square at the baseline
        svgEl('path', { d: `M${x0},${y(0)}V${top + r}Q${x0},${top} ${x0 + r},${top}H${x1 - r}Q${x1},${top} ${x1},${top + r}V${y(0)}Z`, style: 'fill: var(--series)' }, svg);
      }
      const hit = svgEl('rect', { x: m.l + band * i, y: m.t, width: band, height: ih, fill: 'transparent' }, svg);
      const show = () => {
        const r = svg.getBoundingClientRect();
        Charts.showTip(`<b>${W.esc(W.fmtDate(new Date(b.t), { weekday: 'short', day: 'numeric', month: 'long' }))}</b><br>${W.num(b.v)} ${W.esc(unitOf(opts, b.v))}`, r.left + (cx / width) * r.width, r.top + (y(b.v) / height) * r.height);
      };
      hit.addEventListener('pointerenter', show);
      hit.addEventListener('pointerdown', show);
      hit.addEventListener('pointerleave', Charts.hideTip);
    });
    el.appendChild(svg);
  };
})();
