'use strict';
// Shield-Man, the easter egg: click the SonderShield mark 7 times (the one by the name in
// the sidebar, or in Settings › About). Same game as the phone (core/ChompGame.kt and
// ui/ShieldMan.kt in the Android app): you are the shield, ads wander and are always fair
// game, malware hunts you; eat a power shield and the malware turns edible (and you grow
// teeth). Arrow keys or WASD, or drag with the mouse.

const SM = (() => {
  const MAZE = [
    '###################', '#o.......#.......o#', '#.##.###.#.###.##.#', '#.................#',
    '#.##.#.#####.#.##.#', '#....#...#...#....#', '####.###.#.###.####', '   #.#.......#.#   ',
    '####.#.##-##.#.####', '    ....#AMA#....  ', '####.#.#####.#.####', '   #.#.......#.#   ',
    '####.#.#####.#.####', '#........#........#', '#.##.###.#.###.##.#', '#o.#.....S.....#.o#',
    '##.#.#.#####.#.#.##', '#....#...#...#....#', '#.######.#.######.#', '#...........M.....#',
    '###################'];
  const W = 19, H = 21, DOOR_X = 9;
  const DIRS = { NONE: [0, 0], UP: [0, -1], DOWN: [0, 1], LEFT: [-1, 0], RIGHT: [1, 0] };
  const OPP = { UP: 'DOWN', DOWN: 'UP', LEFT: 'RIGHT', RIGHT: 'LEFT', NONE: 'NONE' };
  const THREAT_LISTS = new Set(['urlhaus', 'hagezi-tif', 'rpilist-malware', 'phishing-army']);

  // ---- the game (a line-for-line port of ChompGame.kt) ----
  class Game {
    constructor() {
      this.walls = new Array(W * H).fill(false); this.doors = new Array(W * H).fill(false);
      this.dots = new Array(W * H).fill(false); this.powers = new Array(W * H).fill(false);
      this.enemies = []; this.events = []; this.eatenNames = [];
      this.labeler = () => '';
      const ads = [], mal = [];
      for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
        const c = MAZE[y][x], i = y * W + x;
        if (c === '#') this.walls[i] = true;
        else if (c === '-') { this.doors[i] = true; this.doorY = y; }
        else if (c === '.') this.dots[i] = true;
        else if (c === 'o') this.powers[i] = true;
        else if (c === 'S') this.start = [x, y];
        else if (c === 'A') ads.push([x, y]);
        else if (c === 'M') mal.push([x, y]);
      }
      this.shield = mover(...this.start);
      ads.forEach((p, i) => this.enemies.push({ kind: 'AD', home: p, index: i, m: mover(...p), away: 0, leftHouse: false, label: '' }));
      mal.forEach((p, i) => this.enemies.push({ kind: 'MALWARE', home: p, index: i, m: mover(...p), away: 0, leftHouse: false, label: '' }));
      this.score = 0; this.lives = 3; this.level = 1; this.powerLeft = 0; this.eatenAds = 0; this.eatenMalware = 0;
      this.chain = 0; this.want = 'NONE'; this.sinceBite = 9; this.popup = null; this.popupTime = 9;
      this.resetPositions(); this.setState('READY');
    }
    setLabeler(f) { this.labeler = f; for (const e of this.enemies) e.label = f(e.kind); }
    get dotsLeft() { let n = 0; for (let i = 0; i < W * H; i++) if (this.dots[i] || this.powers[i]) n++; return n; }
    wrap(x) { return ((x % W) + W) % W; }
    wall(x, y) { return y >= 0 && y < H && this.walls[y * W + this.wrap(x)]; }
    door(x, y) { return y >= 0 && y < H && this.doors[y * W + this.wrap(x)]; }
    steer(d) { if (d === 'NONE') return; this.want = d; if (this.state === 'READY' && this.stateTime > 0.3) this.setState('PLAY'); }
    restart() { this.score = 0; this.lives = 3; this.level = 1; this.eatenAds = 0; this.eatenMalware = 0; this.eatenNames = []; this.refill(); this.resetPositions(); this.setState('READY'); }
    refill() { for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) { this.dots[y * W + x] = MAZE[y][x] === '.'; this.powers[y * W + x] = MAZE[y][x] === 'o'; } }
    resetPositions() {
      Object.assign(this.shield, { tx: this.start[0], ty: this.start[1], dir: 'NONE', f: 0 });
      this.want = 'NONE'; this.powerLeft = 0;
      for (const e of this.enemies) { Object.assign(e.m, { tx: e.home[0], ty: e.home[1], dir: 'NONE', f: 0 }); e.away = 0; e.leftHouse = false; }
    }
    setState(s) { this.state = s; this.stateTime = 0; }
    get speedUp() { return 1 + 0.08 * (this.level - 1); }
    enemySpeed(e) {
      if (!e.leftHouse) return 3.5;
      if (e.kind === 'MALWARE' && this.powerLeft > 0) return 3.4 * this.speedUp;
      return e.kind === 'AD' ? 4.6 * this.speedUp : 5.4 * this.speedUp;
    }
    open(x, y, d) { if (this.wall(x, y)) return false; if (this.door(x, y)) return false; return true; }
    tick(dtIn) {
      const dt = Math.min(dtIn, 0.05);
      this.stateTime += dt; this.sinceBite += dt; this.popupTime += dt;
      if (this.state === 'READY' || this.state === 'OVER') return;
      if (this.state === 'DYING') {
        if (this.stateTime > 1.4) { if (this.lives <= 0) { this.setState('OVER'); this.events.push('OVER'); } else { this.resetPositions(); this.setState('READY'); } }
        return;
      }
      if (this.state === 'CLEAR') { if (this.stateTime > 1.6) { this.level++; this.refill(); this.resetPositions(); this.setState('READY'); } return; }
      if (this.powerLeft > 0) { this.powerLeft -= dt; if (this.powerLeft <= 0) { this.powerLeft = 0; this.chain = 0; } }
      this.moveShield(dt);
      this.eatAt(this.shield.tx, this.shield.ty);
      for (const e of this.enemies) { if (e.away > 0) { e.away -= dt; continue; } this.moveEnemy(e, dt); }
      this.collide();
      if (this.dotsLeft === 0 && this.state === 'PLAY') { this.setState('CLEAR'); this.events.push('CLEAR'); }
    }
    moveShield(dt) {
      const m = this.shield, w = this.want;
      if (w === OPP[m.dir] && m.dir !== 'NONE') { m.tx = this.wrap(m.tx + DIRS[m.dir][0]); m.ty += DIRS[m.dir][1]; m.f = 1 - m.f; m.dir = w; }
      if (m.dir === 'NONE' || m.f === 0) {
        if (w !== 'NONE' && this.open(m.tx + DIRS[w][0], m.ty + DIRS[w][1], w)) m.dir = w;
        else if (m.dir !== 'NONE' && !this.open(m.tx + DIRS[m.dir][0], m.ty + DIRS[m.dir][1], m.dir)) m.dir = 'NONE';
      }
      if (m.dir === 'NONE') return;
      m.f += 6.2 * this.speedUp * dt;
      while (m.f >= 1) {
        m.f -= 1; m.tx = this.wrap(m.tx + DIRS[m.dir][0]); m.ty += DIRS[m.dir][1];
        this.eatAt(m.tx, m.ty);
        const ww = this.want;
        if (ww !== 'NONE' && this.open(m.tx + DIRS[ww][0], m.ty + DIRS[ww][1], ww)) m.dir = ww;
        if (!this.open(m.tx + DIRS[m.dir][0], m.ty + DIRS[m.dir][1], m.dir)) { m.dir = 'NONE'; m.f = 0; break; }
      }
    }
    eatAt(x, y) {
      const i = y * W + this.wrap(x);
      if (this.dots[i]) { this.dots[i] = false; this.score += 10; this.events.push('DOT'); this.sinceBite = 0; }
      if (this.powers[i]) {
        this.powers[i] = false; this.score += 50; this.powerLeft = 7 - 0.4 * Math.min(this.level - 1, 8); this.chain = 0;
        this.events.push('POWER'); this.sinceBite = 0;
        for (const e of this.enemies) if (e.kind === 'MALWARE' && e.leftHouse && e.m.dir !== 'NONE') {
          e.m.tx = this.wrap(e.m.tx + DIRS[e.m.dir][0]); e.m.ty += DIRS[e.m.dir][1]; e.m.f = 1 - e.m.f; e.m.dir = OPP[e.m.dir];
        }
      }
    }
    moveEnemy(e, dt) {
      const m = e.m;
      if (m.dir === 'NONE') m.dir = this.choose(e);
      m.f += this.enemySpeed(e) * dt;
      while (m.f >= 1) {
        m.f -= 1; m.tx = this.wrap(m.tx + DIRS[m.dir][0]); m.ty += DIRS[m.dir][1];
        if (m.ty < this.doorY) e.leftHouse = true;
        m.dir = this.choose(e);
        if (m.dir === 'NONE') { m.f = 0; break; }
      }
    }
    enemyOpen(e, x, y, d) { if (this.wall(x, y)) return false; if (this.door(x, y)) return !e.leftHouse && d === 'UP'; return true; }
    choose(e) {
      const m = e.m, all = ['UP', 'LEFT', 'DOWN', 'RIGHT'];
      let opts = all.filter(d => d !== OPP[m.dir] && this.enemyOpen(e, m.tx + DIRS[d][0], m.ty + DIRS[d][1], d));
      if (!opts.length) opts = all.filter(d => this.enemyOpen(e, m.tx + DIRS[d][0], m.ty + DIRS[d][1], d));
      if (!opts.length) return 'NONE';
      const s = this.shield, dist = (ax, ay, bx, by) => Math.hypot(ax - bx, ay - by);
      let target = null;
      if (!e.leftHouse) target = [DOOR_X, this.doorY - 1];
      else if (e.kind === 'MALWARE' && this.powerLeft <= 0) target = e.index === 0 ? [s.tx, s.ty] : [s.tx + DIRS[s.dir][0] * 4, s.ty + DIRS[s.dir][1] * 4];
      if (!target) {
        const fleeing = e.kind === 'MALWARE' || dist(m.tx, m.ty, s.tx, s.ty) < 5;
        if (fleeing && Math.random() < 0.75) return opts.reduce((a, b) => dist(m.tx + DIRS[b][0], m.ty + DIRS[b][1], s.tx, s.ty) > dist(m.tx + DIRS[a][0], m.ty + DIRS[a][1], s.tx, s.ty) ? b : a);
        return opts[Math.floor(Math.random() * opts.length)];
      }
      return opts.reduce((a, b) => dist(m.tx + DIRS[b][0], m.ty + DIRS[b][1], ...target) < dist(m.tx + DIRS[a][0], m.ty + DIRS[a][1], ...target) ? b : a);
    }
    collide() {
      const s = this.shield;
      for (const e of this.enemies) {
        if (e.away > 0) continue;
        let dx = Math.abs(mx(e.m) - mx(s)); if (dx > W / 2) dx = W - dx;
        if (dx < 0.7 && Math.abs(my(e.m) - my(s)) < 0.7) {
          if (e.kind === 'AD') { this.score += 100; this.eatenAds++; this.events.push('AD'); this.bite(e, 100); e.away = 4; }
          else if (this.powerLeft > 0) {
            this.chain++; const pts = 200 * (1 << Math.min(this.chain - 1, 3));
            this.score += pts; this.eatenMalware++; this.events.push('MALWARE'); this.bite(e, pts); e.away = 3;
          } else { this.lives--; this.events.push('DIED'); this.setState('DYING'); return; }
        }
      }
    }
    bite(e, pts) {
      this.sinceBite = 0;
      this.popup = { x: mx(e.m), y: my(e.m), points: pts, label: e.label }; this.popupTime = 0;
      if (e.label) this.eatenNames.push(e.label);
      Object.assign(e.m, { tx: e.home[0], ty: e.home[1], dir: 'NONE', f: 0 }); e.leftHouse = false;
      e.label = this.labeler(e.kind);
    }
  }
  function mover(tx, ty) { return { tx, ty, dir: 'NONE', f: 0 }; }
  const mx = m => m.tx + DIRS[m.dir][0] * m.f, my = m => m.ty + DIRS[m.dir][1] * m.f;

  // ---- drawing ----
  const SHIELD = new Path2D('M50 4L92 18V52C92 78 74 97 50 106C26 97 8 78 8 52V18Z');
  const TOP = new Path2D('M0 0H100V30Q46 60 0 80Z'), BOT = new Path2D('M0 88Q52 70 100 45V110H0Z');
  function q(t, p0, c, p1) { const u = 1 - t; return [u * u * p0[0] + 2 * u * t * c[0] + t * t * p1[0], u * u * p0[1] + 2 * u * t * c[1] + t * t * p1[1]]; }
  function teeth(g, p0, c, p1, down, n, alpha) {
    g.fillStyle = `rgba(255,255,255,${alpha})`;
    const step = 0.13, first = n === 5 ? 0.24 : 0.24 + step / 2, s = down ? 1 : -1;
    for (let k = 0; k < n; k++) {
      const a = first + k * step, p = q(a, p0, c, p1), pl = q(a - 0.04, p0, c, p1), pr = q(a + 0.04, p0, c, p1);
      g.beginPath(); g.moveTo(pl[0], pl[1] - 3 * s); g.lineTo(p[0], p[1] + 10 * s); g.lineTo(pr[0], pr[1] - 3 * s); g.closePath(); g.fill();
    }
  }
  // The shield: mouth on a hinge at the back, fangs only when [te] > 0.
  function man(g, cx, cy, size, open, left, te, top, bot, scale = 1, spin = 0) {
    const box = size / 100;
    g.save(); g.translate(cx, cy); g.rotate(spin * Math.PI / 180); g.scale(left ? -scale : scale, scale); g.translate(-50 * box, -55 * box); g.scale(box, box);
    g.save(); g.translate(10, 78); g.rotate(-24 * open * Math.PI / 180); g.translate(-10, -78);
    g.save(); g.clip(SHIELD); g.save(); g.clip(TOP); g.fillStyle = top; g.fill(SHIELD); g.restore(); if (te > 0) teeth(g, [0, 80], [46, 60], [100, 30], true, 5, te); g.restore(); g.restore();
    g.save(); g.translate(10, 86); g.rotate(6 * open * Math.PI / 180); g.translate(-10, -86);
    g.save(); g.clip(SHIELD); g.save(); g.clip(BOT); g.fillStyle = bot; g.fill(SHIELD); g.restore(); if (te > 0) teeth(g, [0, 88], [52, 70], [100, 45], false, 4, te); g.restore(); g.restore();
    g.restore();
  }
  function threat(g, kind, x, y, u, deg, warn, bug, initial) {
    g.save(); g.translate(x, y); g.rotate(deg * Math.PI / 180);
    if (kind === 'AD') {
      g.fillStyle = warn; g.beginPath(); g.roundRect(-u, -u * 0.62, u * 2, u * 1.24, u * 0.3); g.fill();
      g.fillStyle = '#fff'; g.font = `800 ${(initial ? 0.9 : 0.7) * u}px Unbounded`; g.textAlign = 'center'; g.textBaseline = 'middle';
      g.fillText(initial || 'AD', 0, u * 0.05);
    } else {
      g.strokeStyle = bug; g.lineWidth = u * 0.14;
      const L = (a, b, c, d) => { g.beginPath(); g.moveTo(a, b); g.lineTo(c, d); g.stroke(); };
      for (let k = -1; k <= 1; k++) { const yy = k * u * 0.38; L(-u * 0.55, yy, -u * 1.05, yy + k * u * 0.25 - u * 0.1); L(u * 0.55, yy, u * 1.05, yy + k * u * 0.25 - u * 0.1); }
      L(-u * 0.2, -u * 0.95, -u * 0.5, -u * 1.35); L(u * 0.2, -u * 0.95, u * 0.5, -u * 1.35);
      g.fillStyle = bug; g.beginPath(); g.ellipse(0, u * 0.05, u * 0.6, u * 0.75, 0, 0, 7); g.fill();
      g.beginPath(); g.arc(0, -u * 0.85, u * 0.36, 0, 7); g.fill();
      g.fillStyle = '#000'; g.beginPath(); g.arc(-u * 0.14, -u * 0.9, u * 0.08, 0, 7); g.arc(u * 0.14, -u * 0.9, u * 0.08, 0, 7); g.fill();
      if (initial) {
        g.fillStyle = 'rgba(0,0,0,.75)'; g.beginPath(); g.arc(0, u * 0.12, u * 0.45, 0, 7); g.fill();
        g.fillStyle = '#fff'; g.font = `800 ${0.55 * u}px Unbounded`; g.textAlign = 'center'; g.textBaseline = 'middle'; g.fillText(initial, 0, u * 0.14);
      }
    }
    g.restore();
  }
  const popEase = t => { // cubic-bezier(0.2, 0.9, 0.3, 1.15)
    let lo = 0, hi = 1;
    for (let i = 0; i < 24; i++) { const m = (lo + hi) / 2, x = 3 * (1 - m) * (1 - m) * m * 0.2 + 3 * (1 - m) * m * m * 0.3 + m * m * m; if (x < t) lo = m; else hi = m; }
    const m = (lo + hi) / 2; return 3 * (1 - m) * (1 - m) * m * 0.9 + 3 * (1 - m) * m * m * 1.15 + m * m * m;
  };
  const shortDomain = d => {
    const p = d.replace(/\.$/, '').toLowerCase().split('.').filter(Boolean);
    if (p.length <= 2) return p.join('.');
    return ['co', 'com', 'net', 'org', 'ac', 'gov', 'edu'].includes(p[p.length - 2]) && p[p.length - 1].length === 2 ? p.slice(-3).join('.') : p.slice(-2).join('.');
  };

  // ---- the screen ----
  function open(fromEl, ctx) {
    if (document.querySelector('.sm-bg')) return;
    const css = getComputedStyle(document.documentElement);
    const col = n => css.getPropertyValue(n).trim() || '#fff';
    const ACC = col('--accent'), TEXT = col('--text'), BG = col('--bg'), WARN = col('--warn'), DIM = col('--dim'), FAINT = col('--faint');
    const game = new Game();
    // Real names: things SonderShield blocked on this PC.
    const isThreat = r => r && r.startsWith('list:') && THREAT_LISTS.has(r.slice(5));
    const malware = [...new Set((ctx.logs || []).filter(e => e.verdict === 1 && isThreat(e.reason)).map(e => shortDomain(e.domain)))].slice(0, 40);
    const ads = [...new Set([...(ctx.logs || []).filter(e => e.verdict === 1 && !isThreat(e.reason)).map(e => e.domain), ...((ctx.top || []).map(t => t.domain))]
      .map(shortDomain).filter(d => !malware.includes(d)))].slice(0, 40);
    game.setLabeler(kind => {
      const pool = kind === 'AD' ? ads : malware, on = new Set(game.enemies.map(e => e.label));
      const free = pool.filter(n => !on.has(n)), use = free.length ? free : pool;
      return use.length ? use[Math.floor(Math.random() * use.length)] : '';
    });
    let best = ctx.best || 0, showNames = ctx.names !== false;
    const from = fromEl ? fromEl.getBoundingClientRect() : null;

    const bg = el('div', 'sm-bg');
    bg.innerHTML = `<canvas></canvas>
      <div class="sm-top"><div class="sm-kick">▲ SHIELD-MAN</div><button class="sm-x" title="Close (Esc)">✕</button></div>
      <div class="sm-score"><span class="sm-n">0</span><span class="sm-lv">LEVEL 1</span><span class="sm-best"><small>BEST</small><b>${best}</b></span></div>
      <div class="sm-bottom"><span class="sm-lives"></span><span class="sm-count"></span><button class="sm-names"></button></div>
      <div class="sm-msg hidden"><h3></h3><p></p><p class="sm-ate"></p><p class="sm-new hidden">New best!</p></div>`;
    document.body.appendChild(bg);
    const cv = bg.querySelector('canvas'), g = cv.getContext('2d');
    const $b = s => bg.querySelector(s);
    const namesBtn = $b('.sm-names');
    const drawNamesBtn = () => { namesBtn.textContent = showNames ? 'Names on' : 'Names off'; namesBtn.classList.toggle('on', showNames); };
    drawNamesBtn();
    namesBtn.onclick = () => { showNames = !showNames; drawNamesBtn(); ctx.save({ names: showNames }); };

    let dpr = 1, Wpx = 0, Hpx = 0;
    const size = () => { dpr = window.devicePixelRatio || 1; Wpx = innerWidth; Hpx = innerHeight; cv.width = Wpx * dpr; cv.height = Hpx * dpr; cv.style.width = Wpx + 'px'; cv.style.height = Hpx + 'px'; };
    size(); addEventListener('resize', size);

    let clock = 0, intro = 0, leaving = 0, closing = false, jaw = 0, facingLeft = false, last = performance.now(), raf = 0;
    const keys = { ArrowUp: 'UP', ArrowDown: 'DOWN', ArrowLeft: 'LEFT', ArrowRight: 'RIGHT', w: 'UP', s: 'DOWN', a: 'LEFT', d: 'RIGHT', W: 'UP', S: 'DOWN', A: 'LEFT', D: 'RIGHT' };
    const onKey = e => {
      if (e.key === 'Escape') { close(); return; }
      if (keys[e.key]) { e.preventDefault(); game.steer(keys[e.key]); }
      if ((e.key === 'Enter' || e.key === ' ') && game.state === 'OVER') game.restart();
    };
    document.addEventListener('keydown', onKey);
    let drag = null;
    cv.onpointerdown = e => { drag = [e.clientX, e.clientY]; };
    cv.onpointermove = e => {
      if (!drag) return;
      const dx = e.clientX - drag[0], dy = e.clientY - drag[1];
      if (Math.abs(dx) > 22 || Math.abs(dy) > 22) { game.steer(Math.abs(dx) > Math.abs(dy) ? (dx > 0 ? 'RIGHT' : 'LEFT') : (dy > 0 ? 'DOWN' : 'UP')); drag = [e.clientX, e.clientY]; }
    };
    cv.onpointerup = () => { drag = null; };
    $b('.sm-x').onclick = () => close();
    $b('.sm-msg').onclick = () => { if (game.state === 'OVER') game.restart(); };

    function close() {
      if (closing) return; closing = true;
      if (game.score > best) ctx.save({ best: game.score });
    }

    function frame(now) {
      const dt = Math.min(0.05, (now - last) / 1000); last = now; clock += dt;
      if (closing) { leaving = Math.min(1, leaving + dt / 0.3); bg.style.opacity = 1 - leaving; if (leaving >= 1) { cancelAnimationFrame(raf); removeEventListener('resize', size); document.removeEventListener('keydown', onKey); bg.remove(); return; } }
      intro = Math.min(1, intro + dt / 1.5);
      if (intro >= 1 && !closing) game.tick(dt);
      jaw += ((game.powerLeft > 0 && game.state === 'PLAY' ? 1 : 0) - jaw) * Math.min(1, dt * 9);
      if (game.shield.dir === 'LEFT') facingLeft = true; else if (game.shield.dir === 'RIGHT') facingLeft = false;
      for (const ev of game.events.splice(0)) if (ev === 'OVER' && game.score > best) { best = game.score; ctx.save({ best }); }
      draw();
      raf = requestAnimationFrame(frame);
    }

    function draw() {
      const i = intro;
      g.setTransform(dpr, 0, 0, dpr, 0, 0);
      g.clearRect(0, 0, Wpx, Hpx);
      g.globalAlpha = Math.min(1, i / 0.35); g.fillStyle = BG; g.fillRect(0, 0, Wpx, Hpx); g.globalAlpha = 1;
      const topPx = 140, bottomPx = 80;
      const tile = Math.min((Wpx - 48) / W, (Hpx - topPx - bottomPx) / H), mw = tile * W, mh = tile * H;
      const ox = (Wpx - mw) / 2, oy = topPx + (Hpx - topPx - bottomPx - mh) / 2;
      const at = (x, y) => [ox + (x + 0.5) * tile, oy + (y + 0.5) * tile];
      const sp = at(mx(game.shield), my(game.shield));
      const reveal = Math.max(0, Math.min(1, (i - 0.45) / 0.5)), r = Math.hypot(mw, mh) * reveal, inset = tile * 0.18;
      g.save(); g.beginPath(); g.rect(ox, oy, mw, mh); g.clip();
      const wallCol = mix(BG, ACC, 0.32);
      for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
        const c = at(x, y), dc = Math.hypot(c[0] - sp[0], c[1] - sp[1]);
        if (dc > r) continue;
        const a = Math.max(0, Math.min(1, (r - dc) / (tile * 3)));
        g.globalAlpha = a;
        if (game.wall(x, y)) {
          const tx = ox + x * tile, ty = oy + y * tile; g.fillStyle = wallCol;
          g.beginPath(); g.roundRect(tx + inset, ty + inset, tile - 2 * inset, tile - 2 * inset, tile * 0.25); g.fill();
          if (x + 1 < W && game.wall(x + 1, y)) g.fillRect(tx + tile / 2, ty + inset, tile, tile - 2 * inset);
          if (game.wall(x, y + 1)) g.fillRect(tx + inset, ty + tile / 2, tile - 2 * inset, tile);
        } else if (game.door(x, y)) { g.fillStyle = WARN; g.globalAlpha = 0.6 * a; g.fillRect(ox + x * tile, oy + y * tile + tile * 0.42, tile, tile * 0.16); }
        const k = y * W + x;
        if (game.dots[k]) { g.globalAlpha = 0.55 * a; g.fillStyle = TEXT; g.beginPath(); g.arc(c[0], c[1], tile * 0.09, 0, 7); g.fill(); }
        if (game.powers[k]) { g.globalAlpha = a; man(g, c[0], c[1], tile * 0.8 * (1 + 0.12 * Math.sin(clock * 6)), 0, false, 0, ACC, TEXT); }
      }
      g.globalAlpha = 1;
      if (i >= 1) {
        const scared = game.powerLeft > 0, blink = game.powerLeft > 0.01 && game.powerLeft <= 2 && Math.floor(clock * 6) % 2 === 0;
        const u = tile * 0.44;
        for (const e of game.enemies) {
          if (e.away > 0) continue;
          const p = at(mx(e.m), my(e.m)), bob = Math.sin(clock * 8 + e.index) * tile * 0.06;
          const ini = e.label ? e.label[0].toUpperCase() : null;
          if (e.kind === 'AD') threat(g, 'AD', p[0], p[1] + bob, u, Math.sin(clock * 5 + e.index) * 10, WARN, '', ini);
          else threat(g, 'MALWARE', p[0], p[1] + bob, u * 0.9, Math.sin(clock * 9) * 8, WARN, scared ? (blink ? '#fff' : '#6B7CFF') : '#4ADE80', ini);
        }
        // Name tags: out of the house only, never on top of each other.
        if (showNames) {
          const placed = [];
          g.font = `700 ${Math.max(9, tile * 0.42)}px "DM Sans"`; g.textBaseline = 'middle'; g.textAlign = 'left';
          for (const e of game.enemies) {
            if (e.away > 0 || !e.leftHouse || !e.label) continue;
            const p = at(mx(e.m), my(e.m)), name = e.label.slice(0, 16), tw = g.measureText(name).width, pad = 5, th = Math.max(13, tile * 0.6);
            const rx = p[0] - tw / 2 - pad, ry = p[1] - tile * 0.95 - th, rw = tw + 2 * pad;
            if (placed.some(b => rx < b[0] + b[2] && rx + rw > b[0] && ry < b[1] + b[3] && ry + th > b[1])) continue;
            placed.push([rx, ry, rw, th]);
            g.fillStyle = e.kind === 'AD' ? WARN : scared ? '#6B7CFF' : '#16A34A'; g.globalAlpha = 0.9;
            g.beginPath(); g.roundRect(rx, ry, rw, th, th / 2); g.fill(); g.globalAlpha = 1;
            g.fillStyle = '#fff'; g.fillText(name, rx + pad, ry + th / 2 + 1);
          }
        }
        const pu = game.popup;
        if (pu && game.popupTime < 1.1) {
          const qq = game.popupTime / 1.1, p = at(pu.x, pu.y - qq * 1.2);
          g.globalAlpha = 1 - qq; g.textAlign = 'center'; g.textBaseline = 'middle';
          g.fillStyle = ACC; g.font = `800 ${tile * 0.6}px Unbounded`; g.fillText('+' + pu.points, p[0], p[1]);
          if (showNames && pu.label) { g.fillStyle = TEXT; g.font = `700 ${tile * 0.45}px "DM Sans"`; g.fillText(pu.label.slice(0, 22), p[0], p[1] + tile * 0.6); }
          g.globalAlpha = 1;
        }
      }
      g.restore();
      // The shield: flies in from the mark you clicked.
      const fromPos = from ? [from.left + from.width / 2, from.top + from.height / 2] : [Wpx * 0.1, 60];
      const fromSize = from ? from.width : 26, manSize = tile * 1.6;
      const fly = popEase(Math.max(0, Math.min(1, i / 0.6)));
      const pos = i < 1 ? [fromPos[0] + (sp[0] - fromPos[0]) * fly, fromPos[1] + (sp[1] - fromPos[1]) * fly] : sp;
      const sz = i < 1 ? fromSize + (manSize - fromSize) * fly + manSize * 0.9 * Math.sin(Math.min(Math.PI, Math.max(0, fly * Math.PI))) * 0.35 : manSize;
      const moving = game.state === 'PLAY' && game.shield.dir !== 'NONE';
      const bite = game.sinceBite < 0.18 ? 1 - game.sinceBite / 0.18 : 0;
      const open = i < 1 ? 0 : jaw * (moving ? Math.max(0.15 + 0.85 * Math.abs(Math.sin(clock * 14)), bite) : 0.25);
      const dq = game.state === 'DYING' ? Math.min(1, game.stateTime / 1.2) : 0;
      const fading = game.powerLeft > 0.01 && game.powerLeft <= 2 && Math.floor(clock * 6) % 2 === 0;
      const top = i < 1 ? mix(ACC, TEXT, Math.max(0, Math.min(1, (i - 0.4) / 0.3))) : game.powerLeft > 0 && !fading ? ACC : TEXT;
      man(g, pos[0], pos[1], sz, open, facingLeft, i < 1 ? 0 : jaw, top, TEXT, 1 - dq, dq * 540);

      // HUD (HTML over the canvas).
      const hud = Math.max(0, Math.min(1, (i - 0.8) / 0.2));
      for (const s of ['.sm-top', '.sm-score', '.sm-bottom']) $b(s).style.opacity = hud;
      $b('.sm-bottom').style.top = (oy + mh + 18) + 'px';
      $b('.sm-n').textContent = game.score;
      $b('.sm-lv').textContent = 'LEVEL ' + game.level;
      $b('.sm-best b').textContent = Math.max(best, game.score);
      const lv = Math.max(0, game.lives);
      if ($b('.sm-lives').childElementCount !== lv) $b('.sm-lives').innerHTML = Array.from({ length: lv }, () => miniShield()).join('');
      $b('.sm-count').textContent = `${game.eatenAds} ads · ${game.eatenMalware} malware`;
      const msg = $b('.sm-msg');
      const m = i < 1 ? null : game.state === 'READY' ? ['Ready?', 'Arrow keys or WASD to move. Eat the ads, dodge the malware.']
        : game.state === 'CLEAR' ? ['Clean!', `Level ${game.level + 1} next`] : game.state === 'OVER' ? ['Game over', 'Click or press Enter to play again'] : null;
      msg.classList.toggle('hidden', !m);
      if (m) {
        msg.querySelector('h3').textContent = m[0]; msg.querySelector('p').textContent = m[1];
        const ate = game.state === 'OVER' && game.eatenNames.length ? 'You ate ' + [...new Set(game.eatenNames)].slice(-4).reverse().join(', ') : '';
        msg.querySelector('.sm-ate').textContent = ate;
        msg.querySelector('.sm-new').classList.toggle('hidden', !(game.state === 'OVER' && game.score > 0 && game.score >= best));
      }
    }
    raf = requestAnimationFrame(frame);
  }
  function mix(a, b, t) {
    const p = c => { const m = /#?([0-9a-f]{6})/i.exec(c); const n = m ? parseInt(m[1], 16) : 0; return [n >> 16 & 255, n >> 8 & 255, n & 255]; };
    const x = p(a), y = p(b);
    return '#' + x.map((v, i) => Math.round(v + (y[i] - v) * t).toString(16).padStart(2, '0')).join('');
  }
  function miniShield() {
    return `<svg viewBox="0 0 100 110" width="18" height="20"><clipPath id="smc"><path d="M50 4L92 18V52C92 78 74 97 50 106C26 97 8 78 8 52V18Z"/></clipPath><g clip-path="url(#smc)"><path d="M0 0H100V30Q46 60 0 80Z" fill="var(--accent)"/><path d="M0 88Q52 70 100 45V110H0Z" fill="var(--text)"/></g></svg>`;
  }

  // Seven quick clicks (no more than 1.5 s apart) on a mark open the game; each click wiggles it.
  function secret(elm, ctxFn) {
    let n = 0, lastT = 0;
    elm.style.cursor = 'pointer';
    elm.addEventListener('click', () => {
      const now = Date.now(); n = now - lastT > 1500 ? 1 : n + 1; lastT = now;
      elm.animate([{ transform: `rotate(${n % 2 ? 2 * n : -2 * n}deg)` }, { transform: 'rotate(0)' }], { duration: 420, easing: 'cubic-bezier(.3,1.6,.5,1)' });
      if (n >= 7) { n = 0; open(elm, ctxFn()); }
    });
  }
  return { open, secret, Game, shortDomain };
})();
