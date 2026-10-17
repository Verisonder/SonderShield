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

let state = null, cfg = null, lastLogSeq = 0, logs = [], tab = 'home';

// ---- shell ----
document.querySelectorAll('nav .tab[data-tab]').forEach(b => b.onclick = () => setTab(b.dataset.tab));
$('#quitbtn').onclick = async () => { if (confirm('Quit SonderShield? Protection will stop.')) { await api('quit', { method: 'POST' }); setTimeout(() => window.close(), 300); } };

function setTab(t) {
  tab = t;
  document.querySelectorAll('nav .tab[data-tab]').forEach(b => b.classList.toggle('active', b.dataset.tab === t));
  document.querySelectorAll('.page').forEach(p => p.classList.toggle('active', p.id === 'page-' + t));
  render();
}

async function poll() {
  try {
    state = await api('state');
    cfg = state.config;
    applyTheme();
    renderStatus();
    if (tab === 'home' || tab === 'settings' || tab === 'filter') render();
    const newLogs = await api('log?after=' + lastLogSeq);
    if (newLogs && newLogs.length) {
      for (const e of newLogs) { logs.unshift(e); lastLogSeq = Math.max(lastLogSeq, e.seq); }
      if (logs.length > 2000) logs.length = 2000;
      if (tab === 'filter' && filterView === 'log') renderLog();
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
function shieldSVG(on) {
  return `<svg viewBox="0 0 100 110" width="150" height="165"><clipPath id="hsh"><path d="M50 4L92 18V52C92 78 74 97 50 106C26 97 8 78 8 52V18Z"/></clipPath>
    <g clip-path="url(#hsh)">
      <path d="M0 0H100V30Q46 60 0 80Z" fill="${on ? 'var(--accent)' : 'var(--faint)'}"/>
      <path d="M0 88Q52 70 100 45V110H0Z" fill="var(--text)"/>
    </g></svg>`;
}
function renderHome() {
  const s = state.stats, on = state.on;
  const total = state.today[0], blocked = state.today[1];
  const rate = total ? Math.round(blocked / total * 100) : 0;
  const p = $('#page-home');
  p.innerHTML = `
    <div class="shieldwrap">
      <button class="shieldbtn ${on ? 'on' : ''}" id="shield">${shieldSVG(on)}</button>
      <div class="shieldlabel ${on ? 'on' : ''}">${on ? 'PROTECTION ON' : 'PROTECTION OFF'}</div>
    </div>
    <div class="headline">${on ? 'Ads stop here.' : 'Ads get through.'}</div>
    <div class="headsub">${on
      ? (state.domains ? fmt(state.domains) + ' ad and tracker domains get nothing back.' : 'Loading block lists…')
      : 'Turn on to block ads and trackers for every app on this PC.'}</div>
    ${state.on && !state.active ? `<div class="banner" style="margin-top:22px">Windows isn't sending its lookups to SonderShield yet. If this stays, another DNS tool may be set, or a VPN is overriding DNS.</div>` : ''}
    ${state.problem ? `<div class="banner" style="margin-top:22px">${state.problem}</div>` : ''}
    <svg class="swoosh" viewBox="0 0 360 40" preserveAspectRatio="none"><path d="M0 40 Q130 2 360 10 L360 40 Z" fill="var(--panel)"/></svg>
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
  $('#shield').onclick = async () => { await api('toggle', { method: 'POST' }); poll(); };
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
function addListDialog() {
  const name = prompt('List name:'); if (!name) return;
  const url = prompt('Download URL (https://…):'); if (!url || !url.startsWith('https://')) { alert('URL must start with https://'); return; }
  cfg.custom = (cfg.custom || []).concat([{ name: name.trim(), url: url.trim() }]);
  cfg.lists = (cfg.lists || []).concat([customId(url.trim())]);
  postConfig(cfg).then(async () => { listsCache = await api('lists'); renderLists(); });
}

function renderLog() {
  const body = $('#filter-body');
  const q = logSearch.toLowerCase();
  const shown = logs.filter(e => (logFilter === 0 || (logFilter === 1 && e.verdict === 1) || (logFilter === 2 && e.verdict !== 1)) && (!q || e.domain.toLowerCase().includes(q))).slice(0, 600);
  let h = `<div class="row" style="gap:10px;margin-bottom:6px">
      <input class="field grow" id="logsearch" placeholder="Search domains" value="${escapeAttr(logSearch)}">
      <button class="pill" id="clearlog">Clear</button></div>
    <div class="subtabs">
      <button class="chip ${logFilter === 0 ? 'active' : ''}" data-f="0">All</button>
      <button class="chip ${logFilter === 1 ? 'active' : ''}" data-f="1">Blocked</button>
      <button class="chip ${logFilter === 2 ? 'active' : ''}" data-f="2">Allowed</button></div>`;
  if (!shown.length) h += `<div class="empty">${state.on ? 'Nothing yet. Open an app or a website.' : 'Turn protection on to see queries here.'}</div>`;
  else { h += '<div>'; for (const e of shown) h += logRow(e); h += '</div>'; }
  body.innerHTML = h;
  const si = $('#logsearch'); si.oninput = () => { logSearch = si.value; const pos = si.selectionStart; renderLog(); const ni = $('#logsearch'); ni.focus(); ni.setSelectionRange(pos, pos); };
  $('#clearlog').onclick = async () => { await api('clear-log', { method: 'POST' }); logs = []; renderLog(); };
  body.querySelectorAll('.chip[data-f]').forEach(c => c.onclick = () => { logFilter = +c.dataset.f; renderLog(); });
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
  const ups = cat.upstreams.map(u => `<option value="${u.id}" ${cfg.upstream === u.id ? 'selected' : ''}>${esc(u.name)}</option>`).join('') + `<option value="custom" ${cfg.upstream === 'custom' ? 'selected' : ''}>Custom…</option>`;
  const auto = await api('autostart');
  p.innerHTML = `<div class="kicker">SETTINGS</div><h1>Settings</h1>
    <div class="seclabel"><span class="t">01 DNS SERVER</span></div>
    <div class="card"><select class="field" id="upstream">${ups}</select>
      <input class="field ${cfg.upstream === 'custom' ? '' : 'hidden'}" id="customup" placeholder="udp 9.9.9.9  or  doh https://host/dns-query 1.2.3.4" value="${escapeAttr(cfg.customUpstream || '')}" style="margin-top:10px"></div>
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
    <div class="seclabel"><span class="t">06 ABOUT</span></div>
    <div class="card"><div class="ctitle">SonderShield for Windows</div>
      <div class="csub" style="margin-top:6px">By Verisonder. Free and open source under GPL-3.0. Everything stays on this PC: no account, no analytics.</div>
      <div class="csub" style="margin-top:8px">This version blocks ads, trackers and malware by DNS, and can cut off any app's internet. It points Windows' DNS at SonderShield on this PC.</div>
      <div style="margin-top:12px"><button class="pill" id="src">Source code</button></div></div>`;
  const up = $('#upstream'); up.onchange = () => { cfg.upstream = up.value; $('#customup').classList.toggle('hidden', up.value !== 'custom'); save(); };
  $('#customup').onchange = () => { cfg.customUpstream = $('#customup').value; save(); };
  p.querySelectorAll('.chip[data-h]').forEach(c => c.onclick = () => { cfg.updateHours = +c.dataset.h; save(); renderSettings(); });
  p.querySelectorAll('.chip[data-theme]').forEach(c => c.onclick = () => { cfg.theme = c.dataset.theme; save(); applyTheme(); renderSettings(); });
  p.querySelectorAll('.swatch[data-a]').forEach(s => s.onclick = () => { cfg.accent = +s.dataset.a; save(); applyTheme(); renderSettings(); });
  $('#autostart').onclick = async () => { const on = !auto.on; await api('autostart?on=' + (on ? '1' : '0'), { method: 'POST' }); renderSettings(); };
  $('#resetstats').onclick = async () => { if (confirm('Reset all counters? The log stays.')) { await api('reset-stats', { method: 'POST' }); poll(); } };
  $('#src').onclick = () => open('https://github.com/Verisonder/SonderShield', '_blank');
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
