#!/usr/bin/env python3
"""
숏폼용 배경 음악.

음악 앱 시연에 남의 곡을 깔 수는 없습니다. 그래서 직접 만듭니다 — 신시사이저
네 트랙(패드·베이스·플럭·드럼)을 numpy 로 합성한 밝은 112 BPM 루프입니다.
저작권이 우리에게 있으므로 어디에 올려도 문제가 없습니다.

    python3 demo/music.py --sec 40 --out demo/assets/reel_music.wav

필요한 것: numpy, scipy.
"""
import argparse, math, wave
from pathlib import Path
import numpy as np
from scipy.signal import butter, lfilter

SR = 44100
BPM = 112
BEAT = 60 / BPM
BAR = BEAT * 4

# C 장조. 밝고 익숙한 진행. (코드 구성음, 베이스 음) — MIDI 번호
PROG = [
    ([60, 64, 67], 36),   # C
    ([67, 71, 74], 43),   # G
    ([69, 72, 76], 45),   # Am
    ([65, 69, 72], 41),   # F
]


def hz(m):
    return 440.0 * 2 ** ((m - 69) / 12)


def env(n, a, d, s, r, hold):
    """ADSR. hold 는 서스테인 유지 시간(초)."""
    a, d, r, hold = (int(x * SR) for x in (a, d, r, hold))
    e = np.zeros(n)
    i = 0
    seg = min(a, n); e[i:i + seg] = np.linspace(0, 1, seg, endpoint=False); i += seg
    seg = min(d, n - i); e[i:i + seg] = np.linspace(1, s, seg, endpoint=False); i += seg
    seg = min(hold, n - i); e[i:i + seg] = s; i += seg
    seg = min(r, n - i); e[i:i + seg] = np.linspace(s, 0, seg, endpoint=False); i += seg
    return e


def lowpass(x, fc, order=2):
    b, a = butter(order, fc / (SR / 2), btype="low")
    return lfilter(b, a, x)


def highpass(x, fc, order=2):
    b, a = butter(order, fc / (SR / 2), btype="high")
    return lfilter(b, a, x)


def saw(f, t, detune=(0.0, 0.004, -0.004)):
    """살짝 어긋난 톱니파 셋. 혼자면 얇고, 셋이면 패드가 됩니다."""
    out = np.zeros_like(t)
    for d in detune:
        ph = (f * (1 + d) * t) % 1.0
        out += 2 * ph - 1
    return out / len(detune)


def tri(f, t):
    ph = (f * t) % 1.0
    return 4 * np.abs(ph - 0.5) - 1


def place(mix, start, sig):
    i = int(start * SR)
    j = min(len(mix), i + len(sig))
    if j > i:
        mix[i:j] += sig[: j - i]


def build(sec):
    n = int(sec * SR)
    pad = np.zeros(n); bass = np.zeros(n); pluck = np.zeros(n); drums = np.zeros(n)
    bars = int(math.ceil(sec / BAR)) + 1
    rng = np.random.default_rng(7)

    for b in range(bars):
        chord, root = PROG[b % len(PROG)]
        t0 = b * BAR
        # 패드 — 한 마디 내내
        tt = np.arange(int(BAR * SR)) / SR
        sig = sum(saw(hz(m), tt) for m in chord) / len(chord)
        sig = lowpass(sig, 2200) * env(len(tt), 0.25, 0.3, 0.8, 0.5, BAR - 0.9)
        place(pad, t0, sig * 0.5)

        # 베이스 — 1·2.5·3·4.5 박 (8분 싱코페이션)
        for beat in (0, 1.5, 2, 3.5):
            tt = np.arange(int(BEAT * 0.9 * SR)) / SR
            f = hz(root)
            sig = (np.sin(2 * np.pi * f * tt) + 0.35 * np.sin(2 * np.pi * 2 * f * tt))
            sig *= env(len(tt), 0.005, 0.15, 0.5, 0.12, BEAT * 0.4)
            place(bass, t0 + beat * BEAT, sig * 0.7)

        # 플럭 — 16분 아르페지오, 두 옥타브를 오르내림
        notes = [chord[0], chord[1], chord[2], chord[1] + 12, chord[2] + 12, chord[1] + 12,
                 chord[2], chord[1]] * 2
        for k, m in enumerate(notes):
            if rng.random() < 0.12:   # 가끔 쉬어야 기계 같지 않습니다
                continue
            tt = np.arange(int(0.32 * SR)) / SR
            f = hz(m)
            sig = (tri(f, tt) * 0.6 + np.sin(2 * np.pi * f * tt) * 0.4)
            sig = lowpass(sig, 5200) * np.exp(-tt * 8.0)
            vel = 0.9 if k % 4 == 0 else 0.6
            place(pluck, t0 + k * BEAT / 4, sig * 0.42 * vel)

        # 드럼
        # 킥 1·3박 (+ 4.5박 살짝)
        for kb in (0, 2, 3.5):
            tt = np.arange(int(0.22 * SR)) / SR
            f = 160 * np.exp(-tt * 22) + 48
            sig = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-tt * 14)
            place(drums, t0 + kb * BEAT, sig * (0.9 if kb != 3.5 else 0.45))
        # 스네어(클랩 느낌) 2·4박
        for sb in (1, 3):
            tt = np.arange(int(0.18 * SR)) / SR
            noise = rng.standard_normal(len(tt))
            sig = highpass(lowpass(noise, 6500), 900) * np.exp(-tt * 26)
            tone = np.sin(2 * np.pi * 190 * tt) * np.exp(-tt * 40)
            place(drums, t0 + sb * BEAT, (sig * 0.22 + tone * 0.28))
        # 하이햇 8분 (뒷박은 열린 느낌으로 조금 길게)
        for hb in range(8):
            tt = np.arange(int((0.05 if hb % 2 == 0 else 0.11) * SR)) / SR
            noise = rng.standard_normal(len(tt))
            sig = highpass(noise, 7500, 4) * np.exp(-tt * (90 if hb % 2 == 0 else 40))
            place(drums, t0 + hb * BEAT / 2, sig * (0.07 if hb % 2 == 0 else 0.05))

    mix = pad * 0.55 + bass * 0.8 + pluck * 0.9 + drums * 0.9
    # 킥에 맞춰 패드·플럭을 살짝 눌러 줍니다(사이드체인). 리듬이 살아납니다.
    duck = np.ones(n)
    for b in range(bars):
        for kb in (0, 2):
            i = int((b * BAR + kb * BEAT) * SR)
            L = int(0.28 * SR)
            if i < n:
                seg = np.linspace(0.55, 1.0, min(L, n - i)) ** 0.6
                duck[i:i + len(seg)] = np.minimum(duck[i:i + len(seg)], seg)
    mix = (pad * 0.6 + pluck * 1.0) * duck + bass * 0.85 + drums * 0.8

    # 처음 1마디는 드럼 없이 들어오고(인트로), 끝 1.8초는 페이드아웃
    intro = int(BAR * SR)
    mix[:intro] = (pad * 0.6 + pluck * 1.0)[:intro] * np.linspace(0.4, 1.0, intro)
    fade = int(1.8 * SR)
    mix[-fade:] *= np.linspace(1, 0, fade)

    mix = np.tanh(mix * 1.1) * 0.9          # 소프트 클립
    mix = mix / (np.max(np.abs(mix)) + 1e-9) * 0.85
    return mix


def write_wav(path, mono):
    stereo = np.stack([mono, mono], axis=1)
    data = (np.clip(stereo, -1, 1) * 32767).astype(np.int16)
    with wave.open(str(path), "wb") as w:
        w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes(data.tobytes())


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--sec", type=float, default=40)
    ap.add_argument("--out", default="demo/assets/reel_music.wav")
    a = ap.parse_args()
    Path(a.out).parent.mkdir(parents=True, exist_ok=True)
    write_wav(a.out, build(a.sec))
    print(f"{a.out}  {a.sec:.0f}초 · {BPM} BPM")
