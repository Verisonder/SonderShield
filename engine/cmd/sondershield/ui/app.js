'use strict';
const token = new URLSearchParams(location.search).get('t') || '';
const $ = (s, r = document) => r.querySelector(s);
const el = (tag, cls, html) => { const e = document.createElement(tag); if (cls) e.className = cls; if (html != null) e.innerHTML = html; return e; };
const fmt = n => (n || 0).toLocaleString('en-US');

async function api(path, opts = {}) {
  opts.headers = Object.assign({ 'X-Token': token }, opts.headers || {});
  const r = await fetch('/api/' + path, opts);
  if (r.status === 204) return null;
  if (!r.ok) throw new Error(r.status);
  return r.json();
}
const postConfig = c => api('config', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(c) });

let state = null, cfg = null, lastLogSeq = 0, logs = [], tab = 'home', homeKey = '';

// ---- shell ----
document.querySelectorAll('nav .tab[data-tab]').forEach(b => b.onclick = () => setTab(b.dataset.tab));
$('#quitbtn').onclick = async () => {
  const r = await ask({ title: 'Quit SonderShield?', text: 'Protection stops and Windows goes back to its usual DNS. Closing the window instead keeps SonderShield running in the tray.', ok: 'Quit', danger: true });
  if (r) await api('quit', { method: 'POST' });
};

// ask: an in-app dialog. fields: [{name, placeholder, value}] -> resolves with values, or null.
function ask({ title, text = '', ok = 'OK', cancel = 'Cancel', fields = [], validate }) {
  return new Promise(resolve => {
    const bg = el('div', 'modal-bg');
    bg.innerHTML = `<div class="modal" role="dialog"><h3>${esc(title)}</h3>${text ? `<p>${esc(text)}</p>` : ''}
      ${fields.map((f, i) => `<input class="field" data-i="${i}" placeholder="${escapeAttr(f.placeholder || '')}" value="${escapeAttr(f.value || '')}">`).join('')}
      <div class="err"></div>
      <div class="actions"><button class="pill" data-a="no">${esc(cancel)}</button><button class="pill filled" data-a="yes">${esc(ok)}</button></div></div>`;
    document.body.appendChild(bg);
    const inputs = [...bg.querySelectorAll('input')];
    (inputs[0] || bg.querySelector('[data-a=yes]')).focus();
    const done = v => { bg.remove(); document.removeEventListener('keydown', key); resolve(v); };
    const yes = () => {
      const vals = inputs.map(i => i.value.trim());
      const e = validate && validate(vals);
      if (e) { bg.querySelector('.err').textContent = e; return; }
      done(fields.length ? vals : true);
    };
    const key = e => { if (e.key === 'Escape') done(null); if (e.key === 'Enter') yes(); };
    document.addEventListener('keydown', key);
    bg.querySelector('[data-a=no]').onclick = () => done(null);
    bg.querySelector('[data-a=yes]').onclick = yes;
    bg.onclick = e => { if (e.target === bg) done(null); };
  });
}

function setTab(t) {
  tab = t;
  document.querySelectorAll('nav .tab[data-tab]').forEach(b => b.classList.toggle('active', b.dataset.tab === t));
  document.querySelectorAll('.page').forEach(p => p.classList.toggle('active', p.id === 'page-' + t));
  homeKey = ''; homeShape = '';
  render();
}

async function poll() {
  try {
    state = await api('state');
    cfg = state.config;
    applyTheme();
    renderStatus();
    // Only Home follows live numbers; other pages redraw when you change something there
    // (redrawing them every second closed menus and lost your place).
    const key = JSON.stringify([state.on, state.active, state.problem, state.domains, state.today, state.stats.blocked, state.stats.threats, state.dataIn, state.dataOut, state.conns, state.apps, cfg.theme, cfg.accent]);
    if (tab === 'home' && !busy && key !== homeKey) { homeKey = key; renderHome(); }
    const newLogs = await api('log?after=' + lastLogSeq);
    if (newLogs && newLogs.length) {
      for (const e of newLogs) { logs.unshift(e); lastLogSeq = Math.max(lastLogSeq, e.seq); }
      if (logs.length > 2000) logs.length = 2000;
      if (tab === 'filter' && filterView === 'log') drawLog();
    }
    if (tab === 'conns') { tl = await api('timeline').catch(() => tl); drawConnChart(); }
    if (tab === 'conns' && connView2 === 'live' && !connPaused) { conns = await api('connections'); if (!connOpen.size) drawConns(); }
    else if (tab === 'conns' && connView2 === 'history') { hist = await api('history'); drawConns(); }
  } catch (e) { /* server starting or gone */ }
}

function applyTheme() {
  // System follows Windows: the soft grey dark (like the phone's System theme) or light.
  const t = cfg.theme === 'system' ? (matchMedia('(prefers-color-scheme: dark)').matches ? 'soft' : 'light') : cfg.theme;
  document.documentElement.classList.toggle('light', t === 'light');
  document.documentElement.classList.toggle('soft', t === 'soft');
  document.documentElement.style.setProperty('--accent', '#' + (cfg.accent & 0xffffff).toString(16).padStart(6, '0'));
}

function renderStatus() {
  const c = $('#statuschip'), t = $('#statustext');
  c.className = 'statuschip';
  if (!state.on) { t.textContent = 'Off'; }
  else if (!state.active) { c.classList.add('warn'); t.textContent = 'No queries'; }
  else { c.classList.add('on'); t.textContent = 'Protected'; }
}

function render() {
  if (!state) return;
  ({ home: renderHome, filter: renderFilter, rules: renderRules, apps: renderApps, conns: renderConns, settings: renderSettings }[tab])();
}

// ---- Home ----
// Home's shield has four looks: off (grey top), starting/stopping (rocking, top pulsing),
// on (blue, glowing), and a one-off "snap" when protection has just come on: the two
// halves spring apart and close, a ring pulses out and the blue blade sweeps across.
let busy = null, justOn = false;
function shieldSVG(phase) {
  const top = phase === 'off' ? 'var(--faint)' : 'var(--accent)';
  return `<svg viewBox="0 0 100 110" width="150" height="165"><clipPath id="hsh"><path d="M50 4L92 18V52C92 78 74 97 50 106C26 97 8 78 8 52V18Z"/></clipPath>
    <g clip-path="url(#hsh)">
      <path class="top" d="M0 0H100V30Q46 60 0 80Z" fill="${top}"/>
      <path class="bot" d="M0 88Q52 70 100 45V110H0Z" fill="var(--text)"/>
    </g></svg>`;
}
// Like the phone: numbers count up from 0 when Home appears and glide to new values after,
// the ladder steps slide in from the right one by one, the tiles rise in, and the headline
// rolls over when the state changes. Live updates only touch the numbers, so nothing replays.
let homeShape = '', lastHead = '', shown = {};
const ease = t => 1 - Math.pow(1 - t, 3);
function countTo(node, to) {
  if (!node) return;
  const f = numFmt(node);
  const key = node.dataset.k, from = shown[key] ?? 0;
  shown[key] = to;
  if (node._raf) cancelAnimationFrame(node._raf);
  if (from === to || matchMedia('(prefers-reduced-motion: reduce)').matches) { node.textContent = f(to); return; }
  const dur = from === 0 ? 1100 : 500, t0 = performance.now();
  const step = now => {
    const k = Math.min(1, (now - t0) / dur);
    node.textContent = f(from + (to - from) * ease(k));
    if (k < 1) node._raf = requestAnimationFrame(step);
  };
  node._raf = requestAnimationFrame(step);
}
function renderHome() {
  const s = state.stats, on = state.on;
  const phase = busy || (on ? 'on' : 'off');
  const total = state.today[0], blocked = state.today[1];
  const label = { off: 'PROTECTION OFF', on: 'PROTECTION ON', starting: 'STARTING…', stopping: 'TURNING OFF…' }[phase];
  const head = { off: 'Ads get through.', on: 'Ads stop here.', starting: 'Starting up…', stopping: 'Turning off…' }[phase];
  const sub = phase === 'starting' ? 'Downloading lists and pointing Windows at SonderShield. This takes a few seconds.'
    : phase === 'stopping' ? 'Putting Windows back to its usual DNS.'
    : on ? (state.domains ? fmt(state.domains) + ' ad and tracker domains get nothing back.' : 'Loading block lists…')
    : 'Turn on to block ads and trackers for every app on this PC.';
  const warn = !busy && on && !state.active, problem = !busy && state.problem ? state.problem : '';
  const p = $('#page-home');
  const shape = JSON.stringify([phase, warn, problem, sub, state.kill]);
  if (shape !== homeShape || justOn || !p.firstChild) {
    const fresh = !p.firstChild || homeShape === '';
    const snap = justOn; justOn = false;
    const roll = !fresh && head !== lastHead;
    homeShape = shape; lastHead = head;
    if (fresh) shown = {};
    p.innerHTML = `
    <div class="shieldwrap">
      <div class="shieldstage">
        <div class="ring ${snap ? 'go' : ''}"></div>
        <button class="shieldbtn ${phase} ${snap ? 'snap' : ''}" id="shield" ${busy ? 'disabled' : ''} title="${on ? 'Turn protection off' : 'Turn protection on'}">${shieldSVG(phase)}</button>
      </div>
      <div class="shieldlabel ${phase}">${label}</div>
    </div>
    <div class="headline ${roll ? 'roll' : ''}">${head}</div>
    <div class="headsub">${sub}</div>
    ${state.kill ? `<div class="banner danger" style="margin-top:22px">All internet is blocked by the panic switch. Turn it off in Firewall to restore.</div>` : ''}
    ${warn ? `<div class="banner" style="margin-top:22px">Windows isn't sending its lookups to SonderShield yet. <button class="linkbtn" id="diagbtn">See why</button></div>` : ''}
    ${problem ? `<div class="banner" style="margin-top:22px">${esc(problem)}</div>` : ''}
    <div class="homebody ${fresh ? 'enter' : ''}">
      <svg class="swoosh" height="46">
        <path class="blade ${phase === 'on' ? 'lit' : ''} ${snap ? 'sweep' : ''}" d=""/>
        <path class="swfill" d="" fill="var(--panel)"/>
      </svg>
      <div class="ladder panelgrid">
        <div class="todaycol">
          <h2>TODAY</h2>
          <div class="steps">
            <div class="step"><div class="num" data-k="t"></div><div class="lbl">DNS queries</div></div>
            <div class="step"><div class="num" data-k="b"></div><div class="lbl">ads & trackers blocked</div></div>
            <div class="step"><div class="num" data-k="r"></div><div class="lbl">of everything blocked</div></div>
          </div>
        </div>
        <div class="alltime rv">
          <h2>ALL TIME</h2>
          <div class="atgrid">
            <div class="tile rv" style="--d:1"><div class="num" data-k="aq" data-f="c"></div><div class="lbl">DNS queries</div></div>
            <div class="tile rv" style="--d:2"><div class="num" data-k="ab" data-f="c"></div><div class="lbl">blocked</div></div>
            <div class="tile rv" style="--d:3"><div class="num" data-k="thr" data-f="c"></div><div class="lbl">threats blocked\nmalware & phishing</div></div>
            <div class="tile rv" style="--d:4"><div class="num" data-k="dom" data-f="c"></div><div class="lbl">filter rules\nloaded now</div></div>
            <div class="rate rv" style="--d:5"><div class="num" data-k="ar" data-f="p"></div><div class="lbl">block rate,\nall time</div></div>
            <div class="tile rv" style="--d:6"><div class="num" data-k="mb" data-f="b"></div><div class="lbl">data not downloaded\nestimate</div></div>
            <div class="tile rv" style="--d:7"><div class="num" id="uptime"></div><div class="lbl">on without a break</div></div>
            <div class="tile rv" style="--d:8"><div class="num" data-k="din" data-f="y"></div><div class="lbl">data received\nthis session</div></div>
            <div class="tile rv" style="--d:9"><div class="num" data-k="dout" data-f="y"></div><div class="lbl">data sent\nthis session</div></div>
            <div class="tile rv" style="--d:10"><div class="num" data-k="conns"></div><div class="lbl">active\nconnections</div></div>
            <div class="tile rv" style="--d:11"><div class="num" data-k="apps"></div><div class="lbl">active apps\ntalking out</div></div>
          </div>
        </div>
      </div>
    </div>
    <div class="homebody lower">
      <div class="activity rv">
        <div class="kicker">▲ ACTIVITY</div>
        <div class="chiprow rv" style="--d:1"><button class="chip ${chartWeek ? '' : 'active'}" data-w="0">24 hours</button><button class="chip ${chartWeek ? 'active' : ''}" data-w="1">7 days</button></div>
        <div class="chartcard rv" style="--d:2" id="chart"></div>
      </div>
      <div class="topblocked rv">
        <div class="kicker">▲ TOP BLOCKED</div>
        <div class="toplist" id="toplist"></div>
      </div>
    </div>`;
    for (const n of p.querySelectorAll('.num[data-k]')) n.textContent = numFmt(n)(shown[n.dataset.k] ?? 0);
    p.querySelectorAll('.chip[data-w]').forEach(c => c.onclick = () => {
      chartWeek = c.dataset.w === '1'; chartKey = '';
      p.querySelectorAll('.chip[data-w]').forEach(x => x.classList.toggle('active', x === c));
      drawChart(true);
    });
    chartKey = ''; topKey = '';
    watchReveal(p, fresh);
    $('#shield').onclick = toggleProtection;
    drawSwoosh();
    const db = $('#diagbtn'); if (db) db.onclick = showDiag;
  }
  const n = k => p.querySelector(`.num[data-k="${k}"]`);
  countTo(n('t'), total);
  countTo(n('b'), blocked);
  countTo(n('r'), total ? blocked / total * 100 : 0);
  if (seenAll) {
    countTo(n('aq'), s.total);
    countTo(n('ab'), s.blocked);
    countTo(n('thr'), s.threats);
    countTo(n('dom'), state.domains);
    if (!state.domains) n('dom').textContent = '–';
    countTo(n('ar'), s.total ? s.blocked / s.total * 100 : 0);
    countTo(n('mb'), s.blocked * BYTES_PER_BLOCK);
  }
  $('#uptime').textContent = state.startedAt ? duration(Date.now() - state.startedAt) : 'off';
  if (seenAll) {
    countTo(n('din'), state.dataIn || 0);
    countTo(n('dout'), state.dataOut || 0);
    countTo(n('conns'), state.conns || 0);
    countTo(n('apps'), state.apps || 0);
  }
  if (seenChart) drawChart(false);
  if (seenTop) drawTop();
}

// ---- Home, lower part (all time, activity chart, top blocked) ----
const BYTES_PER_BLOCK = 30000; // same rough estimate as the phone app
let chartWeek = false, chartKey = '', topKey = '', seenAll = false, seenChart = false, seenTop = false, revealObs = null;
const trim1 = v => v.toFixed(1).replace(/\.0$/, '');
const compact = v => { v = Math.round(v); return v < 10000 ? fmt(v) : v < 1e6 ? trim1(v / 1e3) + 'K' : v < 1e9 ? trim1(v / 1e6) + 'M' : trim1(v / 1e9) + 'B'; };
const bytes = b => b < 1e6 ? Math.round(b / 1000) + ' KB' : b < 1e9 ? trim1(b / 1e6) + ' MB' : trim1(b / 1e9) + ' GB';
function numFmt(node) {
  const f = node.dataset.f;
  if (f === 'c') return compact;
  if (f === 'p' || node.dataset.k === 'r') return v => v.toFixed(1) + '%';
  if (f === 'b') return v => '≈ ' + bytes(v);
  if (f === 'y') return v => bytes(v);
  return v => fmt(Math.round(v));
}
function duration(ms) {
  const m = Math.floor(ms / 60000);
  if (m < 1) return 'just now';
  if (m < 60) return m + ' min';
  if (m < 1440) return Math.floor(m / 60) + ' h ' + (m % 60) + ' min';
  return Math.floor(m / 1440) + ' d ' + Math.floor(m % 1440 / 60) + ' h';
}

// Sections play their entrance when they scroll into view, like the phone.
function watchReveal(p, fresh) {
  if (revealObs) revealObs.disconnect();
  if (fresh) seenAll = seenChart = seenTop = false;
  const mark = sec => {
    sec.classList.add('in');
    if (sec.classList.contains('alltime') && !seenAll) { seenAll = true; renderHome(); }
    if (sec.classList.contains('activity') && !seenChart) { seenChart = true; drawChart(true); }
    if (sec.classList.contains('topblocked') && !seenTop) { seenTop = true; drawTop(true); }
  };
  const secs = p.querySelectorAll('.alltime, .activity, .topblocked');
  if (!fresh) { secs.forEach(sec => { sec.classList.add('in', 'quiet'); }); return; }
  revealObs = new IntersectionObserver(es => es.forEach(e => { if (e.isIntersecting) { revealObs.unobserve(e.target); mark(e.target); } }), { threshold: .15 });
  secs.forEach(sec => revealObs.observe(sec));
}

function chartPoints() {
  const h = state.stats.hours || [];
  if (!chartWeek) {
    const now = new Date();
    return h.slice(-24).map((v, i) => [v[0], v[1], i % 6 === 0 || i === 23 ? String((now.getHours() - 23 + i + 48) % 24).padStart(2, '0') + ':00' : '']);
  }
  const hod = new Date().getHours(), N = h.length, out = [];
  const day = new Intl.DateTimeFormat('en-US', { weekday: 'short' });
  for (let d = 6; d >= 0; d--) {
    const end = N - 1 - hod - 24 * (d - 1), start = end - 24;
    let t = 0, b = 0;
    for (let i = Math.max(0, start); i < Math.min(N, end); i++) { t += h[i][0]; b += h[i][1]; }
    out.push([t, b, day.format(Date.now() - d * 864e5)]);
  }
  return out;
}
function drawChart(animate) {
  const box = $('#chart');
  if (!box) return;
  const pts = chartPoints(), key = JSON.stringify([chartWeek, pts]);
  if (key === chartKey && !animate) return;
  chartKey = key;
  const W = 1000, H = 220, max = Math.max(1, ...pts.map(p => p[0]));
  const xy = (i, v) => [W * i / (pts.length - 1), H - H * .92 * v / max];
  const path = k => {
    const ps = pts.map((p, i) => xy(i, p[k]));
    let d = `M${ps[0][0]} ${ps[0][1]}`;
    for (let i = 1; i < ps.length; i++) { const a = ps[i - 1], b = ps[i], mx = (a[0] + b[0]) / 2; d += `C${mx} ${a[1]} ${mx} ${b[1]} ${b[0]} ${b[1]}`; }
    return d;
  };
  const q = path(0), b = path(1), close = `L${W} ${H}L0 ${H}Z`;
  const tq = pts.reduce((a, p) => a + p[0], 0), tb = pts.reduce((a, p) => a + p[1], 0);
  box.innerHTML = `
    <div class="legend"><span><i class="sq soft"></i>Queries ${compact(tq)}</span><span><i class="sq acc"></i>Blocked ${compact(tb)}</span></div>
    <svg class="chart ${animate ? 'draw' : ''}" viewBox="0 0 ${W} ${H}" preserveAspectRatio="none">
      <defs>
        <linearGradient id="gq" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="var(--dim)" stop-opacity=".22"/><stop offset="1" stop-color="var(--dim)" stop-opacity="0"/></linearGradient>
        <linearGradient id="gb" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="var(--accent)" stop-opacity=".38"/><stop offset="1" stop-color="var(--accent)" stop-opacity="0"/></linearGradient>
      </defs>
      ${[0, 1, 2, 3].map(i => `<line x1="0" x2="${W}" y1="${H * i / 3}" y2="${H * i / 3}" class="grid"/>`).join('')}
      <g class="plot">
        <path d="${q}${close}" fill="url(#gq)"/><path d="${b}${close}" fill="url(#gb)"/>
        <path d="${q}" class="lq"/><path d="${b}" class="lb"/>
      </g>
    </svg>
    <div class="axis">${pts.filter(p => p[2]).map(p => `<span>${p[2]}</span>`).join('')}</div>`;
}
function drawTop(animate) {
  const box = $('#toplist');
  if (!box) return;
  const top = state.stats.top || [], key = JSON.stringify(top);
  if (key === topKey && !animate) return;
  topKey = key;
  if (!top.length) { box.innerHTML = '<div class="empty">Nothing blocked yet.</div>'; return; }
  const max = Math.max(1, top[0].hits);
  box.innerHTML = top.map((t, i) => `
    <div class="toprow ${animate ? 'rv' : ''}" style="--d:${i + 1}">
      <div class="trow"><span class="rank">${String(i + 1).padStart(2, '0')}</span><span class="dom">${esc(t.domain)}</span><span class="hits">${compact(t.hits)}</span></div>
      <div class="bar"><i style="width:${animate ? 0 : Math.max(2, t.hits / max * 100)}%" data-w="${Math.max(2, t.hits / max * 100)}"></i></div>
    </div>`).join('');
  if (animate) requestAnimationFrame(() => requestAnimationFrame(() => {
    box.querySelectorAll('.toprow').forEach(r => r.classList.add('in'));
    box.querySelectorAll('.bar i').forEach((b, i) => { b.style.transitionDelay = (80 * (i + 1)) + 'ms'; b.style.width = b.dataset.w + '%'; });
  }));
}

// The swoosh is drawn at its real pixel width (not stretched), so its ends can be rounded:
// the panel's top-right corner gets the same radius as the panel's other corners, the
// top-left one is the panel's own rounded corner, and the blue blade ends in a round tip.
function drawSwoosh() {
  const sv = $('#page-home .swoosh');
  if (!sv) return;
  const W = Math.round(sv.getBoundingClientRect().width), R = 16, k = W / 360;
  if (W < 60) return;
  sv.setAttribute('viewBox', `0 0 ${W} 46`);
  sv.querySelector('.swfill').setAttribute('d',
    `M${R} 46Q${150 * k} 20 ${W - R} 28Q${W} 28 ${W} ${28 + R}L${W} 47L${R} 47Z`);
  sv.querySelector('.blade').setAttribute('d',
    `M${R} 46Q${130 * k} 4 ${W - 6} 12Q${W} 12 ${W} 16Q${W} 20 ${W - 6} 20Q${155 * k} 16 ${R} 46Z`);
}
addEventListener('resize', drawSwoosh);

async function toggleProtection() {
  if (busy) return;
  const wasOn = state.on;
  busy = wasOn ? 'stopping' : 'starting';
  renderHome();
  try { await api('toggle', { method: 'POST' }); } catch (e) {}
  const started = Date.now();
  await poll();
  // Keep the "starting" look on screen for at least a moment so it doesn't just flash.
  const wait = Math.max(0, 700 - (Date.now() - started));
  setTimeout(() => {
    busy = null;
    justOn = !wasOn && state.on;
    homeKey = ''; renderHome();
  }, wait);
}

// ---- DNS diagnostic ----
// Shows whether Windows is really sending its DNS to SonderShield, adapter by adapter,
// and what to do when it isn't.
async function showDiag() {
  let d;
  try { d = await api('diag'); } catch (e) { await ask({ title: 'DNS diagnostic', text: 'Could not read the network settings (' + e.message + '). Try again in a moment.', ok: 'OK', cancel: 'Close' }); return; }
  const bg = el('div', 'modal-bg');
  const adRows = (d.adapters || []).map(a => `
    <div class="diagrow ${a.ok ? 'ok' : 'bad'}">
      <div class="diagmark">${a.ok ? '✓' : '✕'}</div>
      <div class="grow"><div class="ctitle">${esc(a.name)}${a.ours ? ' <span class="cfaint">(SonderShield)</span>' : ''}</div>
        <div class="cfaint">DNS: ${a.servers && a.servers.length ? esc(a.servers.join(', ')) : 'none / automatic'}</div></div>
    </div>`).join('') || '<div class="cfaint">No active adapters found.</div>';
  let verdict, cls;
  if (!d.on) { verdict = 'Protection is off, so nothing is pointed at SonderShield yet.'; cls = ''; }
  else if (d.receiving) { verdict = 'Windows is sending its DNS to SonderShield and queries are arriving. All good.'; cls = 'good'; }
  else if (d.allGood) { verdict = 'Every adapter is pointed at SonderShield, but no queries are arriving. The most likely cause is Windows Encrypted DNS (DoH): open Settings → Network & internet → your connection → DNS server assignment, and set DNS over HTTPS to Off. Another DNS app could also be intercepting.'; cls = 'warn'; }
  else { verdict = "One or more adapters below aren't pointed at SonderShield (✕). SonderShield should set them automatically — switch protection off and on. If an adapter keeps showing ✕, that adapter type may not be caught yet; tell me which one."; cls = 'warn'; }
  bg.innerHTML = `<div class="modal diag" role="dialog">
    <h3>DNS diagnostic</h3>
    <div class="diagverdict ${cls}">${verdict}</div>
    <div class="diagmeta">Mode: <b>${d.mode === 'adapter' ? 'SonderShield adapter' : d.mode === 'local' ? 'This PC (port 53)' : '—'}</b> · SonderShield answers on: <b>${(d.expected || []).join(', ') || '—'}</b> · Queries arriving: <b>${d.receiving ? 'yes' : 'no'}</b></div>
    <div class="diagadapters">${adRows}</div>
    <div class="actions"><button class="pill" data-a="close">Close</button><button class="pill filled" data-a="refresh">Refresh</button></div>
  </div>`;
  document.body.appendChild(bg);
  const close = () => bg.remove();
  bg.querySelector('[data-a=close]').onclick = close;
  bg.querySelector('[data-a=refresh]').onclick = () => { close(); showDiag(); };
  bg.onclick = e => { if (e.target === bg) close(); };
}

// ---- Connections ----
// A live view of every outbound connection on this PC and which app opened it, like
// Portmaster's network monitor. Built from Windows' own connection table (no driver),
// with the hostname filled in from what SonderShield resolved. Connections to an IP that
// was never looked up are flagged "direct IP" — a DNS filter can't see their name.
let conns = null, hist = null, connPaused = false, connSearch = '', connView2 = 'live';
let connFilter = 'all', connGroup = 'app', connSort = 'recent', connOpen = new Set();
function renderConns() {
  const p = $('#page-conns');
  const live = connView2 === 'live';
  if (live && connSort === 'recent') connSort = 'most';
  const chips = (name, cur, opts) => opts.map(([v, l]) => `<button class="chip sm ${cur === v ? 'active' : ''}" data-${name}="${v}">${l}</button>`).join('');
  p.innerHTML = `<div class="kicker">NETWORK</div><h1>Connections</h1>
    <div class="subtabs"><button class="chip ${live ? 'active' : ''}" id="cv-live">Live</button><button class="chip ${live ? '' : 'active'}" id="cv-hist">History</button></div>
    <div class="sub">${live ? 'Every app talking to the internet right now, and where to.' : 'Everything each app has connected to since protection turned on.'}</div>
    <div class="connbar">
      <input class="field" id="connsearch" placeholder="Search app, site or address…" value="${escapeAttr(connSearch)}">
      ${live ? `<button class="pill ${connPaused ? 'filled' : ''}" id="connpause">${connPaused ? 'Resume' : 'Pause'}</button>` : ''}
    </div>
    <div class="connctl">
      <div class="cgrp"><span class="clabel">Show</span>${chips('f', connFilter, [['all', 'All'], ['allowed', 'Allowed'], ['blocked', 'Blocked'], ['direct', 'Direct IP']])}</div>
      <div class="cgrp"><span class="clabel">Group</span>${chips('g', connGroup, [['app', 'App'], ['site', 'Site'], ['none', 'None']])}</div>
      <div class="cgrp"><span class="clabel">Sort</span>${chips('s', connSort, live ? [['most', 'Most'], ['name', 'Name']] : [['recent', 'Recent'], ['most', 'Most seen'], ['name', 'Name']])}</div>
    </div>
    <div class="chartcard connchart" id="connchart"></div>
    <div id="connlist"><div class="empty">Reading…</div></div>`;
  connChartKey = ''; drawConnChart();
  $('#cv-live').onclick = () => { connView2 = 'live'; connOpen.clear(); renderConns(); poll(); };
  $('#cv-hist').onclick = () => { connView2 = 'history'; connOpen.clear(); renderConns(); poll(); };
  $('#connsearch').oninput = e => { connSearch = e.target.value; drawConns(); };
  const pb = $('#connpause'); if (pb) pb.onclick = () => { connPaused = !connPaused; renderConns(); if (!connPaused) poll(); };
  p.querySelectorAll('[data-f]').forEach(c => c.onclick = () => { connFilter = c.dataset.f; renderConns(); });
  p.querySelectorAll('[data-g]').forEach(c => c.onclick = () => { connGroup = c.dataset.g; renderConns(); });
  p.querySelectorAll('[data-s]').forEach(c => c.onclick = () => { connSort = c.dataset.s; renderConns(); });
  drawConns();
}

// Connections over time (open connections, and lookups blocked in each 5-second step),
// like Portmaster's activity chart. Redrawn only when a new sample arrives.
let tl = null, connRange = 10, connChartKey = '';
function drawConnChart() {
  const box = $('#connchart');
  if (!box) return;
  const last = tl && tl.length ? tl[tl.length - 1].t : 0;
  const key = connRange + '|' + last + '|' + (tl ? tl.length : 0);
  if (key === connChartKey) return;
  connChartKey = key;
  const now = Date.now(), from = now - connRange * 60000;
  const pts = (tl || []).filter(p => p.t >= from);
  const blockedSum = pts.reduce((n, p) => n + p.blocked, 0);
  const openNow = pts.length ? pts[pts.length - 1].conns : 0;
  const head = `<div class="cchead"><span class="ccttl">Activity</span>
    <span class="legend"><span><i class="sq acc"></i>Open connections ${openNow}</span><span><i class="sq warnsq"></i>Blocked lookups ${fmt(blockedSum)}</span></span>
    <span class="ccrange"><button class="chip sm ${connRange === 10 ? 'active' : ''}" data-r="10">10 min</button><button class="chip sm ${connRange === 60 ? 'active' : ''}" data-r="60">1 hour</button></span></div>`;
  let body;
  if (pts.length < 2) {
    body = '<div class="empty" style="padding:26px 0 14px">The chart fills in while protection is on.</div>';
  } else {
    const W = 1000, H = 150;
    const max = Math.max(1, ...pts.map(p => Math.max(p.conns, p.blocked)));
    const xy = (p, v) => [(p.t - from) / (now - from) * W, H - H * 0.9 * v / max];
    const path = k => {
      const ps = pts.map(p => xy(p, p[k]));
      let d = `M${ps[0][0].toFixed(1)} ${ps[0][1].toFixed(1)}`;
      for (let i = 1; i < ps.length; i++) { const a = ps[i - 1], b = ps[i], mx = (a[0] + b[0]) / 2; d += `C${mx.toFixed(1)} ${a[1].toFixed(1)} ${mx.toFixed(1)} ${b[1].toFixed(1)} ${b[0].toFixed(1)} ${b[1].toFixed(1)}`; }
      return d;
    };
    const x0 = xy(pts[0], 0)[0].toFixed(1), x1 = xy(pts[pts.length - 1], 0)[0].toFixed(1);
    const close = `L${x1} ${H}L${x0} ${H}Z`;
    const c = path('conns'), b = path('blocked');
    body = `<svg class="chart cchart" viewBox="0 0 ${W} ${H}" preserveAspectRatio="none">
      <defs>
        <linearGradient id="cgc" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="var(--accent)" stop-opacity=".32"/><stop offset="1" stop-color="var(--accent)" stop-opacity="0"/></linearGradient>
        <linearGradient id="cgb" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="var(--warn)" stop-opacity=".3"/><stop offset="1" stop-color="var(--warn)" stop-opacity="0"/></linearGradient>
      </defs>
      ${[0, 1, 2, 3].map(i => `<line x1="0" x2="${W}" y1="${H * i / 3}" y2="${H * i / 3}" class="grid"/>`).join('')}
      <path d="${c}${close}" fill="url(#cgc)"/><path d="${b}${close}" fill="url(#cgb)"/>
      <path d="${c}" class="lb"/><path d="${b}" class="lw"/>
    </svg>
    <div class="axis"><span>${connRange === 10 ? '10 min ago' : '1 hour ago'}</span><span>${connRange === 10 ? '5 min ago' : '30 min ago'}</span><span>now</span></div>`;
  }
  box.innerHTML = head + body;
  box.querySelectorAll('[data-r]').forEach(c => c.onclick = () => { connRange = +c.dataset.r; connChartKey = ''; drawConnChart(); });
}

function timeAgo(ms) {
  const s = Math.max(0, (Date.now() - ms) / 1000);
  if (s < 60) return 'just now';
  if (s < 3600) return Math.floor(s / 60) + 'm ago';
  if (s < 86400) return Math.floor(s / 3600) + 'h ago';
  return Math.floor(s / 86400) + 'd ago';
}

// Live connections and history rows, in one shape.
function connItems() {
  if (connView2 === 'history') return (hist || []).map(e => ({ ...e, key: 'h|' + e.app + '|' + e.remote + '|' + e.domain }));
  return (conns || []).map(c => ({
    app: c.app, path: c.path, domain: c.domain, remote: c.remoteAddr, port: c.remotePort, state: c.state,
    blocked: c.blocked, direct: c.direct, count: 1, lastSeen: Date.now(),
    key: 'l|' + c.pid + '|' + c.localPort + '|' + c.remoteAddr + '|' + c.remotePort,
  }));
}

function drawConns() {
  const box = $('#connlist');
  if (!box) return;
  const live = connView2 === 'live';
  if (live ? !conns : !hist) return;
  const q = connSearch.trim().toLowerCase();
  let list = connItems();
  if (q) list = list.filter(i => (i.app || '').toLowerCase().includes(q) || (i.domain || '').toLowerCase().includes(q) || (i.remote || '').includes(q));
  if (connFilter === 'allowed') list = list.filter(i => !i.blocked);
  else if (connFilter === 'blocked') list = list.filter(i => i.blocked);
  else if (connFilter === 'direct') list = list.filter(i => i.direct);
  if (!list.length) {
    const none = live ? 'No outbound connections right now.' : 'No history yet. It builds up while protection is on.';
    box.innerHTML = `<div class="empty">${connItems().length ? 'Nothing matches.' : none}${live && !q ? ' (On Linux this list is empty.)' : ''}</div>`;
    return;
  }
  const site = i => i.domain || i.remote;
  const cmp = {
    recent: (a, b) => b.lastSeen - a.lastSeen,
    most: (a, b) => b.count - a.count,
    name: (a, b) => site(a).localeCompare(site(b)),
  }[connSort] || (() => 0);
  list.sort(cmp);

  const keyOf = { app: i => i.app || 'Unknown', site: site, none: () => '' }[connGroup];
  const groups = new Map();
  for (const i of list) { const k = keyOf(i); if (!groups.has(k)) groups.set(k, []); groups.get(k).push(i); }
  const order = [...groups.keys()];
  const total = k => groups.get(k).reduce((n, i) => n + i.count, 0);
  if (connSort === 'name') order.sort((a, b) => a.localeCompare(b));
  else if (connSort === 'most') order.sort((a, b) => total(b) - total(a));

  const row = i => {
    const open = connOpen.has(i.key);
    const dest = `${i.domain ? esc(i.domain) : `<span class="cip">${esc(i.remote)}</span>`}${i.direct ? '<span class="cdirect">direct IP</span>' : ''}${i.blocked ? '<span class="cgblocked sm">blocked</span>' : ''}`;
    const who = `${appIconHTML(i.path, i.app)}<span class="crapp">${esc(i.app || 'Unknown')}</span>`;
    const main = connGroup === 'app' ? `<span class="cdest">${dest}</span>`
      : connGroup === 'site' ? `<span class="cdest cwho">${who}</span>`
      : `<span class="cdest cwho">${who}<span class="csep">→</span>${dest}</span>`;
    const meta = live ? `<span class="cstate ${i.state === 'established' ? 'live' : ''}">${esc(i.state || '')}</span>`
      : `${i.count > 1 ? `<span class="histcount">×${i.count}</span>` : ''}<span class="histago">${timeAgo(i.lastSeen)}</span>`;
    const kv = (k, v) => `<div class="kv"><span>${k}</span><b>${v}</b></div>`;
    const detail = !open ? '' : `<div class="conndetail">
        ${kv('App', esc(i.app || 'Unknown'))}
        ${kv('Program', `<span class="mono">${esc(i.path || '—')}</span>`)}
        ${kv('Site', i.domain ? esc(i.domain) : 'No lookup seen (direct to the address)')}
        ${kv('Address', `<span class="mono">${esc(i.remote)}:${i.port}</span>`)}
        ${live ? kv('State', esc(i.state || '')) : kv('First seen', new Date(i.firstSeen).toLocaleString()) + kv('Last seen', new Date(i.lastSeen).toLocaleString()) + kv('Times seen', i.count)}
        ${kv('Status', i.blocked ? 'This app is blocked' : 'Allowed')}
        <div class="dacts">
          ${i.path ? `<button class="pill" data-act="app" data-key="${escapeAttr(i.key)}">App rules…</button>` : ''}
          <button class="pill danger" data-act="ip" data-key="${escapeAttr(i.key)}">Block this address</button>
          <button class="pill" data-act="copy" data-key="${escapeAttr(i.key)}">Copy address</button>
        </div></div>`;
    return `<div class="connrow2 ${open ? 'open' : ''}"><div class="crmain" data-key="${escapeAttr(i.key)}">${main}<span class="cport">:${i.port}</span>${meta}</div>${detail}</div>`;
  };
  box.innerHTML = order.map(k => {
    const g = groups.get(k);
    if (connGroup === 'none') return `<div class="conngroup"><div class="congbody">${g.map(row).join('')}</div></div>`;
    const head = connGroup === 'app' ? `${appIconHTML(g[0].path, k)}<span class="cgname">${esc(k)}</span>` : `<span class="cgname">${esc(k)}</span>`;
    const blocked = connGroup === 'app' && g[0].blocked;
    return `<div class="conngroup ${blocked ? 'isblocked' : ''}">
      <div class="conghead">${head}${blocked ? '<span class="cgblocked">blocked</span>' : ''}<span class="cgcount">${g.length}</span></div>
      <div class="congbody">${g.map(row).join('')}</div></div>`;
  }).join('');

  const byKey = k => list.find(i => i.key === k);
  box.onclick = async ev => {
    const act = ev.target.closest('[data-act]');
    if (act) {
      const i = byKey(act.dataset.key);
      if (!i) return;
      if (act.dataset.act === 'app') openApp(i.path);
      else if (act.dataset.act === 'copy') { try { await navigator.clipboard.writeText(i.remote); act.textContent = 'Copied'; } catch (e) {} }
      else if (act.dataset.act === 'ip') {
        const ok = await ask({ title: 'Block ' + i.remote + '?', text: 'Every app loses access to this address' + (i.domain ? ' (' + i.domain + ')' : '') + ' while protection is on. You can remove it in Firewall › Block by address or port.', ok: 'Block', danger: true });
        if (!ok) return;
        cfg.netBlocks = [...(cfg.netBlocks || []), { kind: 'ip', value: i.remote, note: i.domain || i.app || '' }];
        await postConfig(cfg);
        act.textContent = 'Blocked'; act.disabled = true;
      }
      return;
    }
    const m = ev.target.closest('.crmain');
    if (!m) return;
    connOpen.has(m.dataset.key) ? connOpen.delete(m.dataset.key) : connOpen.add(m.dataset.key);
    drawConns();
  };
}

// ---- Filter ----
let filterView = 'lists', listSearch = '', logSearch = '', logFilter = 0, listsCache = [];
const GROUPS = [['ads', 'AD BLOCKING'], ['privacy', 'PRIVACY'], ['security', 'SECURITY'], ['regional', 'REGIONAL'], ['custom', 'CUSTOM']];

async function renderFilter() {
  const p = $('#page-filter');
  if (!p.dataset.init) {
    p.innerHTML = `<div class="kicker">FILTER</div><h1>Filter</h1>
      <div class="subtabs"><button class="chip active" id="fv-lists">Lists</button><button class="chip" id="fv-log">Log</button></div>
      <div id="filter-body"></div>`;
    p.dataset.init = '1';
    $('#fv-lists').onclick = () => { filterView = 'lists'; switchFilter(); };
    $('#fv-log').onclick = () => { filterView = 'log'; switchFilter(); };
  }
  switchFilter();
  if (filterView === 'lists') { listsCache = await api('lists'); renderLists(); }
}
function switchFilter() {
  $('#fv-lists').classList.toggle('active', filterView === 'lists');
  $('#fv-log').classList.toggle('active', filterView === 'log');
  $('#filter-body').innerHTML = '';
  if (filterView === 'lists') renderLists(); else renderLog();
}

function renderLists() {
  const body = $('#filter-body');
  const on = cfg.lists || [];
  const q = listSearch.toLowerCase();
  const shown = listsCache.filter(l => !q || l.name.toLowerCase().includes(q) || l.url.toLowerCase().includes(q) || (l.about || '').toLowerCase().includes(q));
  let h = `<div class="row" style="gap:10px;margin-bottom:6px">
      <input class="field grow" id="lsearch" placeholder="Search lists" value="${escapeAttr(listSearch)}">
      <button class="pill" id="updateall">Update all</button>
      <button class="pill" id="addlist">Add</button>
    </div>`;
  for (const [g, label] of GROUPS) {
    const ls = shown.filter(l => (l.custom ? 'custom' : l.group) === g);
    if (!ls.length) continue;
    const non = ls.filter(l => on.includes(l.id)).length;
    h += `<div class="seclabel"><span class="t">${label}</span><span class="n">${non} on</span></div>`;
    for (const l of ls) {
      const isOn = on.includes(l.id);
      const m = l.meta || {};
      let status = m.error ? '<span style="color:var(--warn)">' + esc(m.error) + '</span>'
        : m.updated ? fmt(m.entries) + ' rules · ' + ago(m.updated)
          : isOn ? 'Downloads when protection starts' : 'Not downloaded';
      h += `<div class="card"><div class="row">
        <div class="grow">
          <div class="row" style="gap:8px"><span class="ctitle">${esc(l.name)}</span><span class="tag">${l.custom ? 'Custom' : 'Built-in'}</span></div>
          <div class="csub">${esc(l.about || '')}</div>
          <div class="cfaint" style="color:${m.error ? 'var(--warn)' : 'var(--faint)'}">${status}</div>
          <div class="cfaint">${esc(l.url.replace('https://', ''))}</div>
        </div>
        ${l.custom ? `<button class="pill rm" data-id="${l.id}" style="padding:8px 12px">Remove</button>` : ''}
        <div class="sw ${isOn ? 'on' : ''}" data-id="${l.id}"><div class="knob"></div></div>
      </div></div>`;
    }
  }
  if (!shown.length) h += `<div class="empty">No list matches.</div>`;
  body.innerHTML = h;
  const si = $('#lsearch'); si.oninput = () => { listSearch = si.value; const pos = si.selectionStart; renderLists(); const ni = $('#lsearch'); ni.focus(); ni.setSelectionRange(pos, pos); };
  $('#updateall').onclick = async e => { e.target.textContent = 'Updating…'; e.target.disabled = true; await api('lists/update', { method: 'POST' }); listsCache = await api('lists'); renderLists(); };
  $('#addlist').onclick = addListDialog;
  body.querySelectorAll('.sw[data-id]').forEach(s => s.onclick = () => toggleList(s.dataset.id));
  body.querySelectorAll('.rm[data-id]').forEach(b => b.onclick = () => removeList(b.dataset.id));
}
async function toggleList(id) {
  const on = new Set(cfg.lists || []);
  on.has(id) ? on.delete(id) : on.add(id);
  cfg.lists = [...on];
  await postConfig(cfg);
  listsCache = await api('lists'); renderLists();
}
async function removeList(id) {
  cfg.custom = (cfg.custom || []).filter(c => customId(c.url) !== id);
  cfg.lists = (cfg.lists || []).filter(x => x !== id);
  await postConfig(cfg);
  listsCache = await api('lists'); renderLists();
}
async function addListDialog() {
  const v = await ask({
    title: 'Add a block list', text: 'Hosts files, AdBlock-style lists and plain domain lists all work.', ok: 'Add',
    fields: [{ placeholder: 'Name' }, { placeholder: 'https://…' }],
    validate: ([n, u]) => !n ? 'Give the list a name.' : !u.startsWith('https://') ? 'The address must start with https://' : '',
  });
  if (!v) return;
  const [name, url] = v;
  cfg.custom = (cfg.custom || []).concat([{ name, url }]);
  cfg.lists = (cfg.lists || []).concat([customId(url)]);
  await postConfig(cfg); listsCache = await api('lists'); renderLists();
}

function renderLog() {
  const body = $('#filter-body');
  // Build the search bar and chips once; afterwards only the list below them changes, so
  // typing in the search box isn't interrupted by new lookups arriving.
  if (!$('#loglist')) {
    body.innerHTML = `<div class="row" style="gap:10px;margin-bottom:6px">
        <input class="field grow" id="logsearch" placeholder="Search domains" value="${escapeAttr(logSearch)}">
        <button class="pill" id="clearlog">Clear</button></div>
      <div class="subtabs" id="logchips">
        <button class="chip" data-f="0">All</button><button class="chip" data-f="1">Blocked</button><button class="chip" data-f="2">Allowed</button></div>
      <div id="loglist"></div>`;
    $('#logsearch').oninput = e => { logSearch = e.target.value; drawLog(); };
    $('#clearlog').onclick = async () => { await api('clear-log', { method: 'POST' }); logs = []; drawLog(); };
    body.querySelectorAll('.chip[data-f]').forEach(c => c.onclick = () => { logFilter = +c.dataset.f; drawLog(); });
  }
  drawLog();
}
function drawLog() {
  const list = $('#loglist'); if (!list) return;
  document.querySelectorAll('#logchips .chip').forEach(c => c.classList.toggle('active', +c.dataset.f === logFilter));
  const q = logSearch.toLowerCase();
  const shown = logs.filter(e => (logFilter === 0 || (logFilter === 1 && e.verdict === 1) || (logFilter === 2 && e.verdict !== 1)) && (!q || e.domain.toLowerCase().includes(q))).slice(0, 600);
  list.innerHTML = shown.length ? shown.map(logRow).join('')
    : `<div class="empty">${state && state.on ? 'Nothing yet. Open an app or a website.' : 'Turn protection on to see queries here.'}</div>`;
}
function logRow(e) {
  const blocked = e.verdict === 1, failed = e.verdict === 2;
  const type = ({ 1: 'A', 28: 'AAAA', 65: 'HTTPS', 5: 'CNAME', 12: 'PTR', 16: 'TXT' })[e.type] || 'T' + e.type;
  const right = blocked ? '<span class="badge blocked">BLOCKED</span>'
    : `<span class="badge ok">${failed ? 'failed' : e.reason === 'cached' ? 'cached' : e.ms + ' ms'}</span>`;
  return `<div class="logrow"><div class="bar ${blocked ? 'blocked' : failed ? 'failed' : ''}"></div>
    <div class="grow"><div class="ctitle" style="font-size:14px">${esc(e.domain)}</div>
    <div class="cfaint" style="word-break:normal">${type} · ${time(e.time)}${blocked && e.reason ? ' · ' + reasonLabel(e.reason) : ''}</div></div>${right}</div>`;
}

// ---- Rules ----
function renderRules() {
  const p = $('#page-rules');
  p.innerHTML = `<div class="kicker">YOUR RULES</div><h1>Rules</h1>
    <div class="sub">Your rules beat every list. One domain per line. A domain also covers its subdomains.</div>
    <div class="seclabel"><span class="t">ALWAYS BLOCK</span></div>
    <textarea class="field" id="block" placeholder="ads.example.com">${esc(cfg.block || '')}</textarea>
    <div class="seclabel"><span class="t">ALWAYS ALLOW</span></div>
    <textarea class="field" id="allow" placeholder="cdn.example.com">${esc(cfg.allow || '')}</textarea>
    <div style="margin-top:16px"><button class="pill filled" id="saverules">Save rules</button> <span id="rulesaved" style="color:var(--faint);font-size:13px;margin-left:10px"></span></div>`;
  $('#saverules').onclick = async () => {
    cfg.block = $('#block').value; cfg.allow = $('#allow').value;
    await postConfig(cfg); $('#rulesaved').textContent = 'Saved'; setTimeout(() => $('#rulesaved').textContent = '', 2000);
  };
}

// ---- Firewall ----
let programs = null; // running apps (Firewall)
async function renderApps() {
  const p = $('#page-apps');
  const kill = state.kill;
  p.innerHTML = `<div class="kicker">FIREWALL</div><h1>Firewall</h1>
    <div class="sub">Control what each app can reach — block it entirely, block only incoming connections, or keep it off the local network. Uses Windows Firewall.</div>
    <div class="panic ${kill ? 'on' : ''}">
      <div class="panictext"><div class="panicttl">${kill ? 'All internet is blocked' : 'Block all internet'}</div>
        <div class="panicsub">${kill ? 'Every app is cut off. SonderShield still works.' : 'Cut off every app on this PC at once. A panic switch.'}</div></div>
      <button class="pill ${kill ? 'filled' : 'danger'}" id="killbtn">${kill ? 'Restore internet' : 'Block everything'}</button>
    </div>
    <div class="seclabel" style="margin-top:28px"><span class="t">BLOCK BY ADDRESS OR PORT</span></div>
    <div class="sub" style="margin-bottom:12px">Cut off outbound traffic to an IP address or range, or to a port, for every app. Applies while protection is on.</div>
    <div class="row" style="margin-bottom:12px"><button class="pill" id="addnet">Add a block</button></div>
    <div id="netlist"></div>
    <div class="seclabel" style="margin-top:28px"><span class="t">APPS</span></div>
    <div class="connbar"><input class="field" id="appsearch" placeholder="Search apps…" value="${escapeAttr(appSearch)}"><button class="pill" id="refreshprog">Refresh</button></div>
    <div id="proglist"><div class="empty">Loading apps…</div></div>`;
  $('#addnet').onclick = addNetBlock;
  drawNetBlocks();
  $('#killbtn').onclick = async () => {
    const on = !kill;
    if (on) { const ok = await ask({ title: 'Block all internet?', text: 'Every app on this PC loses internet until you switch this back off. SonderShield itself keeps working.', ok: 'Block everything', danger: true }); if (!ok) return; }
    await api('killswitch?on=' + (on ? '1' : '0'), { method: 'POST' });
    await poll(); renderApps();
  };
  $('#appsearch').oninput = e => { appSearch = e.target.value; drawPrograms(); };
  const load = async () => {
    [programs, appHist] = await Promise.all([api('programs'), api('history').catch(() => [])]);
    drawPrograms();
  };
  $('#refreshprog').onclick = load;
  if (!programs) await load(); else { drawPrograms(); load(); }
}
function drawNetBlocks() {
  const box = $('#netlist'); if (!box) return;
  const rules = cfg.netBlocks || [];
  if (!rules.length) { box.innerHTML = `<div class="empty">No address or port blocks yet.</div>`; return; }
  box.innerHTML = rules.map((r, i) => `<div class="card"><div class="row"><div class="grow">
      <div class="ctitle">${r.kind === 'port' ? 'Port ' : ''}${esc(r.value)}${r.kind === 'ip' ? '' : ''}</div>
      <div class="cfaint">${r.kind === 'ip' ? 'IP address or range' : 'TCP & UDP port'}${r.note ? ' · ' + esc(r.note) : ''}</div></div>
      <button class="pill danger" data-rm="${i}">Remove</button></div></div>`).join('');
  box.querySelectorAll('[data-rm]').forEach(b => b.onclick = async () => {
    cfg.netBlocks = (cfg.netBlocks || []).filter((_, i) => i !== +b.dataset.rm);
    await postConfig(cfg); drawNetBlocks();
  });
}
function validNet(kind, v) {
  v = v.trim();
  if (kind === 'port') { const m = /^(\d{1,5})(-(\d{1,5}))?$/.exec(v); if (!m) return false; const a = +m[1], b = m[3] ? +m[3] : a; return a >= 1 && a <= 65535 && b >= a && b <= 65535; }
  if (/^[0-9.]+(\/\d{1,2})?$/.test(v)) { const ip = v.split('/')[0].split('.'); return ip.length === 4 && ip.every(o => o !== '' && +o >= 0 && +o <= 255); }
  if (/^[0-9a-fA-F:]+(\/\d{1,3})?$/.test(v) && v.includes(':')) return true;
  return false;
}
async function addNetBlock() {
  const kindAns = await ask({ title: 'Block what?', text: 'Choose an IP address / range, or a port.', ok: 'IP address', cancel: 'Port' });
  const kind = kindAns ? 'ip' : 'port';
  const r = await ask({
    title: kind === 'ip' ? 'Block an IP address or range' : 'Block a port',
    text: kind === 'ip' ? 'A single address or a CIDR range.' : 'A port number, or a range like 6881-6889. Blocks TCP and UDP.',
    ok: 'Block', fields: [
      { placeholder: kind === 'ip' ? 'e.g. 203.0.113.5  or  203.0.113.0/24' : 'e.g. 25  or  6881-6889' },
      { placeholder: 'Note (optional)' },
    ],
    validate: v => validNet(kind, v[0]) ? '' : (kind === 'ip' ? 'Enter a valid IP or CIDR.' : 'Enter a port 1-65535, or a range.'),
  });
  if (!r) return;
  cfg.netBlocks = [...(cfg.netBlocks || []), { kind, value: r[0].trim(), note: r[1] || '' }];
  await postConfig(cfg); drawNetBlocks();
}
// ---- Apps (Firewall) ----
// Every app SonderShield knows about: running now, seen in the history, or with a rule.
// Shown as tiles with the program's own icon (a coloured letter when it has none).
let appSearch = '', appHist = null;
const MODE_INFO = {
  allow: ['Allow', 'Normal internet access.'],
  block: ['Block all', 'No internet at all, in or out.'],
  in: ['No incoming', 'Can connect out, but nothing can connect in to it.'],
  lan: ['No LAN', 'Internet works, but no access to devices on your network.'],
};
const baseName = p => (p || '').split(/[\\/]/).pop().replace(/\.exe$/i, '');
function ruleOf(path) { return (cfg.appRules || []).find(x => x.path.toLowerCase() === (path || '').toLowerCase()); }
function modeOf(path) { const r = ruleOf(path); return r && r.mode !== 'allow' ? (r.mode || 'block') : ''; }
function appIconHTML(path, name, big) {
  const n = (name || '?').trim();
  const letter = esc(n.charAt(0).toUpperCase() || '?');
  let h = 7; for (const c of n) h = (h * 31 + c.charCodeAt(0)) % 360;
  const img = path ? `<img src="/api/icon?t=${token}&p=${encodeURIComponent(path)}" alt="" onerror="iconFail(this)">` : letter;
  return `<span class="appic ${path ? '' : 'letter'} ${big ? 'big' : ''}" style="--h:${h}" data-l="${letter}">${img}</span>`;
}
window.iconFail = img => { const sp = img.parentNode; sp.classList.add('letter'); sp.textContent = sp.dataset.l; };
function knownApps() {
  const map = new Map();
  const add = (path, name, running) => {
    if (!path) return;
    const k = path.toLowerCase();
    const e = map.get(k) || { path, name: name || baseName(path), running: false };
    e.running = e.running || running;
    map.set(k, e);
  };
  (programs || []).forEach(p => add(p.path, p.name, true));
  (appHist || []).forEach(h => add(h.path, h.app, false));
  (cfg.appRules || []).forEach(r => add(r.path, baseName(r.path), false));
  return [...map.values()];
}
function appTile(e) {
  const m = modeOf(e.path);
  return `<button class="apptile ${m ? 'ruled' : ''}" data-path="${escapeAttr(e.path)}" title="${escapeAttr(e.path)}">
    ${appIconHTML(e.path, e.name)}
    <span class="apname">${esc(e.name)}</span>
    ${m ? `<span class="apmode">${MODE_INFO[m][0]}</span>` : ''}
    ${e.running ? '<span class="adot" title="Running"></span>' : ''}
  </button>`;
}
function drawPrograms() {
  const box = $('#proglist');
  if (!box) return;
  const q = appSearch.trim().toLowerCase();
  let apps = knownApps();
  if (q) apps = apps.filter(e => e.name.toLowerCase().includes(q) || e.path.toLowerCase().includes(q));
  if (!apps.length) { box.innerHTML = `<div class="empty">${q ? 'Nothing matches.' : 'No apps found yet. (On Linux this list is empty.)'}</div>`; return; }
  const byName = (a, b) => a.name.localeCompare(b.name);
  const active = apps.filter(e => e.running).sort(byName);
  const edited = apps.filter(e => { const r = ruleOf(e.path); return r && r.edited; })
    .sort((a, b) => ruleOf(b.path).edited - ruleOf(a.path).edited).slice(0, 8);
  const sec = (title, list) => list.length ? `<div class="appsec">${title}</div><div class="appgrid">${list.map(appTile).join('')}</div>` : '';
  box.innerHTML = sec('Active', active) + sec('Recently edited', edited) + sec('All', [...apps].sort(byName));
  box.querySelectorAll('.apptile').forEach(t => t.onclick = () => openApp(t.dataset.path));
}
function openApp(path) {
  const e = knownApps().find(x => x.path.toLowerCase() === path.toLowerCase()) || { path, name: baseName(path), running: false };
  const bg = el('div', 'modal-bg');
  const draw = () => {
    const m = modeOf(path) || 'allow';
    bg.innerHTML = `<div class="modal appmodal" role="dialog">
      <div class="apphead">${appIconHTML(path, e.name, true)}<div class="grow"><h3>${esc(e.name)}</h3>
        <div class="cfaint">${e.running ? 'Running now' : 'Not running'}</div></div></div>
      <div class="cfaint apppath">${esc(path)}</div>
      <div class="picks">${Object.entries(MODE_INFO).map(([v, [t, d]]) => `
        <div class="pick ${m === v ? 'sel' : ''}" data-m="${v}"><div class="grow"><div class="ctitle">${t}</div><div class="csub">${d}</div></div><div class="radio"></div></div>`).join('')}</div>
      <div class="cfaint" style="margin-top:10px">Rules apply while protection is on. Uses Windows Firewall.</div>
      <div class="actions"><button class="pill" data-a="close">Close</button></div>
    </div>`;
    bg.querySelector('[data-a=close]').onclick = () => bg.remove();
    bg.querySelectorAll('.pick[data-m]').forEach(r => r.onclick = async () => {
      const rules = (cfg.appRules || []).filter(x => x.path.toLowerCase() !== path.toLowerCase());
      rules.push({ path, mode: r.dataset.m, edited: Date.now() });
      cfg.appRules = rules;
      await postConfig(cfg);
      draw(); drawPrograms();
    });
  };
  draw();
  bg.onclick = ev => { if (ev.target === bg) bg.remove(); };
  document.body.appendChild(bg);
}


// ---- Settings ----
const ACCENTS = [0xFF3B82FF, 0xFF38B6FF, 0xFF5B6CFF, 0xFF00D1FF, 0xFF22C55E, 0xFFF97316, 0xFFEF4444, 0xFFEC4899, 0xFFA855F7, 0xFFFACC15];
async function renderSettings() {
  const cat = await api('catalog');
  const p = $('#page-settings');
  const upRow = (id, name, sub) => `<div class="pick ${cfg.upstream === id ? 'sel' : ''}" data-up="${id}"><div class="grow"><div class="ctitle">${esc(name)}</div><div class="csub">${esc(sub)}</div></div><div class="radio"></div></div>`;
  const split = n => { const m = /^(.*?) \((.*)\)$/.exec(n); return m ? [m[1], m[2].charAt(0).toUpperCase() + m[2].slice(1)] : [n, '']; };
  const ups = cat.upstreams.map(u => upRow(u.id, ...split(u.name))).join('') + upRow('custom', 'Your own server', 'Plain or encrypted (DoH)');
  const auto = await api('autostart');
  p.innerHTML = `<div class="kicker">SETTINGS</div><h1>Settings</h1>
    <div class="seclabel"><span class="t">01 DNS SERVER</span></div>
    <div class="picks">${ups}</div>
    <div><input class="field ${cfg.upstream === 'custom' ? '' : 'hidden'}" id="customup" placeholder="udp 9.9.9.9  or  doh https://host/dns-query 1.2.3.4" value="${escapeAttr(cfg.customUpstream || '')}" style="margin-top:10px"></div>
    <div class="seclabel"><span class="t">02 FORCE DNS THROUGH SONDERSHIELD</span></div>
    <div class="card"><div class="row"><div class="grow"><div class="ctitle">Stop apps using their own DNS</div>
      <div class="csub">Blocks other DNS routes — plain DNS to other servers, DNS-over-TLS, and known DNS-over-HTTPS resolvers — so browsers and apps can't skip filtering. Your own DNS keeps working. Takes effect while protection is on.</div></div>
      <div class="sw ${cfg.lockDns ? 'on' : ''}" id="lockdns"><div class="knob"></div></div></div></div>
    <div class="seclabel"><span class="t">03 UPDATE LISTS AUTOMATICALLY</span></div>
    <div class="card"><div class="subtabs" style="margin:0">
      ${[[0, 'Off'], [12, '12 h'], [24, '24 h'], [48, '48 h']].map(([h, l]) => `<button class="chip ${cfg.updateHours === h ? 'active' : ''}" data-h="${h}">${l}</button>`).join('')}
    </div></div>
    <div class="seclabel"><span class="t">04 THEME</span></div>
    <div class="card"><div class="subtabs" style="margin:0">
      ${[['default', 'Dark'], ['light', 'Light'], ['system', 'System']].map(([t, l]) => `<button class="chip ${cfg.theme === t ? 'active' : ''}" data-theme="${t}">${l}</button>`).join('')}
    </div><div class="accentrow">${ACCENTS.map(a => `<div class="swatch ${cfg.accent === a ? 'sel' : ''}" data-a="${a}" style="background:#${(a & 0xffffff).toString(16).padStart(6, '0')}"></div>`).join('')}</div></div>
    <div class="seclabel"><span class="t">05 STARTUP</span></div>
    <div class="card"><div class="row"><div class="grow"><div class="ctitle">Start with Windows</div><div class="csub">Launch in the background when you sign in.</div></div>
      <div class="sw ${auto.on ? 'on' : ''}" id="autostart"><div class="knob"></div></div></div></div>
    <div class="seclabel"><span class="t">06 STATISTICS</span></div>
    <div class="card"><div class="row"><div class="grow"><div class="ctitle">Blocked in total</div><div class="csub">${fmt(state.stats.blocked)}</div></div>
      <button class="pill" id="resetstats">Reset</button></div></div>
    <div class="seclabel"><span class="t">07 UPDATES</span></div>
    <div class="card" id="updatecard">
      <div class="row"><div class="grow"><div class="ctitle">Version ${esc(cat.version)}</div>
        <div class="csub" id="updatestatus">Check for a newer version.</div></div>
        <button class="pill" id="checkupdate">Check for updates</button></div>
      <div id="updateaction" class="hidden" style="margin-top:12px"></div>
    </div>
    <div class="seclabel"><span class="t">08 ABOUT</span></div>
    <div class="card"><div class="ctitle">SonderShield for Windows</div>
      <div class="csub" style="margin-top:6px">By Verisonder. Free and open source under Apache 2.0. Everything stays on this PC: no account, no analytics.</div>
      <div class="csub" style="margin-top:8px">Blocks ads, trackers and malware by DNS, and can cut off any app's internet.${state.on ? (state.mode === 'adapter' ? ' Filtering through the SonderShield network adapter.' : ' Filtering on this PC (port 53).') : ''}</div>
      <div style="margin-top:12px"><button class="pill" id="diagbtn2">Run DNS diagnostic</button> <button class="pill" id="src">Source code</button></div></div>`;
  p.querySelectorAll('.pick[data-up]').forEach(r => r.onclick = () => {
    cfg.upstream = r.dataset.up; save();
    p.querySelectorAll('.pick[data-up]').forEach(x => x.classList.toggle('sel', x === r));
    $('#customup').classList.toggle('hidden', cfg.upstream !== 'custom');
    if (cfg.upstream === 'custom') $('#customup').focus();
  });
  $('#customup').onchange = () => { cfg.customUpstream = $('#customup').value; save(); };
  p.querySelectorAll('.chip[data-h]').forEach(c => c.onclick = () => { cfg.updateHours = +c.dataset.h; save(); renderSettings(); });
  p.querySelectorAll('.chip[data-theme]').forEach(c => c.onclick = () => { cfg.theme = c.dataset.theme; save(); applyTheme(); renderSettings(); });
  p.querySelectorAll('.swatch[data-a]').forEach(s => s.onclick = () => { cfg.accent = +s.dataset.a; save(); applyTheme(); renderSettings(); });
  $('#lockdns').onclick = () => { cfg.lockDns = !cfg.lockDns; save(); renderSettings(); };
  $('#autostart').onclick = async () => { const on = !auto.on; await api('autostart?on=' + (on ? '1' : '0'), { method: 'POST' }); renderSettings(); };
  $('#resetstats').onclick = async () => { if (await ask({ title: 'Reset counters?', text: "Today's and all-time numbers go back to zero. The log stays.", ok: 'Reset' })) { await api('reset-stats', { method: 'POST' }); poll(); } };
  $('#src').onclick = () => open('https://github.com/Verisonder/SonderShield', '_blank');
  $('#diagbtn2').onclick = showDiag;
  $('#checkupdate').onclick = checkForUpdate;
  if (cat.version === 'dev') { $('#updatestatus').textContent = 'Development build — updates are checked only in a released build.'; }
}

async function checkForUpdate() {
  const btn = $('#checkupdate'), status = $('#updatestatus'), action = $('#updateaction');
  btn.disabled = true; btn.textContent = 'Checking…';
  try {
    const u = await api('update/check');
    btn.disabled = false; btn.textContent = 'Check again';
    if (u.error) { status.textContent = 'Could not check: ' + u.error; return; }
    if (!u.available) { status.textContent = "You're on the latest version."; action.classList.add('hidden'); return; }
    status.textContent = 'Version ' + u.latest + ' is available.';
    action.classList.remove('hidden');
    action.innerHTML = `<div class="banner" style="color:var(--dim);border-color:var(--line);background:var(--card)">${esc((u.notes||'').slice(0,600))}</div>
      <button class="pill filled" id="doupdate">Update now</button>
      <span id="upmsg" style="color:var(--faint);font-size:13px;margin-left:10px"></span>`;
    $('#doupdate').onclick = doUpdate;
  } catch (e) { btn.disabled = false; btn.textContent = 'Check for updates'; status.textContent = 'Could not check for updates.'; }
}

async function doUpdate() {
  const b = $('#doupdate'), msg = $('#upmsg');
  b.disabled = true; b.textContent = 'Downloading…';
  msg.textContent = 'This will restart SonderShield.';
  try {
    await api('update/apply', { method: 'POST' });
    msg.textContent = 'Updating… the window will reopen in a moment.';
    // The backend relaunches; this window's server goes away. Try to reopen shortly.
  } catch (e) {
    b.disabled = false; b.textContent = 'Update now';
    let m = 'Update failed.'; try { m = (await e) || m; } catch (_) {}
    msg.textContent = 'Update failed. You can download it from the Releases page.';
  }
}
const save = () => postConfig(cfg);

// ---- helpers ----
function esc(s) { return String(s == null ? '' : s).replace(/[&<>]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c])); }
function escapeAttr(s) { return esc(s).replace(/"/g, '&quot;'); }
function time(ms) { return new Date(ms).toLocaleTimeString('en-US', { hour12: false }); }
function ago(ms) { const s = (Date.now() - ms) / 1000; if (s < 60) return 'just now'; if (s < 3600) return Math.floor(s / 60) + ' min ago'; if (s < 86400) return Math.floor(s / 3600) + ' h ago'; return Math.floor(s / 86400) + ' d ago'; }
function reasonLabel(r) { if (r.startsWith('list:')) { const id = r.slice(5); return id.charAt(0).toUpperCase() + id.slice(1); } return r; }
function customId(url) { let h = 2166136261; for (let i = 0; i < url.length; i++) { h ^= url.charCodeAt(i); h = Math.imul(h, 16777619); } h = h >>> 0; return 'custom-' + h.toString(16).padStart(8, '0'); }

poll();
setInterval(poll, 1000);
