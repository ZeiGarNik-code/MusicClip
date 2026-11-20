#!/usr/bin/env python3
"""MusicClip Visualizer - built-in demo track generator.

Synthesizes three royalty-free tracks from scratch (pure DSP, no samples,
no third-party music), so the APK ships with "nhac co san" (built-in music)
whose license is 100% clean (CC0 / MIT, same as the project).

Output: app/src/main/assets/music/*.wav  (mono, 16-bit PCM, 44.1 kHz)

Usage:
    python3 tools/make_music.py
"""
import os
import sys
import wave

import numpy as np
from scipy.signal import butter, fftconvolve, lfilter

SR = 44100
OUT_DIR = os.path.normpath(
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "assets", "music")
)
RNG = np.random.default_rng(20261120)  # deterministic output


# ----------------------------------------------------------------- primitives
def midi_hz(n):
    return 440.0 * 2.0 ** ((n - 69) / 12.0)


def _phase(freq, n):
    if np.isscalar(freq):
        freq = np.full(n, float(freq))
    return 2.0 * np.pi * np.cumsum(freq) / SR


def sine(freq, n):
    return np.sin(_phase(freq, n))


def saw(freq, n):
    return 2.0 * ((_phase(freq, n) / (2 * np.pi)) % 1.0) - 1.0


def square(freq, n, duty=0.5):
    p = ((_phase(freq, n) / (2 * np.pi)) % 1.0)
    return np.where(p < duty, 1.0, -1.0)


def tri(freq, n):
    p = ((_phase(freq, n) / (2 * np.pi)) % 1.0)
    return 4.0 * np.abs(p - 0.5) - 1.0


def noise(n):
    return RNG.standard_normal(n)


def adsr(n, a=0.01, d=0.1, s=0.7, r=0.2):
    """Amplitude envelope of exactly n samples."""
    a_n = max(1, int(a * SR))
    d_n = max(1, int(d * SR))
    r_n = max(1, int(r * SR))
    s_n = max(0, n - a_n - d_n - r_n)
    if a_n + d_n + r_n > n:  # very short note: squeeze phases
        scale = n / float(a_n + d_n + r_n)
        a_n = max(1, int(a_n * scale))
        d_n = max(1, int(d_n * scale))
        r_n = max(1, n - a_n - d_n)
        s_n = 0
    env = np.concatenate([
        np.linspace(0.0, 1.0, a_n, endpoint=False),
        np.linspace(1.0, s, d_n, endpoint=False),
        np.full(s_n, s),
        np.linspace(s, 0.0, r_n),
    ])
    if len(env) < n:
        env = np.pad(env, (0, n - len(env)))
    return env[:n]


def exp_env(n, tau):
    return np.exp(-np.arange(n) / (tau * SR + 1e-9))


def lp(x, fc, order=2):
    fc = min(max(fc, 20.0), SR / 2 * 0.98)
    b, a = butter(order, fc / (SR / 2), btype="low")
    return lfilter(b, a, x)


def hp(x, fc, order=2):
    fc = min(max(fc, 20.0), SR / 2 * 0.98)
    b, a = butter(order, fc / (SR / 2), btype="high")
    return lfilter(b, a, x)


def bp(x, f1, f2, order=2):
    b, a = butter(order, [f1 / (SR / 2), min(f2, SR / 2 * 0.98) / (SR / 2)], btype="band")
    return lfilter(b, a, x)


def reverb(x, seconds=1.8, mix=0.35, damp=5000.0):
    n = int(seconds * SR)
    ir = noise(n) * np.exp(-np.arange(n) / (0.4 * SR))
    ir = lp(ir, damp)
    ir /= (np.abs(ir).max() + 1e-9)
    wet = fftconvolve(x, ir)[: len(x)]
    wet /= (np.abs(wet).max() + 1e-9) * (np.abs(x).max() + 1e-9) + 1e-9
    wet *= np.abs(x).max() + 1e-9
    return (1.0 - mix) * x + mix * wet


def delay(x, time_s=0.28, fb=0.35, mix=0.3, taps=5):
    d = max(1, int(time_s * SR))
    xp = np.concatenate([x, np.zeros(d * taps + 1)])  # room for echoes
    out = xp.copy()
    tail = xp
    gain = mix
    for _ in range(taps):
        tail = np.concatenate([np.zeros(d), tail[:-d]]) * fb
        out += tail * gain
        gain *= 0.85
    return out


def add(buf, start_sample, sig, gain=1.0):
    end = start_sample + len(sig)
    if end > len(buf):
        sig = sig[: len(buf) - start_sample]
        end = len(buf)
    if start_sample < 0 or len(sig) <= 0:
        return
    buf[start_sample:end] += sig * gain


# ------------------------------------------------------------------- voices
def kick(vel=1.0, punch=1.0):
    n = int(0.34 * SR)
    t = np.arange(n) / SR
    f = 46.0 + 110.0 * np.exp(-t / 0.03)
    body = np.sin(2 * np.pi * np.cumsum(f) / SR) * exp_env(n, 0.10)
    click = noise(n) * exp_env(n, 0.004) * 0.35
    click = hp(click, 1500)
    out = body + click * punch
    out *= adsr(n, 0.001, 0.05, 0.6, 0.25)
    return out * vel * 0.95


def snare(vel=1.0):
    n = int(0.22 * SR)
    tone = np.sin(2 * np.pi * 185.0 * np.arange(n) / SR) * exp_env(n, 0.05) * 0.5
    hiss = bp(noise(n), 1200, 8500) * exp_env(n, 0.075)
    out = tone + hiss
    return out * vel * 0.8


def clap(vel=1.0):
    n = int(0.3 * SR)
    out = np.zeros(n)
    for i, off in enumerate((0.0, 0.014, 0.03)):
        s = int(off * SR)
        seg = bp(noise(n - s), 900, 5200) * exp_env(n - s, 0.02 if i < 2 else 0.11)
        out[s:] += seg * (1.0 - 0.15 * i)
    return out * vel * 0.7


def hat(open_=False, vel=1.0):
    n = int((0.20 if open_ else 0.05) * SR)
    out = hp(noise(n), 7500) * exp_env(n, 0.11 if open_ else 0.014)
    return out * vel * 0.4


def shaker(vel=1.0):
    n = int(0.07 * SR)
    out = hp(noise(n), 6000) * adsr(n, 0.005, 0.02, 0.4, 0.04)
    return out * vel * 0.35


def bass_note(midi, dur_s, vel=1.0, cutoff=420.0, wave_mix=0.85, sub=0.6):
    n = int(dur_s * SR)
    f = midi_hz(midi)
    osc = saw(f, n) * wave_mix + square(f, n) * (1 - wave_mix) * 0.5
    osc += sine(f / 2.0, n) * sub
    env = adsr(n, 0.004, 0.08, 0.75, min(0.12, dur_s * 0.4))
    out = lp(osc * env, cutoff)
    return out * vel


def pad_chord(midis, dur_s, vel=1.0, cutoff=1600.0, attack=0.4, detune=7.0):
    n = int(dur_s * SR)
    acc = np.zeros(n)
    for m in midis:
        f = midi_hz(m)
        for cents in (-detune, 0.0, detune):
            acc += saw(f * 2 ** (cents / 1200.0), n)
        acc += sine(f, n) * 0.5
    acc /= (len(midis) * 3.5)
    env = adsr(n, attack, 0.6, 0.8, min(1.0, dur_s * 0.45))
    out = lp(acc * env, cutoff)
    return out * vel


def pluck(midi, dur_s, vel=1.0, cutoff=2600.0, wave_mix=0.6):
    n = int(dur_s * SR)
    f = midi_hz(midi)
    osc = saw(f, n) * wave_mix + square(f, n) * (1 - wave_mix)
    env = exp_env(n, 0.09) * adsr(n, 0.002, 0.05, 0.5, 0.1)
    out = lp(osc * env, cutoff)
    return out * vel


def bell(midi, dur_s, vel=1.0, ratio=3.5, index=6.0):
    n = int(dur_s * SR)
    f = midi_hz(midi)
    t = np.arange(n) / SR
    mod = np.sin(2 * np.pi * f * ratio * t) * index * np.exp(-t / 0.35)
    out = np.sin(2 * np.pi * f * t + mod) * exp_env(n, 0.55)
    return out * vel


def lead_note(midi, dur_s, vel=1.0, cutoff=4000.0):
    n = int(dur_s * SR)
    f = midi_hz(midi)
    osc = saw(f, n) * 0.6 + saw(f * 2 ** (6 / 1200.0), n) * 0.4
    env = adsr(n, 0.01, 0.1, 0.7, min(0.2, dur_s * 0.5))
    return lp(osc * env, cutoff) * vel


# ---------------------------------------------------------------- sequencer
class Track:
    def __init__(self, bpm, bars, title):
        self.bpm = bpm
        self.bars = bars
        self.title = title
        self.beats_per_bar = 4
        self.total_beats = bars * self.beats_per_bar
        self.total_n = int(self.total_beats * 60.0 / bpm * SR)
        self.buf = np.zeros(self.total_n + SR)  # tail room for releases
        self.sends = {"drums": np.zeros_like(self.buf), "music": np.zeros_like(self.buf)}

    def t2n(self, beats):
        return int(beats * 60.0 / self.bpm * SR)

    def put(self, beat, sig, bus="music", gain=1.0):
        add(self.buf, self.t2n(beat), sig, gain)
        if bus in self.sends:
            add(self.sends[bus], self.t2n(beat), sig, gain)


# ------------------------------------------------------------- track recipes
def recipe_neon_skyline():
    """Synthwave, 100 BPM, A minor - pads + arp + driving bass."""
    bpm, bars = 100, 16
    tr = Track(bpm, bars, "Neon Skyline")
    prog = [
        [45, 57, 60, 64],   # Am  (A2 root)
        [41, 53, 57, 60],   # F
        [48, 55, 60, 64],   # C
        [43, 55, 59, 62],   # G
    ]
    roots = [33, 29, 36, 31]  # bass roots
    arp_idx = [0, 1, 2, 3, 2, 1, 2, 3]
    for bar in range(bars):
        chord = prog[bar % 4]
        root = roots[bar % 4]
        b0 = bar * 4
        # pad
        tr.put(b0, pad_chord(chord, 4 * 60 / bpm, vel=0.34, cutoff=1500, attack=0.35))
        # bass eighths
        for e in range(8):
            beat = b0 + e * 0.5
            note = root if e % 4 != 3 else root + 7
            tr.put(beat, bass_note(note, 0.45 * 60 / bpm, vel=0.85, cutoff=380 + 60 * (e % 2)))
        # arp sixteenths (every second 8th subdivided -> use 8ths for clarity)
        for e in range(8):
            beat = b0 + e * 0.5
            ci = arp_idx[e]
            note = chord[ci] + 12
            pl = pluck(note, 0.24, vel=0.32, cutoff=3200)
            tr.put(beat, delay(pl, 0.3, fb=0.32, mix=0.28), gain=1.0)
        # drums
        for beat in range(4):
            tr.put(b0 + beat, kick(1.0), bus="drums")
            if beat in (1, 3):
                tr.put(b0 + beat, snare(0.8), bus="drums")
        for e in range(8):
            op = 7 if e == 7 else 0
            if op:
                tr.put(b0 + e * 0.5, hat(True, 0.7), bus="drums")
            else:
                tr.put(b0 + e * 0.5, hat(False, 0.55 if e % 2 == 0 else 0.4), bus="drums")
        # open hat pickup at bar end
        if bar % 4 == 3:
            tr.put(b0 + 3.5, hat(True, 0.85), bus="drums")
    return tr


def recipe_pulse_drive():
    """House, 124 BPM, F minor - four-on-floor + offbeat bass + stabs."""
    bpm, bars = 124, 16
    tr = Track(bpm, bars, "Pulse Drive")
    prog = [
        [53, 56, 60],       # Fm
        [49, 53, 56],       # Db
        [51, 55, 58],       # Ab (voiced)
        [51, 55, 58],       # Ab
    ]
    # nicer: Fm - Db - Ab - Eb
    prog = [
        [53, 56, 60],
        [49, 53, 56],
        [56, 60, 63],
        [51, 55, 58],
    ]
    roots = [29, 25, 32, 27]
    for bar in range(bars):
        chord = prog[bar % 4]
        root = roots[bar % 4]
        b0 = bar * 4
        # four-on-floor
        for beat in range(4):
            tr.put(b0 + beat, kick(1.0), bus="drums")
        # clap on 2 & 4
        for beat in (1, 3):
            tr.put(b0 + beat, clap(0.9), bus="drums")
        # offbeat open hats
        for e in range(4):
            tr.put(b0 + e + 0.5, hat(True, 0.55), bus="drums")
        # closed 16ths ghost
        for s in range(16):
            if s % 2 == 1:
                tr.put(b0 + s * 0.25, hat(False, 0.3), bus="drums")
        # offbeat bass
        for e in range(4):
            tr.put(b0 + e + 0.5, bass_note(root, 0.32, vel=0.9, cutoff=350, wave_mix=0.7, sub=0.7))
        # chord stabs on the & of 1 and & of 3
        for e in (0.75, 2.75):
            tr.put(b0 + e, pluck(chord[0], 0.2, vel=0.5, cutoff=2800)
                   + pluck(chord[1], 0.2, vel=0.4, cutoff=2800)
                   + pluck(chord[2], 0.2, vel=0.4, cutoff=2800))
        # plucky 16th arp w/ delay
        for s in range(16):
            note = chord[s % 3] + 12 if s % 4 != 3 else chord[(s + 1) % 3] + 12
            pl = pluck(note, 0.14, vel=0.22, cutoff=4200)
            tr.put(b0 + s * 0.25, delay(pl, 0.75 * 60 / bpm, fb=0.3, mix=0.25))
        # section fills
        if bar % 4 == 3:
            for s in (3.0, 3.25, 3.5, 3.75):
                tr.put(b0 + s, snare(0.35 + 0.15 * (s - 3.0)), bus="drums")
    return tr


def recipe_quiet_orbit():
    """Ambient, 70 BPM, D major colour - pads, bells, soft pulse."""
    bpm, bars = 70, 12
    tr = Track(bpm, bars, "Quiet Orbit")
    prog = [
        [50, 57, 61, 66],   # Dmaj7
        [47, 54, 57, 62],   # Bm7
        [43, 50, 54, 59],   # Gmaj7
        [45, 52, 57, 61],   # A7sus-ish
    ]
    for bar in range(bars):
        chord = prog[bar % 4]
        b0 = bar * 4
        # long pad
        tr.put(b0, pad_chord(chord, 4.4 * 60 / bpm, vel=0.4, cutoff=1250, attack=1.4, detune=9.0))
        # sub drone root
        tr.put(b0, sine(midi_hz(chord[0] - 12), int(4.3 * 60 / bpm * SR)) * 0.18
               * adsr(int(4.3 * 60 / bpm * SR), 1.0, 1.0, 0.9, 1.4))
        # bells sparse
        if bar % 2 == 0:
            for beat, deg in ((0.0, 0), (1.75, 2), (3.0, 3)):
                bl = bell(chord[deg] + 12, 2.2, vel=0.3)
                tr.put(b0 + beat, delay(bl, 0.4, fb=0.3, mix=0.35))
        # soft kick every 2 bars + shaker 8ths
        if bar % 2 == 0:
            tr.put(b0, kick(0.45, punch=0.2), bus="drums")
        for e in range(8):
            tr.put(b0 + e * 0.5, shaker(0.4 if e % 2 == 0 else 0.25), bus="drums")
        # airy lead phrase at bar ends
        if bar % 4 == 3:
            for beat, deg in ((2.0, 1), (2.5, 2), (3.0, 0)):
                tr.put(b0 + beat, lead_note(chord[deg] + 12, 0.4, vel=0.16, cutoff=3000))
    return tr


# ------------------------------------------------------------------- master
def render_track(recipe_fn, out_name):
    tr = recipe_fn()
    dry = tr.buf[: tr.total_n]
    drums = tr.sends["drums"][: tr.total_n]
    music = tr.sends["music"][: tr.total_n]

    music_wet = reverb(music, seconds=2.2, mix=0.34, damp=4200)
    drums_wet = reverb(drums, seconds=1.0, mix=0.10, damp=6500)

    mix = dry + music_wet * 0.35 + drums_wet * 0.2  # sends add sparkle/depth
    # gentle bus processing
    mix = lp(mix, 16000)
    mix = hp(mix, 28)
    # soft clip + normalize
    mix = np.tanh(mix * 1.15) / np.tanh(1.15)
    peak = np.abs(mix).max()
    if peak > 0:
        mix *= 0.89 / peak
    # loop-friendly crossfade: fold last 150 ms into first 150 ms
    xf = int(0.15 * SR)
    if len(mix) > 4 * xf:
        tail = mix[-xf:].copy()
        head = mix[:xf].copy()
        ramp = np.linspace(0.0, 1.0, xf)
        mix[:xf] = head * ramp + tail * (1.0 - ramp)
        mix = mix[:-xf]
    # de-click edges
    fade = int(0.008 * SR)
    mix[:fade] *= np.linspace(0, 1, fade)
    mix[-fade:] *= np.linspace(1, 0, fade)

    os.makedirs(OUT_DIR, exist_ok=True)
    path = os.path.join(OUT_DIR, out_name)
    pcm = (np.clip(mix, -1.0, 1.0) * 32767.0).astype(np.int16)
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())
    dur = len(pcm) / SR
    print(f"  wrote {path}  ({dur:.1f}s, {os.path.getsize(path)/1e6:.2f} MB)")


def main():
    print("Generating built-in tracks ...")
    render_track(recipe_neon_skyline, "neon_skyline.wav")
    render_track(recipe_pulse_drive, "pulse_drive.wav")
    render_track(recipe_quiet_orbit, "quiet_orbit.wav")
    print("Done.")


if __name__ == "__main__":
    sys.exit(main())
