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
  homeKey = '';
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
    const key = JSON.stringify([state.on, state.active, state.problem, state.domains, state.today, state.stats.blocked, state.stats.threats, cfg.theme, cfg.accent]);
    if (tab === 'home' && !busy && key !== homeKey) { homeKey = key; renderHome(); }
    const newLogs = await api('log?after=' + lastLogSeq);
    if (newLogs && newLogs.length) {
      for (const e of newLogs) { logs.unshift(e); lastLogSeq = Math.max(lastLogSeq, e.seq); }
      if (logs.length > 2000) logs.length = 2000;
      if (tab === 'filter' && filterView === 'log') drawLog();
    }
  } catch (e) { /* server starting or gone */ }
}

function applyTheme() {
  const t = cfg.theme === 'system' ? (matchMedia('(prefers-color-scheme: dark)').matches ? 'default' : 'light') : cfg.theme;
  document.documentElement.classList.toggle('light', t === 'light');
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
  ({ home: renderHome, filter: renderFilter, rules: renderRules, apps: renderApps, settings: renderSettings }[tab])();
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
function renderHome() {
  const s = state.stats, on = state.on;
  const phase = busy || (on ? 'on' : 'off');
  const total = state.today[0], blocked = state.today[1];
  const rate = total ? Math.round(blocked / total * 100) : 0;
  const label = { off: 'PROTECTION OFF', on: 'PROTECTION ON', starting: 'STARTING…', stopping: 'TURNING OFF…' }[phase];
  const head = { off: 'Ads get through.', on: 'Ads stop here.', starting: 'Starting up…', stopping: 'Turning off…' }[phase];
  const sub = phase === 'starting' ? 'Downloading lists and pointing Windows at SonderShield. This takes a few seconds.'
    : phase === 'stopping' ? 'Putting Windows back to its usual DNS.'
    : on ? (state.domains ? fmt(state.domains) + ' ad and tracker domains get nothing back.' : 'Loading block lists…')
    : 'Turn on to block ads and trackers for every app on this PC.';
  const snap = justOn; justOn = false;
  const p = $('#page-home');
  p.innerHTML = `
    <div class="shieldwrap">
      <div class="shieldstage">
        <div class="ring ${snap ? 'go' : ''}"></div>
        <button class="shieldbtn ${phase} ${snap ? 'snap' : ''}" id="shield" ${busy ? 'disabled' : ''} title="${on ? 'Turn protection off' : 'Turn protection on'}">${shieldSVG(phase)}</button>
      </div>
      <div class="shieldlabel ${phase}">${label}</div>
    </div>
    <div class="headline">${head}</div>
    <div class="headsub">${sub}</div>
    ${!busy && state.on && !state.active ? `<div class="banner" style="margin-top:22px">Windows isn't sending its lookups to SonderShield yet. If this stays, another DNS tool may be set, or a VPN is overriding DNS.</div>` : ''}
    ${!busy && state.problem ? `<div class="banner" style="margin-top:22px">${esc(state.problem)}</div>` : ''}
    <svg class="swoosh" viewBox="0 0 360 46" preserveAspectRatio="none">
      <path class="blade ${phase === 'on' ? 'lit' : ''} ${snap ? 'sweep' : ''}" d="M0 46Q130 4 360 12L360 20Q155 16 0 46Z"/>
      <path d="M0 46Q150 20 360 28L360 47L0 47Z" fill="var(--panel)"/>
    </svg>
    <div class="ladder">
      <h2>TODAY</h2>
      <div class="steps">
        <div class="step"><div class="num">${fmt(total)}</div><div class="lbl">DNS queries</div></div>
        <div class="step"><div class="num">${fmt(blocked)}</div><div class="lbl">ads & trackers blocked</div></div>
        <div class="step"><div class="num">${rate}%</div><div class="lbl">of everything blocked</div></div>
      </div>
    </div>
    <div class="grid3" style="margin-top:10px">
      <div class="tile"><div class="num">${fmt(s.blocked)}</div><div class="lbl">blocked\nall time</div></div>
      <div class="tile"><div class="num">${fmt(s.threats)}</div><div class="lbl">threats blocked\nmalware & phishing</div></div>
      <div class="tile"><div class="num">${fmt(state.domains)}</div><div class="lbl">domains\non the lists</div></div>
    </div>`;
  $('#shield').onclick = toggleProtection;
}

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
let programs = null;
async function renderApps() {
  const p = $('#page-apps');
  p.innerHTML = `<div class="kicker">FIREWALL</div><h1>Firewall</h1>
    <div class="sub">Switch an app on here to cut off its internet completely — on Wi-Fi and Ethernet. Uses Windows Firewall.</div>
    <div class="row" style="margin-bottom:12px"><button class="pill" id="refreshprog">Refresh running apps</button></div>
    <div id="proglist"><div class="empty">Loading running apps…</div></div>`;
  $('#refreshprog').onclick = async () => { programs = await api('programs'); drawPrograms(); };
  if (!programs) programs = await api('programs');
  drawPrograms();
}
function drawPrograms() {
  const box = $('#proglist'); const blocked = new Set(cfg.firewall || []);
  if (!programs || !programs.length) { box.innerHTML = `<div class="empty">No apps found. (On Linux this list is empty.)</div>`; return; }
  const sorted = [...programs].sort((a, b) => (blocked.has(b.path) ? 1 : 0) - (blocked.has(a.path) ? 1 : 0) || a.name.localeCompare(b.name));
  let h = '';
  for (const pr of sorted) {
    const on = blocked.has(pr.path);
    h += `<div class="card"><div class="row"><div class="grow">
      <div class="ctitle">${esc(pr.name)}</div><div class="cfaint">${esc(pr.path)}</div></div>
      <div class="sw ${on ? 'on' : ''}" data-path="${escapeAttr(pr.path)}"><div class="knob"></div></div></div></div>`;
  }
  box.innerHTML = h;
  box.querySelectorAll('.sw[data-path]').forEach(s => s.onclick = async () => {
    const path = s.dataset.path; const set = new Set(cfg.firewall || []);
    set.has(path) ? set.delete(path) : set.add(path);
    cfg.firewall = [...set]; await postConfig(cfg); drawPrograms();
  });
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
    <div class="seclabel"><span class="t">02 UPDATE LISTS AUTOMATICALLY</span></div>
    <div class="card"><div class="subtabs" style="margin:0">
      ${[[0, 'Off'], [12, '12 h'], [24, '24 h'], [48, '48 h']].map(([h, l]) => `<button class="chip ${cfg.updateHours === h ? 'active' : ''}" data-h="${h}">${l}</button>`).join('')}
    </div></div>
    <div class="seclabel"><span class="t">03 THEME</span></div>
    <div class="card"><div class="subtabs" style="margin:0">
      ${[['default', 'Dark'], ['light', 'Light'], ['system', 'System']].map(([t, l]) => `<button class="chip ${cfg.theme === t ? 'active' : ''}" data-theme="${t}">${l}</button>`).join('')}
    </div><div class="accentrow">${ACCENTS.map(a => `<div class="swatch ${cfg.accent === a ? 'sel' : ''}" data-a="${a}" style="background:#${(a & 0xffffff).toString(16).padStart(6, '0')}"></div>`).join('')}</div></div>
    <div class="seclabel"><span class="t">04 STARTUP</span></div>
    <div class="card"><div class="row"><div class="grow"><div class="ctitle">Start with Windows</div><div class="csub">Launch in the background when you sign in.</div></div>
      <div class="sw ${auto.on ? 'on' : ''}" id="autostart"><div class="knob"></div></div></div></div>
    <div class="seclabel"><span class="t">05 STATISTICS</span></div>
    <div class="card"><div class="row"><div class="grow"><div class="ctitle">Blocked in total</div><div class="csub">${fmt(state.stats.blocked)}</div></div>
      <button class="pill" id="resetstats">Reset</button></div></div>
    <div class="seclabel"><span class="t">06 UPDATES</span></div>
    <div class="card" id="updatecard">
      <div class="row"><div class="grow"><div class="ctitle">Version ${esc(cat.version)}</div>
        <div class="csub" id="updatestatus">Check for a newer version.</div></div>
        <button class="pill" id="checkupdate">Check for updates</button></div>
      <div id="updateaction" class="hidden" style="margin-top:12px"></div>
    </div>
    <div class="seclabel"><span class="t">07 ABOUT</span></div>
    <div class="card"><div class="ctitle">SonderShield for Windows</div>
      <div class="csub" style="margin-top:6px">By Verisonder. Free and open source under GPL-3.0. Everything stays on this PC: no account, no analytics.</div>
      <div class="csub" style="margin-top:8px">Blocks ads, trackers and malware by DNS, and can cut off any app's internet.${state.on ? (state.mode === 'adapter' ? ' Filtering through the SonderShield network adapter.' : ' Filtering on this PC (port 53).') : ''}</div>
      <div style="margin-top:12px"><button class="pill" id="src">Source code</button></div></div>`;
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
  $('#autostart').onclick = async () => { const on = !auto.on; await api('autostart?on=' + (on ? '1' : '0'), { method: 'POST' }); renderSettings(); };
  $('#resetstats').onclick = async () => { if (await ask({ title: 'Reset counters?', text: "Today's and all-time numbers go back to zero. The log stays.", ok: 'Reset' })) { await api('reset-stats', { method: 'POST' }); poll(); } };
  $('#src').onclick = () => open('https://github.com/Verisonder/SonderShield', '_blank');
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
