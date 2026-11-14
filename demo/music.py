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


# ── 숏폼 전용 편곡 ──────────────────────────────────────────────────────
# 위의 build() 는 같은 네 마디를 끝까지 되풀이합니다. 본편 배경으로는 그게
# 맞지만(자막을 읽는 데 방해가 안 됩니다), 숏폼에서는 40초 내내 같은 소리라
# 지루합니다. 숏폼은 영상의 구간에 맞춰 편곡을 바꿉니다 — 19마디.
#
#   1~2   문제 제기      드럼 없이 째깍이는 하이햇과 낮은 베이스. 끝에 라이저
#   3     "그래서"        코드 스탭 + 스네어 롤
#   4     제목            한 방 치고 멈췄다가 필인
#   5~14  기능 다섯       본 그루브. 두 마디마다 멜로디·코드 진행이 바뀌고 필인
#   15    비              드럼이 빠지고 패드만 (브레이크)
#   16~17 차가 권함       킥이 돌아오며 다시 쌓임
#   18~19 마무리          한 옥타브 올린 멜로디 + 마지막 한 방
REEL_BARS = 19
PROG_A = [([60, 64, 67], 36), ([67, 71, 74], 43), ([69, 72, 76], 45), ([65, 69, 72], 41)]   # C G Am F
PROG_B = [([65, 69, 72], 41), ([67, 71, 74], 43), ([64, 67, 71], 40), ([69, 72, 76], 45)]   # F G Em Am
PROG_C = [([65, 69, 72], 41), ([67, 71, 74], 43), ([69, 72, 76], 45), ([60, 64, 67], 36)]   # F G Am C
# 멜로디 모티프 (박 위치, MIDI). C 장조 펜타토닉 — 따라 부를 수 있을 만큼 단순하게.
MOTIFS = [
    [(0, 76), (0.75, 74), (1.5, 72), (2.5, 74), (3, 79)],
    [(0, 79), (0.5, 76), (1.5, 74), (2, 72), (3, 69), (3.5, 72)],
    [(0, 72), (1, 76), (1.5, 79), (2.5, 81), (3.25, 79)],
    [(0, 81), (0.75, 79), (1.5, 76), (2.25, 74), (3, 72)],
]


def build_reel(tail=2.5):
    n = int((REEL_BARS * BAR + tail) * SR)
    L = np.zeros(n); R = np.zeros(n)
    duckable = np.zeros((2, n))          # 킥에 눌리는 악기(패드·멜로디)
    rng = np.random.default_rng(11)

    def put(sig, t, pan=0.0, duck=False, gain=1.0):
        i = int(max(0, t) * SR)
        j = min(n, i + len(sig))
        if j <= i:
            return
        l, r = math.cos((pan + 1) * math.pi / 4), math.sin((pan + 1) * math.pi / 4)
        tgt = duckable if duck else None
        if tgt is None:
            L[i:j] += sig[: j - i] * l * gain; R[i:j] += sig[: j - i] * r * gain
        else:
            tgt[0, i:j] += sig[: j - i] * l * gain; tgt[1, i:j] += sig[: j - i] * r * gain

    def kick(t, g=1.0):
        tt = np.arange(int(0.26 * SR)) / SR
        f = 170 * np.exp(-tt * 24) + 46
        put(np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-tt * 12), t, gain=0.95 * g)

    def clap(t, g=1.0):
        tt = np.arange(int(0.2 * SR)) / SR
        nz = highpass(lowpass(rng.standard_normal(len(tt)), 7000), 1000)
        e = np.exp(-tt * 24) + 0.6 * np.exp(-np.abs(tt - 0.012) * 300) + 0.5 * np.exp(-np.abs(tt - 0.024) * 300)
        put(nz * e * 0.2 + np.sin(2 * np.pi * 195 * tt) * np.exp(-tt * 42) * 0.22, t, gain=g)

    def hat(t, g=1.0, open_=False, pan=0.25):
        tt = np.arange(int((0.16 if open_ else 0.045) * SR)) / SR
        put(highpass(rng.standard_normal(len(tt)), 7800, 4) * np.exp(-tt * (26 if open_ else 95)),
            t, pan=pan, gain=0.075 * g)

    def tom(t, f0, g=1.0, pan=0.0):
        tt = np.arange(int(0.24 * SR)) / SR
        f = f0 * (1 + 0.5 * np.exp(-tt * 30))
        put(np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-tt * 11), t, pan=pan, gain=0.5 * g)

    def bassnote(t, m, dur=0.9, g=1.0):
        tt = np.arange(int(BEAT * dur * SR)) / SR
        f = hz(m)
        sig = np.sin(2 * np.pi * f * tt) + 0.4 * np.sin(2 * np.pi * 2 * f * tt) + 0.12 * tri(3 * f, tt)
        put(sig * env(len(tt), 0.005, 0.14, 0.55, 0.1, BEAT * dur * 0.5), t, gain=0.62 * g)

    def padchord(t, chord, bars=1.0, fc=2200, g=1.0):
        tt = np.arange(int(BAR * bars * SR)) / SR
        sig = sum(saw(hz(m), tt) for m in chord) / len(chord)
        sig = lowpass(sig, fc) * env(len(tt), 0.2, 0.3, 0.8, 0.5, BAR * bars - 0.9)
        put(sig, t, pan=-0.15, duck=True, gain=0.3 * g)
        put(np.roll(sig, int(0.011 * SR)), t, pan=0.2, duck=True, gain=0.22 * g)

    def stab(t, chord, g=1.0):
        tt = np.arange(int(0.34 * SR)) / SR
        sig = sum(saw(hz(m + 12), tt) for m in chord) / len(chord)
        put(lowpass(sig, 4200) * np.exp(-tt * 9), t, duck=False, gain=0.42 * g)

    def lead(t, m, g=1.0, bright=5200):
        tt = np.arange(int(0.42 * SR)) / SR
        f = hz(m)
        sig = tri(f, tt) * 0.55 + np.sin(2 * np.pi * f * tt) * 0.45 + 0.1 * np.sin(2 * np.pi * 2 * f * tt)
        sig = lowpass(sig, bright) * np.exp(-tt * 6.5)
        put(sig, t, pan=-0.1, duck=True, gain=0.5 * g)
        put(sig, t + BEAT * 0.75, pan=0.55, duck=True, gain=0.16 * g)     # 되울림(오른쪽)
        put(sig, t + BEAT * 1.5, pan=-0.55, duck=True, gain=0.08 * g)     # 되울림(왼쪽)

    def arp(t0, chord, g=1.0, every=0.5):
        seq = [chord[0], chord[2], chord[1] + 12, chord[2]]
        k = 0
        b = 0.0
        while b < 4:
            if rng.random() > 0.1:
                tt = np.arange(int(0.22 * SR)) / SR
                f = hz(seq[k % 4] + 12)
                sig = lowpass(tri(f, tt), 6000) * np.exp(-tt * 13)
                put(sig, t0 + b * BEAT + rng.normal(0, 0.004), pan=0.4 if k % 2 else -0.4, duck=True,
                    gain=0.2 * g * (1.0 if k % 4 == 0 else 0.7))
            k += 1; b += every

    def riser(t, dur, g=1.0):
        tt = np.arange(int(dur * SR)) / SR
        nz = rng.standard_normal(len(tt))
        out = np.zeros(len(tt))
        # 구간을 나눠 통과 대역을 올립니다(필터 스윕 흉내)
        K = 16
        for i in range(K):
            a, b = int(len(tt) * i / K), int(len(tt) * (i + 1) / K)
            out[a:b] = highpass(nz[a:b + 64], 600 + 6500 * (i / K) ** 2)[: b - a]
        put(out * (tt / dur) ** 2, t, gain=0.16 * g)

    def crash(t, g=1.0):
        tt = np.arange(int(1.3 * SR)) / SR
        put(highpass(rng.standard_normal(len(tt)), 5000, 2) * np.exp(-tt * 3.2), t, pan=-0.2, gain=0.13 * g)
        put(highpass(rng.standard_normal(len(tt)), 5000, 2) * np.exp(-tt * 3.2), t, pan=0.3, gain=0.13 * g)

    kicks = []

    def groove(b, prog_i, chord, root, motif, energy=1.0, octave=0, fill=False, bright=5200):
        t0 = b * BAR
        padchord(t0, chord, g=0.9)
        for beat in (0, 1.5, 2, 3.5):
            bassnote(t0 + beat * BEAT, root + (12 if beat == 3.5 and prog_i % 2 else 0), g=energy)
        for kb in (0, 2, 2.75) if prog_i % 2 else (0, 1.5, 2):
            kick(t0 + kb * BEAT, 1.0 if kb in (0, 2) else 0.6); kicks.append(t0 + kb * BEAT)
        clap(t0 + BEAT); clap(t0 + 3 * BEAT)
        for h in range(8 if not fill else 6):
            hat(t0 + h * BEAT / 2 + rng.normal(0, 0.003), g=(1.0 if h % 2 else 0.8) * rng.uniform(0.85, 1.1),
                open_=(h == 7 and not fill) or (h == 3 and prog_i % 2 == 1))
        if energy > 0.9:
            for h in (1, 5, 9, 13):    # 16분 사이 음
                hat(t0 + h * BEAT / 4, g=0.45, pan=-0.3)
        for (beat, m) in motif:
            lead(t0 + beat * BEAT + rng.normal(0, 0.004), m + octave, g=rng.uniform(0.85, 1.05), bright=bright)
        arp(t0, chord, g=0.75 * energy)
        if fill:
            for i, (bt, f0) in enumerate(((3.0, 210), (3.25, 180), (3.5, 150), (3.75, 120))):
                tom(t0 + bt * BEAT, f0, g=0.9, pan=-0.5 + i * 0.33)
            clap(t0 + 3.5 * BEAT, 0.7)

    # 1~2: 문제 제기 — 째깍이는 긴장
    for b in (0, 1):
        t0 = b * BAR
        for h in range(16):
            hat(t0 + h * BEAT / 4, g=0.55 + 0.45 * (h % 4 == 0), pan=0.3 if h % 2 else -0.3)
        for beat in (0, 1.5, 3):
            bassnote(t0 + beat * BEAT, 45, dur=0.7, g=0.85)            # A — 단조 느낌으로 시작
        if b == 1:
            for beat in (2, 2.5, 3, 3.25, 3.5, 3.75):
                clap(t0 + beat * BEAT, 0.45 + 0.1 * beat / 4)
    padchord(0, [57, 60, 64], bars=2, fc=900, g=0.8)
    riser(BAR * 1.0, BAR * 1.0, 1.0)
    # 3: "그래서" — 스탭
    t0 = 2 * BAR
    crash(t0); kick(t0); kicks.append(t0)
    for beat, ch in ((0, PROG_C[0][0]), (1.5, PROG_C[1][0]), (2.5, PROG_C[2][0])):
        stab(t0 + beat * BEAT, ch); bassnote(t0 + beat * BEAT, PROG_C[0][1] if beat == 0 else 43 if beat == 1.5 else 45)
    for h in range(8):
        hat(t0 + h * BEAT / 2, g=0.9)
    for i in range(8):
        clap(t0 + (3 + i / 8) * BEAT, 0.35 + 0.08 * i)
    # 4: 제목 — 한 방, 멈춤, 필인
    t0 = 3 * BAR
    crash(t0, 1.2); kick(t0, 1.1); kicks.append(t0)
    stab(t0, [60, 64, 67, 72], 1.3); bassnote(t0, 36, dur=2.2)
    for (beat, m) in ((0, 84), (0.5, 79), (1, 76), (1.5, 72)):
        lead(t0 + beat * BEAT, m, 0.9)
    for i, (bt, f0) in enumerate(((3.0, 220), (3.25, 190), (3.5, 160), (3.75, 130))):
        tom(t0 + bt * BEAT, f0, pan=-0.5 + i * 0.33)
    riser(t0 + 2 * BEAT, 2 * BEAT, 0.7)
    # 5~14: 본 그루브 (장면마다 두 마디)
    for sc in range(5):
        prog = PROG_A if sc % 2 == 0 else PROG_B
        for k in range(2):
            b = 4 + sc * 2 + k
            chord, root = prog[(k + (2 if sc >= 3 else 0)) % 4]
            groove(b, sc * 2 + k, chord, root, MOTIFS[(sc + k) % 4], fill=(k == 1),
                   bright=4600 + 400 * sc)
        crash(4 * BAR + sc * 2 * BAR, 0.8)
    # 15: 비 — 브레이크
    t0 = 14 * BAR
    padchord(t0, [57, 60, 64, 67], fc=1300, g=1.3)
    bassnote(t0, 45, dur=3.5, g=0.8)
    for h in range(8):
        hat(t0 + h * BEAT / 2, g=0.5, pan=0.5 if h % 2 else -0.5)
    for (beat, m) in ((0, 76), (1, 72), (2, 69), (3, 72)):
        lead(t0 + beat * BEAT, m, 0.8, bright=3000)
    riser(t0 + 2 * BEAT, 2 * BEAT, 0.9)
    # 16~17: 다시 쌓임
    for k in range(2):
        b = 15 + k
        chord, root = PROG_C[k * 2]
        groove(b, k, chord, root, MOTIFS[2 if k == 0 else 0], energy=0.85 + 0.15 * k, fill=(k == 1))
    crash(15 * BAR, 0.9)
    # 18~19: 마무리 — 한 옥타브 위
    for k in range(2):
        b = 17 + k
        chord, root = PROG_C[k + 2] if k == 0 else PROG_C[3]
        groove(b, k + 1, chord, root, MOTIFS[k * 3 % 4], energy=1.0, octave=12, fill=False, bright=6500)
    crash(17 * BAR, 1.2)
    # 마지막 한 방
    t0 = REEL_BARS * BAR
    crash(t0, 1.3); kick(t0, 1.1); kicks.append(t0)
    stab(t0, [60, 64, 67, 72], 1.5); bassnote(t0, 36, dur=3.0)
    lead(t0, 84, 1.0); lead(t0, 72, 0.8)

    # 킥에 맞춰 패드·멜로디를 눌러 줍니다(사이드체인).
    duck = np.ones(n)
    for t in kicks:
        i = int(t * SR)
        seg = np.linspace(0.5, 1.0, min(int(0.26 * SR), n - i)) ** 0.6
        duck[i:i + len(seg)] = np.minimum(duck[i:i + len(seg)], seg)
    L += duckable[0] * duck; R += duckable[1] * duck
    st = np.stack([L, R], axis=1)
    fade = int(1.2 * SR)
    st[-fade:] *= np.linspace(1, 0, fade)[:, None]
    st = np.tanh(st * 1.15) * 0.9
    return st / (np.max(np.abs(st)) + 1e-9) * 0.88


def write_wav(path, sig):
    stereo = sig if sig.ndim == 2 else np.stack([sig, sig], axis=1)
    data = (np.clip(stereo, -1, 1) * 32767).astype(np.int16)
    with wave.open(str(path), "wb") as w:
        w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes(data.tobytes())


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--sec", type=float, default=40)
    ap.add_argument("--out", default="demo/assets/reel_music.wav")
    ap.add_argument("--reel", action="store_true", help="숏폼 전용 편곡(19마디, 구간별로 바뀝니다)")
    a = ap.parse_args()
    Path(a.out).parent.mkdir(parents=True, exist_ok=True)
    if a.reel:
        write_wav(a.out, build_reel())
        print(f"{a.out}  숏폼 편곡 · {REEL_BARS}마디 · {BPM} BPM")
    else:
        write_wav(a.out, build(a.sec))
        print(f"{a.out}  {a.sec:.0f}초 · {BPM} BPM")
