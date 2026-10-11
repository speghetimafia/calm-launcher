#!/usr/bin/env node
// Bakes the approved mockup (../../mockup/index.html) into device-exact assets for the launcher:
//   app/src/main/assets/ui/*.png   every graphic, per state and theme, rendered by Chrome at 1:1 (mdpi)
//   app/src/main/assets/ui/layout.json   where each image goes + text styles measured from the mockup
// The stereo's WebView (v74) cannot render the mockup, so the app composes these natively.
// Run: node tools/bake.mjs   (needs Google Chrome; re-run after every mockup change)
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';

const ROOT = path.resolve(import.meta.dirname, '../..');
const MOCK = path.join(ROOT, 'mockup/index.html');
const APP = path.join(ROOT, 'launcher/app/src/main');
const OUT = path.join(APP, 'assets/ui');
const CHROME = process.env.CHROME ?? ['/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', '/usr/bin/google-chrome', '/usr/bin/chromium'].find(p => fs.existsSync(p));
if (!CHROME) throw new Error('Chrome not found: set CHROME=/path/to/chrome');
const PORT = 9337;
const THEMES = ['light', 'dark', 'night'];

fs.rmSync(OUT, { recursive: true, force: true });
fs.mkdirSync(OUT, { recursive: true });

const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'bake-'));
const chrome = spawn(CHROME, ['--headless=new', `--remote-debugging-port=${PORT}`, `--user-data-dir=${profile}`,
  '--hide-scrollbars', '--force-device-scale-factor=1', '--allow-file-access-from-files', 'about:blank'], { stdio: 'ignore' });
process.on('exit', () => { try { chrome.kill('SIGKILL'); } catch {} try { fs.rmSync(profile, { recursive: true, force: true, maxRetries: 5 }); } catch {} });

const sleep = ms => new Promise(r => setTimeout(r, ms));
async function json(url) {
  for (let i = 0; i < 100; i++) { try { return await (await fetch(url)).json(); } catch { await sleep(100); } }
  throw new Error('Chrome did not start');
}
const page = (await json(`http://127.0.0.1:${PORT}/json/list`)).find(t => t.type === 'page');
const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise(r => (ws.onopen = r));
let seq = 0;
const pending = new Map();
ws.onmessage = e => {
  const m = JSON.parse(e.data);
  if (!m.id || !pending.has(m.id)) return;
  const { res, rej } = pending.get(m.id); pending.delete(m.id);
  m.error ? rej(new Error(m.error.message)) : res(m.result);
};
const send = (method, params = {}) => new Promise((res, rej) => {
  const id = ++seq; pending.set(id, { res, rej }); ws.send(JSON.stringify({ id, method, params }));
});
async function js(expr) {
  const r = await send('Runtime.evaluate', { expression: `{\n${expr}\n}`, awaitPromise: true, returnByValue: true });
  if (r.exceptionDetails) throw new Error(`${r.exceptionDetails.text} ${r.exceptionDetails.exception?.description ?? ''}\n  in: ${expr.slice(0, 200)}`);
  return r.result.value;
}

await send('Page.enable');
await send('Emulation.setDeviceMetricsOverride', { width: 1200, height: 800, deviceScaleFactor: 1, mobile: false });
await send('Emulation.setDefaultBackgroundColorOverride', { color: { r: 0, g: 0, b: 0, a: 0 } });
await send('Page.navigate', { url: 'file://' + MOCK });
for (let i = 0; i < 100 && (await js('document.readyState').catch(() => '')) !== 'complete'; i++) await sleep(100);

// ── put the device alone at (0,0), freeze time, add the isolation helpers ──
const ok = await js(`(async () => {
  const sprite = document.querySelector('body > svg');
  document.body.replaceChildren(sprite, dev);
  document.documentElement.style.background = 'transparent';
  document.body.style.cssText = 'margin:0;background:transparent';
  dev.style.cssText += ';position:absolute;left:0;top:0;margin:0';
  for (let i = 1; i < 5000; i++) { clearInterval(i); clearTimeout(i); }
  const st = document.createElement('style');
  st.textContent = \`
    *,*::before,*::after{animation-play-state:paused!important;animation-delay:0s!important;transition:none!important}
    #dev.solo{background:transparent!important;box-shadow:none!important}
    #dev.solo::before{display:none!important}
    #dev.solo, #dev.solo *{visibility:hidden!important}
    #dev.solo [data-bake], #dev.solo [data-bake] *{visibility:visible!important}
    #dev [data-hide], #dev [data-hide] *, #dev.solo [data-bake] [data-hide], #dev.solo [data-bake] [data-hide] *{visibility:hidden!important}\`;
  document.head.append(st);
  const extra = document.createElement('style'); extra.id = 'extra'; document.head.append(extra);
  window.B = {
    q: s => typeof s === 'string' ? document.querySelector(s) : s,
    rect(s) { const r = B.q(s).getBoundingClientRect(); return { x: r.left, y: r.top, w: r.width, h: r.height }; },
    reset() {
      dev.classList.remove('solo');
      document.querySelectorAll('[data-bake]').forEach(e => e.removeAttribute('data-bake'));
      document.querySelectorAll('[data-hide]').forEach(e => e.removeAttribute('data-hide'));
      document.getElementById('extra').textContent = '';
    },
    hide(list) { list.forEach(h => document.querySelectorAll(h).forEach(e => e.setAttribute('data-hide', ''))); },
    solo(s, hides = [], css = '') { B.reset(); dev.classList.add('solo'); B.q(s).setAttribute('data-bake', ''); B.hide(hides); document.getElementById('extra').textContent = css; },
    only(hides, css = '') { B.reset(); B.hide(hides); document.getElementById('extra').textContent = css; },
    theme(t) { prefs.theme = t === 'light' ? 'auto' : 'dark'; S.night = t === 'night'; render(); },
    base() {
      Object.assign(S, { screen: 'home', sheet: null, bt: 'Galaxy S23', source: 'bt', track: 'paused', usb: 'none',
        vol: 20, volFlash: false, call: null, reverse: false, sleep: false, peek: false, radioOn: false });
      prefs.accent = ACCENTS[0][1];
    },
    // text style + where the baseline really is, straight from Chrome's layout
    spec(s) {
      const el = B.q(s); const cs = getComputedStyle(el); const r = el.getBoundingClientRect();
      const probe = document.createElement('span');
      probe.style.cssText = 'display:inline-block;width:0;height:0;vertical-align:baseline;';
      el.insertBefore(probe, el.firstChild); const base = probe.getBoundingClientRect().top; probe.remove();   // first line's baseline
      const fs = parseFloat(cs.fontSize);
      return { x: r.left, y: r.top, w: r.width, h: r.height, baseline: base, size: fs,
        ls: cs.letterSpacing === 'normal' ? 0 : parseFloat(cs.letterSpacing) / fs,
        color: cs.color, upper: cs.textTransform === 'uppercase', align: cs.textAlign,
        lineHeight: cs.lineHeight === 'normal' ? null : parseFloat(cs.lineHeight),
        shadow: cs.textShadow, mono: /JBMono/.test(cs.fontFamily), bg: cs.backgroundColor,
        padL: parseFloat(cs.paddingLeft), padT: parseFloat(cs.paddingTop), opacity: parseFloat(cs.opacity) };
    },
    vars(names) { const cs = getComputedStyle(dev); return Object.fromEntries(names.map(n => [n, cs.getPropertyValue(n).trim()])); },
  };
  await document.fonts.ready;
  return document.fonts.check('20px Heros') && document.fonts.check('20px JBMono');
})()`);
if (!ok) throw new Error('device fonts did not load (mockup/fonts/*)');

const L = { size: [1024, 600], img: {}, rect: {}, text: {}, vars: {}, accents: [], meta: {} };
let files = 0;
const R = r => ({ x: Math.floor(r.x), y: Math.floor(r.y), w: Math.ceil(r.x + r.w) - Math.floor(r.x), h: Math.ceil(r.y + r.h) - Math.floor(r.y) });
const grow = (r, m) => R({ x: r.x - m.l, y: r.y - m.t, w: r.w + m.l + m.r, h: r.h + m.t + m.b });
const M = n => ({ l: n, t: n, r: n, b: n });
const PADM = { l: 14, t: 8, r: 14, b: 26 };

async function shot(file, clip, scale = 1) {
  const r = await send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true,
    clip: { x: clip.x, y: clip.y, width: clip.w, height: clip.h, scale } });
  fs.writeFileSync(path.join(OUT, file + '.png'), Buffer.from(r.data, 'base64'));
  files++;
}
// one image name, same place in every theme; file gets @theme when it differs per theme
async function bake(name, clip, theme = null, scale = 1) {
  L.img[name] = { ...clip, scale };
  await shot(theme ? `${name}@${theme}` : name, clip, scale);
}
const rectOf = async s => js(`B.rect(${JSON.stringify(s)})`);

L.accents = await js('ACCENTS.map(a => ({ name: a[0], hex: a[1], short: a[2] }))');

// ════════════ HOME ════════════
const PADS = [
  { key: 'prev', sel: '#kPrev', variants: { prev: ['#kPrevL', 'Prev'], seek: ['#kPrevL', 'Seek −'] } },
  { key: 'next', sel: '#kNext', variants: { next: ['#kNextL', 'Next'], seek: ['#kNextL', 'Seek +'] } },
  { key: 'bt', sel: '[data-src=bt]' }, { key: 'radio', sel: '[data-src=radio]' }, { key: 'usb', sel: '[data-src=usb]' },
  { key: 'phone', sel: '#kPhone', hide: ['#phSub'] }, { key: 'switch', sel: '#kSwitch' },
  { key: 'auto', sel: '#kAuto', hide: ['#aaSub'] }, { key: 'more', sel: '#kMore' },
];

for (const t of THEMES) {
  await js(`B.base(); B.theme('${t}')`);
  L.vars[t] = await js(`B.vars(['--ink','--ink2','--ink3','--body'])`);

  // background: everything that never changes
  await js(`B.only(['.tabs', '#time', '#ampm', '.plate-div', '.plate-r', '.lcd > *', '.ctrls .pad', '#kticks', '.knob-chassis', '.kval'])`);
  await bake('bg', { x: 0, y: 0, w: 1024, h: 600 }, t);

  // pads
  for (const p of PADS) {
    const variants = p.variants ?? { base: null };
    for (const [v, lab] of Object.entries(variants)) {
      for (const st of ['up', 'down']) {
        await js(`B.base(); B.theme('${t}');
          ${lab ? `B.q('${lab[0]}').textContent = ${JSON.stringify(lab[1])};` : ''}
          const e = B.q('${p.sel}'); e.classList.remove('on', 'dim'); ${st === 'down' ? "e.classList.add('on');" : ''}
          B.solo(e, ['.led', ...${JSON.stringify(p.hide ?? [])}].map(h => '${p.sel} ' + h))`);
        const r = R(await rectOf(p.sel));
        if (st === 'up') L.rect['pad_' + p.key] = r;
        await bake(`pad_${p.key}${v === 'base' ? '' : '_' + v}_${st}`, grow(r, PADM), t);
      }
    }
    const hasLed = await js(`!!B.q('${p.sel} .led')`);
    if (hasLed) L.rect['led_' + p.key] = await rectOf(`${p.sel} .led`);
  }

  // Play pad depends on the accent (dark == light, night darkens the accent)
  if (t !== 'dark') for (const a of L.accents) for (const [lab, icon] of [['Play', '#i-play'], ['Pause', '#i-pause']]) {
    for (const st of ['up', 'down']) {
      await js(`B.base(); prefs.accent = '${a.hex}'; B.theme('${t}');
        B.q('#kPlayL').textContent = '${lab}'; B.q('#kPlayI').setAttribute('href', '${icon}');
        const e = B.q('#kPlay'); e.classList.remove('on', 'dim'); ${st === 'down' ? "e.classList.add('on');" : ''}
        B.solo(e)`);
      const r = R(await rectOf('#kPlay'));
      L.rect.pad_play = r;
      await bake(`pad_play_${lab.toLowerCase()}_${st}_${a.hex.slice(1)}`, grow(r, PADM), t);
    }
  }

  // text measured per theme (colours differ)
  await js(`B.base(); B.theme('${t}')`);
  const T = {};
  for (const [k, s] of Object.entries({ time: '#time', ampm: '#ampm', wday: '#wday', date: '#date',
    srcTag: '#srcTag', srcDev: '#srcDev', title: '#npT', artist: '#npA', roTag: '#roTag', roVal: '#roVal',
    roGhost: '#roGhost', roBot: '#roBot', phSub: '#phSub', aaSub: '#aaSub', kvNum: '#kv', kval: '.kval',
    tabO: '#tabDev', tabK: '#tabUsb', tabG: '#tabVol' })) T[k] = await js(`B.spec('${s}')`);
  await js(`S.bt = null; S.track = 'none'; render()`);
  T.titleEmpty = await js(`B.spec('#npT')`); T.artistEmpty = await js(`B.spec('#npA')`);
  L.meta.metaEmpty = await rectOf('.meta');
  await js(`S.bt = null; render()`);
  T.tabOff = await js(`B.spec('#tabDev')`);
  L.text[t] = T;
}

// geometry that is the same in every theme
await js(`B.base(); B.theme('light')`);
Object.assign(L.rect, {
  tabs: await rectOf('.tabs'), plate: await rectOf('.plate'), plateDiv: await rectOf('.plate-div'),
  lcd: await rectOf('#lcd'), deck: await rectOf('.deck-module'), ro: await rectOf('.ro'),
  knobwell: await rectOf('#knob'), kticks: await rectOf('#kticks'), chassis: await rectOf('.knob-chassis'),
  kval: await rectOf('.kval'), knobI: await rectOf('.knob i'),
  stPlay: await rectOf('#stPlay'), stPause: await rectOf('#stPause'), stRec: await rectOf('#stRec'),
});
L.meta.metaTape = await rectOf('.meta');
L.meta.tagrow = await rectOf('.tagrow');
L.meta.gap = await js(`parseFloat(getComputedStyle(B.q('.meta')).rowGap) || 6`);
await js(`S.source = 'radio'; render()`);
L.meta.metaRadio = await rectOf('.meta');
L.meta.scale = await rectOf('#scale');
L.meta.plateGaps = await js(`(() => { const a = B.rect('#ampm'), d = B.rect('.plate-div'), p = B.rect('.plate-r'), t = B.rect('#time');
  return { timeToAmpm: a.x - (t.x + t.w), ampmToDiv: d.x - (a.x + a.w), divToText: p.x - (d.x + d.w) }; })()`);
L.meta.tabsGap = 6;

// status icons, LEDs (theme independent)
await js(`B.base(); B.theme('light')`);
for (const id of ['stPlay', 'stPause', 'stRec']) for (const on of [false, true]) {
  await js(`const e = B.q('#${id}'); e.classList.toggle('on', ${on}); B.solo(e)`);
  await bake(`${id}_${on ? 'on' : 'off'}`, grow(R(await rectOf('#' + id)), M(10)));
}
for (const [name, sel] of [['led_g', '[data-src=bt] .led'], ['led_k', '#phLed']]) for (const on of [false, true]) {
  await js(`B.base(); render(); const e = B.q('${sel}'); e.className = 'led${on ? ' on' : ''}'; B.q('[data-src=bt]').classList.remove('on'); B.solo(e)`);
  await bake(`${name}_${on ? 'on' : 'off'}`, grow(R(await rectOf(sel)), M(14)));
}

// tape deck: static body, then one spool at 2x for smooth rotation
await js(`B.base(); render(); B.solo('.deck-module', ['.deck-module .reel', '.deck-bars', '.head-glow', '.head-glow + circle'])`);
await bake('deck', grow(R(await rectOf('.deck-module')), M(14)));
L.meta.reels = await js(`[...document.querySelectorAll('.deck-module .reel')].map(g => { const r = g.getBoundingClientRect(); return { cx: r.left + r.width / 2, cy: r.top + r.height / 2 }; })`);
L.meta.bars = await js(`[...document.querySelectorAll('.deck-bars .eq-bar')].map(b => ({ x: +b.getAttribute('x'), y: +b.getAttribute('y'), w: +b.getAttribute('width'), h: +b.getAttribute('height'), rx: +b.getAttribute('rx') }))`);
L.meta.headGlow = await js(`(() => { const c = B.q('.head-glow'); return { cx: +c.getAttribute('cx'), cy: +c.getAttribute('cy'), r: +c.getAttribute('r') }; })()`);
await js(`B.base(); render(); B.q('.deck-module').style.filter = 'none'; B.solo(document.querySelector('.deck-module .reel'))`);
const reel = L.meta.reels[0];
await bake('spool', R({ x: reel.cx - 30, y: reel.cy - 30, w: 60, h: 60 }), null, 2);
await js(`B.q('.deck-module').style.filter = ''`);

// knob: fixed shadow ring, rotating billet cap (2x), fixed lighting on top
await js(`B.base(); render()`);
const kclip = grow(R(await rectOf('.knob-chassis')), M(46));
await js(`B.solo('.knob-chassis', ['.knob'], '.knob-chassis::after{display:none!important}')`);
await bake('knob_back', kclip);
await js(`B.solo('.knob', ['.knob i'])`);
await bake('knob_rot', kclip, null, 2);
await js(`B.solo('.knob-chassis', ['.knob'], '.knob-chassis{box-shadow:none!important}.knob-chassis::before{display:none!important}')`);
await bake('knob_front', kclip);

// ════════════ SHEETS ════════════
for (const t of THEMES) {
  await js(`B.base(); S.sheet = 'more'; B.theme('${t}'); B.only(['#moreGrid .mi', '#more .done'])`);
  await bake('more_bg', { x: 0, y: 0, w: 1024, h: 600 }, t);
  const n = await js(`document.querySelectorAll('#moreGrid .mi').length`);
  for (let i = 0; i < n; i++) for (const st of ['up', 'down']) {
    const sel = `#moreGrid .mi[data-i="${i}"]`;
    await js(`B.base(); S.sheet = 'more'; B.theme('${t}'); const e = B.q('${sel}'); ${st === 'down' ? "e.classList.add('on');" : ''} B.solo(e)`);
    const r = R(await rectOf(sel)); L.rect['mi_' + i] = r;
    await bake(`mi_${i}_${st}`, grow(r, PADM), t);
  }
  for (const sheet of ['more', 'settings']) for (const st of ['up', 'down']) {
    await js(`B.base(); S.sheet = '${sheet}'; B.theme('${t}'); const e = B.q('#${sheet} .done'); ${st === 'down' ? "e.classList.add('on');" : ''} B.solo(e)`);
    const r = R(await rectOf(`#${sheet} .done`)); L.rect['done_' + sheet] = r;
    await bake(`done_${sheet}_${st}`, grow(r, PADM), t);
  }

  await js(`B.base(); S.sheet = 'settings'; B.theme('${t}'); B.only(['#setList .opt', '#settings .done'])`);
  await bake('set_bg', { x: 0, y: 0, w: 1024, h: 600 }, t);
  const opts = await js(`[...document.querySelectorAll('#setList .opt')].map(e => ({ r: +e.dataset.r, o: +e.dataset.o }))`);
  for (const { r: ri, o: oi } of opts) for (const st of ['up', 'down']) {
    const sel = `#setList .opt[data-r="${ri}"][data-o="${oi}"]`;
    await js(`B.base(); S.sheet = 'settings'; B.theme('${t}'); const e = B.q('${sel}'); e.classList.remove('on'); ${st === 'down' ? "e.classList.add('on');" : ''}
      B.solo(e, ['${sel} .led'])`);
    const r = R(await rectOf(sel)); L.rect[`opt_${ri}_${oi}`] = r;
    L.rect[`optled_${ri}_${oi}`] = await rectOf(`${sel} .led`);
    await bake(`opt_${ri}_${oi}_${st}`, grow(r, PADM), t);
  }
}
L.meta.settings = await js(`SETTINGS.map(r => ({ key: r.k, color: !!r.color, options: r.o.map(o => o[0]) }))`);
L.meta.more = await js(`MORE.map(m => ({ name: m.n, component: m.c ?? null, special: m.special ?? null }))`);
for (const on of [false, true]) {
  const sel = '#setList .opt[data-r="0"][data-o="0"] .led';
  await js(`B.base(); S.sheet = 'settings'; B.theme('light'); const e = B.q('${sel}'); e.className = 'led${on ? ' on' : ''}'; B.solo(e)`);
  await bake(`led_opt_${on ? 'on' : 'off'}`, grow(R(await rectOf(sel)), M(14)));
}

// launcher icon: the orange Play key
await js(`B.base(); B.theme('light'); B.solo('#kPlay')`);
await shot('../../../../../icon-src', grow(R(await rectOf('#kPlay')), M(4)), 2);

fs.writeFileSync(path.join(OUT, 'layout.json'), JSON.stringify(L));

// No sounds: on this unit any Android audio makes NwdAudioService pause Bluetooth music (see README).

console.log(`baked ${files} images + layout.json`);
ws.close();
process.exit(0);
