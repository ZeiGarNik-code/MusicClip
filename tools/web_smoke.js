/* Smoke test: run web/index.html script under a minimal DOM/WebAudio stub. */
const fs = require("fs");
const vm = require("vm");

const html = fs.readFileSync("web/index.html", "utf8");
const m = html.match(/<script>([\s\S]*)<\/script>/);
if (!m) { console.error("no script"); process.exit(1); }
const script = m[1];

/* auto-proxy: any get → auto, any call → auto (mimics canvas ctx / gradients) */
const auto = new Proxy(function () {}, {
  get(t, p) {
    if (p === Symbol.toPrimitive) return () => 0;
    if (p === "then") return undefined;
    return auto;
  },
  set() { return true; },
  apply() { return auto; }
});

function makeEl(id) {
  const L = {};
  return {
    id, style: {}, textContent: "", value: "80", files: null, hidden: false,
    width: 0, height: 0, children: [],
    appendChild(ch) { this.children.push(ch); },
    remove() {},
    classList: {
      _s: new Set(),
      add(c) { this._s.add(c); },
      remove(c) { this._s.delete(c); },
      toggle(c, f) {
        if (f === undefined) { this._s.has(c) ? this._s.delete(c) : this._s.add(c); }
        else if (f) this._s.add(c); else this._s.delete(c);
      },
      contains(c) { return this._s.has(c); }
    },
    addEventListener(ev, fn) { (L[ev] = L[ev] || []).push(fn); },
    click() { (L.click || []).forEach((f) => f({})); },
    _fire(ev, arg) { (L[ev] || []).forEach((f) => f(arg || {})); },
    getContext() { return auto; }
  };
}

class FakeParam {
  constructor(v) { this.value = v || 0; }
  setValueAtTime() { return this; }
  exponentialRampToValueAtTime() { return this; }
  linearRampToValueAtTime() { return this; }
}
class FakeNode {
  constructor() {
    this.gain = new FakeParam(1); this.frequency = new FakeParam(440);
    this.Q = new FakeParam(1); this.detune = new FakeParam(0);
    this.type = "sine"; this.buffer = null; this._fft = 2048;
  }
  get fftSize() { return this._fft; }
  set fftSize(v) { this._fft = v; }
  get frequencyBinCount() { return this._fft >> 1; }
  get smoothingTimeConstant() { return 0.7; }
  set smoothingTimeConstant(v) {}
  connect() {} disconnect() {} start() {} stop() {}
  getByteFrequencyData(a) { if (a) a.fill(120); }
  getByteTimeDomainData(a) { if (a) a.fill(128); }
}
class FakeAC {
  constructor() { this.currentTime = 0; this.sampleRate = 48000; this.destination = new FakeNode(); }
  resume() { return Promise.resolve(); }
  createGain() { return new FakeNode(); }
  createOscillator() { return new FakeNode(); }
  createBiquadFilter() { return new FakeNode(); }
  createBufferSource() { return new FakeNode(); }
  createBuffer(ch, n) { return { getChannelData: () => new Float32Array(n) }; }
  createAnalyser() { return new FakeNode(); }
  createMediaElementSource() { return new FakeNode(); }
}
class FakeAudio {
  constructor() { this.loop = false; this.crossOrigin = ""; this.paused = true; this.src = ""; }
  play() { this.paused = false; return Promise.resolve(); }
  pause() { this.paused = true; }
  addEventListener() {}
}

const els = {};
let rafCb = null;
const sandbox = {
  document: {
    getElementById(id) { return els[id] || (els[id] = makeEl(id)); },
    createElement(tag) { return makeEl("<" + tag + ">"); },
    documentElement: { requestFullscreen() { return Promise.resolve(); }, outerHTML: "<html></html>" },
    body: makeEl("body"),
    fullscreenElement: null,
    exitFullscreen() {},
    addEventListener() {}
  },
  window: {
    innerWidth: 1280, innerHeight: 720, devicePixelRatio: 1,
    addEventListener() {},
    AudioContext: FakeAC, webkitAudioContext: FakeAC
  },
  localStorage: { getItem() { return null; }, setItem() {} },
  performance: { now: () => Date.now() },
  requestAnimationFrame(cb) { rafCb = cb; },
  setInterval, clearInterval, setTimeout, clearTimeout,
  Audio: FakeAudio,
  URL: { createObjectURL() { return "blob:test"; }, revokeObjectURL() {} },
  Blob: class Blob { constructor(parts, opts) { this.parts = parts; this.type = opts && opts.type; } },
  location: { href: "https://example.test/MusicClip/" },
  fetch() { return Promise.resolve({ text: () => Promise.resolve("<!DOCTYPE html><html></html>") }); },
  console
};
vm.createContext(sandbox);

function step(n) {
  for (let i = 0; i < n; i++) {
    if (!rafCb) throw new Error("no frame scheduled");
    const cb = rafCb; rafCb = null;
    cb(Date.now() + i * 16);
  }
}

async function main() {
  vm.runInContext(script, sandbox, { filename: "index.html<script>" });
  els.fxPop.hidden = true;   // mirror the HTML [hidden] attribute

  // 1. start screen click → begins sample music + first frames
  els.start.click();
  step(5);

  // 2. walk through ALL effects with frames between
  const E = vm.runInContext("Effects", sandbox);
  for (let i = 0; i < E.list.length; i++) {
    E.switchTo(i);
    step(4);
    if (els.fxName.textContent !== E.cur().name) throw new Error("fxName mismatch @ " + i);
  }
  if (E.list.length < 20) throw new Error("expected >=20 effects, got " + E.list.length);
  if (els.fxPop.children.length !== E.list.length) throw new Error("fxPop missing entries");

  // 2b. effect picker popup: open, pick #3, close
  els.fxName.click();
  if (els.fxPop.hidden) throw new Error("fxPop did not open");
  els.fxPop.children[3].click();
  if (E.idx !== 3 || !els.fxPop.hidden) throw new Error("fxPop pick failed");
  step(3);

  // 2c. offline download button
  els.btnDl.click();
  await new Promise((r) => setTimeout(r, 20));

  // 3. buttons
  els.btnNext.click(); step(2);
  els.btnPrev.click(); step(2);
  els.btnAuto.click(); step(30);          // auto-cycle tick (dt via real time… small)
  els.btnPlay.click();  step(2);           // pause
  els.btnPlay.click();  step(2);           // resume
  els.vol._fire("input", { target: { value: "40" } });
  els.btnHide.click(); els.btnHide.click();
  els.btnFs.click();

  // 4. file mode
  els.fileInput._fire("change", { target: { files: [{ name: "test.mp3" }] } });
  step(4);
  const mode = vm.runInContext("AudioSys.mode", sandbox);
  if (mode !== "file") throw new Error("file mode not active: " + mode);
  if (!String(els.trackName.textContent).includes("test.mp3")) throw new Error("track name not set");
  els.btnSample.click();                   // back to sample
  step(4);

  // 5. many frames to exercise decay paths + beats (FakeAnalyser always "loud")
  step(120);

  // sanity: beat env moved at some point
  const env = vm.runInContext("Beat.env", sandbox);
  const idx = vm.runInContext("Effects.idx", sandbox);
  if (idx < 0 || idx >= E.list.length) throw new Error("bad idx");

  vm.runInContext("AudioSys.stopSample()", sandbox);
  console.log("SMOKE OK — effects:", E.list.length, "| last env:", env.toFixed(3), "| fx idx:", idx);
  process.exit(0);
}

main().catch((e) => {
  console.error("SMOKE FAIL:", (e && e.stack) || e);
  try { vm.runInContext("AudioSys.stopSample()", sandbox); } catch (_) {}
  process.exit(1);
});
