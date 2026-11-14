#!/usr/bin/env python3
"""
시연 영상 편집.

`record.mjs` 가 남긴 차량 영상·폰 프레임·장면 기록을 한 편으로 붙입니다.
장면을 눈으로 찾아 자르지 않고 `cues.json` 의 시각으로 자르기 때문에,
다시 녹화해도 같은 편집이 그대로 나옵니다.

    python3 demo/compose.py --in recording --out 영상
    python3 demo/compose.py --only reel        # 숏폼만
    python3 demo/compose.py --only demo        # 본편만

만드는 것
    시연영상_플레이시트.mp4        1920x1080 · 심사 제출용
    숏폼_플레이시트.mp4            1080x1920 · 세로

ffmpeg 필터를 이어 붙이는 대신 **프레임을 직접 그립니다.** 녹화본을 한 장씩
읽어 기기 안에 넣고, 그 기기를 원근 투영으로 기울여 놓은 뒤, 글자와 함께
인코더로 흘려보냅니다. 이렇게 해야 기기가 공간 안에서 돌고, 글자가 한 줄씩
떠오르고, 컷이 박자에 맞습니다 — 필터 그래프로는 '화면 녹화를 붙인 것' 을
벗어나기 어렵습니다.

화면 내용은 건드리지 않습니다. 얹는 것은 기기 프레임·자막·음악·효과음뿐입니다.

필요한 것: ffmpeg, Python Pillow, numpy (음악을 새로 만들 때는 scipy).
"""

import argparse, json, math, shutil, subprocess, sys
from functools import lru_cache
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

HERE = Path(__file__).parent
FONT = "/System/Library/Fonts/AppleSDGothicNeo.ttc"
EMOJI = "/System/Library/Fonts/Apple Color Emoji.ttc"
FPS = 30

# 색. 폰 화면(passenger-web)과 같은 값입니다 — 영상의 글자와 화면 속 글자가
# 같은 회사 물건으로 보여야 합니다.
INK = (25, 31, 40)
INK2 = (78, 89, 104)
SUB = (139, 149, 161)
LINE = (229, 232, 235)
BG = (242, 244, 246)
WHITE = (255, 255, 255)
BLUE = (49, 130, 246)
BLUE_SOFT = (232, 243, 255)
BLUE_INK = (27, 100, 218)
RED = (240, 68, 82)

CAR_CROP = (1688, 1210, 856, 88)   # 에뮬레이터 전체 화면 중 앱 영역 (w, h, x, y)
LEAD = 2.5                          # record.mjs 와 같은 값
BEAT = 60 / 112                     # music.py 의 BPM. 숏폼의 컷은 전부 이 박자 위에 놓입니다.


# ── 글꼴 ────────────────────────────────────────────────────────────────
def _weights():
    """굵기 이름 → ttc 안의 번호. macOS 판마다 순서가 달라 이름으로 찾습니다."""
    out = {}
    for i in range(24):
        try:
            fam, style = ImageFont.truetype(FONT, 20, index=i).getname()
        except Exception:
            break
        if not fam.startswith("."):
            out.setdefault(style, i)
    return out


WEIGHTS = _weights()


def _w(name):
    for n in (name, "ExtraBold", "Bold", "SemiBold", "Medium", "Regular"):
        if n in WEIGHTS:
            return WEIGHTS[n]
    return 0


# 숏폼의 큰 글자. 시스템 고딕은 어디서나 본 글씨라 눈이 지나칩니다.
# 없으면 시스템 고딕의 가장 굵은 것으로 대신합니다.
DISPLAY = next((p for p in (HERE / "assets" / "fonts" / "BlackHanSans-Regular.ttf",
                            Path.home() / "Library/Fonts/BlackHanSans-Regular.ttf") if p.exists()), None)


@lru_cache(maxsize=None)
def font(size, weight="Bold"):
    if weight == "Display" and DISPLAY:
        return ImageFont.truetype(str(DISPLAY), int(size))
    return ImageFont.truetype(FONT, int(size), index=_w(weight))


# ── 작은 도구 ───────────────────────────────────────────────────────────
def run(*args):
    subprocess.run(args, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)


def probe(path):
    return float(subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", str(path)],
        capture_output=True, text=True).stdout.strip())


def clamp(x, a=0.0, b=1.0):
    return a if x < a else b if x > b else x


def lerp(a, b, t):
    return a + (b - a) * t


def mix(c1, c2, t):
    return tuple(int(round(lerp(a, b, t))) for a, b in zip(c1, c2))


def oc(p):
    """빠르게 들어와 부드럽게 멈춥니다."""
    p = clamp(p); return 1 - (1 - p) ** 3


def ob(p, s=1.7):
    """살짝 넘쳤다 돌아옵니다. 스티커·말풍선이 '톡' 하고 붙는 느낌."""
    p = clamp(p) - 1; return 1 + (s + 1) * p ** 3 + s * p ** 2


def io(p):
    p = clamp(p); return 4 * p ** 3 if p < .5 else 1 - (-2 * p + 2) ** 3 / 2


def ent(t, delay=0.0, d=0.5):
    return clamp((t - delay) / d)


# ── 그릴 재료 (전부 캐시) ───────────────────────────────────────────────
PAD = 6   # 글자 이미지 둘레 여백


@lru_cache(maxsize=None)
def text_im(s, size, weight="Bold", fill=INK):
    f = font(size, weight)
    l, _t, r, _b = f.getbbox(s)
    im = Image.new("RGBA", (r - l + PAD * 2, int(size * 1.34) + PAD * 2), (0, 0, 0, 0))
    ImageDraw.Draw(im).text((PAD - l, PAD), s, font=f, fill=tuple(fill) + (255,))
    return im


def text_w(s, size, weight="Bold"):
    return text_im(s, size, weight).width - PAD * 2


@lru_cache(maxsize=None)
def rrect_im(w, h, r, fill, outline=None, width=0, ss=3):
    im = Image.new("RGBA", (w * ss, h * ss), (0, 0, 0, 0))
    ImageDraw.Draw(im).rounded_rectangle(
        [0, 0, w * ss - 1, h * ss - 1], radius=r * ss, fill=tuple(fill),
        outline=tuple(outline) if outline else None, width=width * ss)
    return im.resize((w, h), Image.LANCZOS)


@lru_cache(maxsize=None)
def pill_im(s, size, weight, fg, bg, padx=None, pady=None):
    """알약 라벨."""
    padx = int(size * .72) if padx is None else padx
    pady = int(size * .36) if pady is None else pady
    t = text_im(s, size, weight, fg)
    w, h = t.width - PAD * 2 + padx * 2, int(size * 1.16) + pady * 2
    im = rrect_im(w, h, h // 2, tuple(bg) + (255,) if len(bg) == 3 else bg).copy()
    # 한글은 글자 상자의 위쪽 12%~98% 를 씁니다. 눈으로 본 가운데는 55% 지점입니다.
    im.alpha_composite(t, (padx - PAD, int(h / 2 - size * .56) - PAD))
    return im


@lru_cache(maxsize=None)
def shadow_im(w, h, r, blur, alpha, color=(0, 23, 51)):
    m = blur * 3
    a = Image.new("L", (w + m * 2, h + m * 2), 0)
    ImageDraw.Draw(a).rounded_rectangle([m, m, m + w, m + h], radius=r, fill=alpha)
    a = a.filter(ImageFilter.GaussianBlur(blur))
    im = Image.new("RGBA", a.size, tuple(color) + (0,))
    im.putalpha(a)
    return im


@lru_cache(maxsize=None)
def emoji_im(ch, px):
    """애플 컬러 이모지. 비트맵 글꼴이라 160 으로 그린 뒤 줄입니다."""
    f = ImageFont.truetype(EMOJI, 160)
    im = Image.new("RGBA", (260, 260), (0, 0, 0, 0))
    ImageDraw.Draw(im).text((40, 40), ch, font=f, embedded_color=True)
    box = im.getbbox()
    im = im.crop(box) if box else im
    k = px / im.height
    return im.resize((max(1, int(im.width * k)), px), Image.LANCZOS)


@lru_cache(maxsize=None)
def mark_im(size, bg=BLUE, fg=WHITE):
    """브랜드 마크 — 좌석 넷. 넷 다 같은 크기라는 것이 곧 하고 싶은 말입니다."""
    im = rrect_im(size, size, int(size * .28), tuple(bg) + (255,)).copy()
    g, s = int(size * .11), int(size * .27)
    o = (size - (s * 2 + g)) // 2
    seat = rrect_im(s, s, int(s * .3), tuple(fg) + (255,))
    for i in range(4):
        im.alpha_composite(seat, (o + (i % 2) * (s + g), o + (i // 2) * (s + g)))
    return im


@lru_cache(maxsize=None)
def bg_im(w, h, base, blob, strength=.5):
    """옅은 바탕 + 번진 색 덩이 둘. 평평한 단색이면 템플릿처럼 보입니다."""
    sw, sh = w // 8, h // 8
    m = Image.new("L", (sw, sh), 0)
    d = ImageDraw.Draw(m)
    d.ellipse([sw * .45, -sh * .25, sw * 1.35, sh * .45], fill=255)
    d.ellipse([-sw * .4, sh * .55, sw * .55, sh * 1.3], fill=200)
    m = m.filter(ImageFilter.GaussianBlur(sw / 5)).point(lambda v: int(v * strength))
    im = Image.composite(Image.new("RGB", (sw, sh), tuple(blob)), Image.new("RGB", (sw, sh), tuple(base)), m)
    arr = np.asarray(im.resize((w, h), Image.BICUBIC), dtype=np.int16)
    # 아주 약한 잡음. 넓은 그러데이션이 인코딩되며 줄무늬로 깨지는 것을 막습니다.
    rng = np.random.default_rng(7)
    arr = np.clip(arr + rng.integers(-1, 2, arr.shape[:2] + (1,)), 0, 255).astype(np.uint8)
    return Image.fromarray(arr).convert("RGBA")


# ── 얹기 ────────────────────────────────────────────────────────────────
def fade(im, a):
    out = im.copy()
    out.putalpha(im.getchannel("A").point(lambda v: int(v * a)))
    return out


def comp(cv, im, x, y, a=1.0):
    """화면 밖으로 나가도 되는 alpha_composite."""
    if a <= .004:
        return
    x, y = int(round(x)), int(round(y))
    x0, y0 = max(0, x), max(0, y)
    x1, y1 = min(cv.width, x + im.width), min(cv.height, y + im.height)
    if x1 <= x0 or y1 <= y0:
        return
    if (x0, y0, x1, y1) != (x, y, x + im.width, y + im.height):
        im = im.crop((x0 - x, y0 - y, x1 - x, y1 - y))
    if a < .996:
        im = fade(im, a)
    cv.alpha_composite(im, (x0, y0))


def comp_c(cv, im, cx, cy, a=1.0, s=1.0, rot=0.0):
    """가운데 기준으로, 크기·회전을 줘서."""
    if s <= .01:
        return
    if abs(s - 1) > .002:
        im = im.resize((max(1, int(im.width * s)), max(1, int(im.height * s))), Image.BICUBIC)
    if abs(rot) > .05:
        im = im.rotate(rot, resample=Image.BICUBIC, expand=True)
    comp(cv, im, cx - im.width / 2, cy - im.height / 2, a)


def rise(cv, im, x, y, p, dist=44):
    """아래에서 떠오르며 나타납니다. p 는 0→1."""
    e = oc(p)
    comp(cv, im, x, y + (1 - e) * dist, e)


def text(cv, s, x, y, size, weight="Bold", fill=INK, p=1.0, center=False, dist=44):
    im = text_im(s, size, weight, tuple(fill))
    if center:
        x = x - (im.width - PAD * 2) / 2
    rise(cv, im, x - PAD, y - PAD, p, dist)


# ── 3D ──────────────────────────────────────────────────────────────────
def quad(w, h, cx, cy, rx=0.0, ry=0.0, rz=0.0, s=1.0, f=2600.0):
    """평면 사각형을 공간에서 돌린 뒤 화면에 투영한 네 꼭짓점. 각도는 도(°).

    ry > 0 이면 오른쪽 변이 앞으로 나옵니다. rx > 0 이면 위쪽 변이 앞으로 나옵니다."""
    rx, ry, rz = math.radians(rx), math.radians(ry), math.radians(rz)
    out = []
    for x, y in ((-w / 2, -h / 2), (w / 2, -h / 2), (w / 2, h / 2), (-w / 2, h / 2)):
        x, y, z = x * s, y * s, 0.0
        c, n = math.cos(rz), math.sin(rz); x, y = x * c - y * n, x * n + y * c
        c, n = math.cos(ry), math.sin(ry); x, z = x * c + z * n, -x * n + z * c
        c, n = math.cos(rx), math.sin(rx); y, z = y * c - z * n, y * n + z * c
        k = f / (f + z)
        out.append((cx + x * k, cy + y * k))
    return out


def warp(cv, im, q, a=1.0):
    """im 을 네 꼭짓점 q 로 원근 변형해 얹습니다."""
    xs, ys = [p[0] for p in q], [p[1] for p in q]
    x0, y0 = math.floor(min(xs)) - 1, math.floor(min(ys)) - 1
    bw, bh = math.ceil(max(xs)) + 1 - x0, math.ceil(max(ys)) + 1 - y0
    if bw < 2 or bh < 2 or bw > 6000 or bh > 6000:
        return
    w, h = im.size
    A, B = [], []
    for (x, y), (u, v) in zip(((p[0] - x0, p[1] - y0) for p in q), ((0, 0), (w, 0), (w, h), (0, h))):
        A.append([x, y, 1, 0, 0, 0, -u * x, -u * y]); B.append(u)
        A.append([0, 0, 0, x, y, 1, -v * x, -v * y]); B.append(v)
    cf = np.linalg.solve(np.array(A, float), np.array(B, float))
    comp(cv, im.transform((bw, bh), Image.PERSPECTIVE, tuple(cf), Image.BICUBIC), x0, y0, a)


class Device:
    """화면 한 장을 품은 기기. 베젤·둥근 모서리·두께·그림자·유리 반사까지.

    화면을 그냥 얹으면 떠 있는 사각형으로 보이고, 그 순간 '화면 녹화를 붙인 것'
    이 됩니다. 두께와 그림자가 있어야 물건으로 읽힙니다."""
    M = 10    # 둘레 여백 (변형할 때 가장자리가 계단으로 깨지지 않게)
    SP = 150  # 그림자 여백

    def __init__(self, sw, sh, bezel, radius, island=False):
        self.sw, self.sh, self.b, self.r = sw, sh, bezel, radius
        self.W, self.H = sw + (bezel + self.M) * 2, sh + (bezel + self.M) * 2
        ss, M = 3, self.M
        base = Image.new("RGBA", (self.W * ss, self.H * ss), (0, 0, 0, 0))
        d = ImageDraw.Draw(base)
        box = [M * ss, M * ss, (self.W - M) * ss - 1, (self.H - M) * ss - 1]
        d.rounded_rectangle(box, radius=(radius + bezel) * ss, fill=(18, 20, 26, 255))
        d.rounded_rectangle(box, radius=(radius + bezel) * ss, outline=(78, 84, 96, 255), width=int(1.6 * ss))
        self.base = base.resize((self.W, self.H), Image.LANCZOS)

        side = Image.new("RGBA", (self.W * ss, self.H * ss), (0, 0, 0, 0))
        ImageDraw.Draw(side).rounded_rectangle(box, radius=(radius + bezel) * ss, fill=(52, 57, 68, 255))
        self.side = side.resize((self.W, self.H), Image.LANCZOS)

        m = Image.new("L", (sw * ss, sh * ss), 0)
        ImageDraw.Draw(m).rounded_rectangle([0, 0, sw * ss - 1, sh * ss - 1], radius=radius * ss, fill=255)
        self.mask = m.resize((sw, sh), Image.LANCZOS)

        # 유리 반사 — 왼쪽 위에서 비스듬히 떨어지는 옅은 빛
        yy, xx = np.mgrid[0:sh, 0:sw]
        g = np.clip(1 - (xx / sw * .95 + yy / sh * 1.5), 0, 1) ** 1.6 * 26
        gl = Image.new("RGBA", (sw, sh), (255, 255, 255, 0))
        gl.putalpha(Image.fromarray((g * (np.asarray(self.mask) / 255)).astype(np.uint8), "L"))
        self.gloss = gl
        if island:
            iw, ih = int(sw * .27), max(14, int(sw * .068))
            self.island = (rrect_im(iw, ih, ih // 2, (12, 13, 17, 255)), ((sw - iw) // 2, int(sw * .028)))
        else:
            self.island = None
        self.shadow = shadow_im(self.W - M * 2, self.H - M * 2, radius + bezel, 50, 105)
        self._blank = Image.new("RGB", (sw, sh), (250, 250, 251))

    def face(self, frame):
        im = self.base.copy()
        o = self.M + self.b
        im.paste(frame or self._blank, (o, o), self.mask)
        im.alpha_composite(self.gloss, (o, o))
        if self.island:
            im.alpha_composite(self.island[0], (o + self.island[1][0], o + self.island[1][1]))
        return im

    def draw(self, cv, frame, cx, cy, rx=0.0, ry=0.0, rz=0.0, s=1.0, a=1.0, thick=16):
        sp = self.shadow.width - (self.W - self.M * 2)
        warp(cv, self.shadow,
             quad(self.W - self.M * 2 + sp, self.H - self.M * 2 + sp,
                  cx + 60 * s * math.sin(math.radians(ry)), cy + 52 * s, rx, ry, rz, s), a * .95)
        # 두께 — 앞으로 나온 변 쪽으로 옆면이 드러납니다.
        dx = thick * s * math.sin(math.radians(ry))
        dy = -thick * s * math.sin(math.radians(rx))
        if abs(dx) + abs(dy) > .6:
            steps = max(1, int((abs(dx) + abs(dy)) / 2.5))
            for i in range(steps, 0, -1):
                k = i / steps
                warp(cv, self.side, quad(self.W, self.H, cx + dx * k, cy + dy * k, rx, ry, rz, s), a)
        warp(cv, self.face(frame), quad(self.W, self.H, cx, cy, rx, ry, rz, s), a)


# ── 읽고 쓰기 ───────────────────────────────────────────────────────────
class Reader:
    """영상에서 프레임을 한 장씩. 끝나면 마지막 장을 계속 돌려줍니다."""

    def __init__(self, path, ss, w, h, crop=None):
        vf = []
        if crop:
            vf.append(f"crop={crop[0]}:{crop[1]}:{crop[2]}:{crop[3]}")
        vf += [f"scale={w}:{h}:flags=lanczos", f"fps={FPS}"]
        self.size, self.n, self.last = (w, h), w * h * 3, None
        self.p = subprocess.Popen(
            ["ffmpeg", "-v", "error", "-ss", f"{max(0, ss):.3f}", "-i", str(path),
             "-vf", ",".join(vf), "-f", "rawvideo", "-pix_fmt", "rgb24", "-"],
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, bufsize=self.n * 2)

    def next(self):
        buf = self.p.stdout.read(self.n)
        if len(buf) == self.n:
            self.last = Image.frombytes("RGB", self.size, buf)
        return self.last

    def close(self):
        self.p.kill(); self.p.wait()


class Writer:
    def __init__(self, out, w, h):
        self.n = 0
        self.p = subprocess.Popen(
            ["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24",
             "-s", f"{w}x{h}", "-r", str(FPS), "-i", "-",
             "-c:v", "libx264", "-preset", "veryfast", "-crf", "12", "-pix_fmt", "yuv420p",
             str(out)],
            stdin=subprocess.PIPE)

    @property
    def t(self):
        return self.n / FPS

    def add(self, cv):
        self.p.stdin.write(cv.convert("RGB").tobytes())
        self.n += 1

    def close(self):
        self.p.stdin.close(); self.p.wait()


def frames(dur):
    return max(1, int(round(dur * FPS)))


# ── 소리 ────────────────────────────────────────────────────────────────
# 효과음은 demo/assets/sfx/ 에 둡니다(myinstants.com 에서 받은 짧은 소리).
# 저장소에는 올리지 않으므로, 폴더가 없으면 음악만 깔고 넘어갑니다.
SFX = {
    "whoosh": "whoosh-clean.mp3",
    "swoosh": "swoosh-2.mp3",
    "pop": "pop_7e9Is8L.mp3",
    "ding": "proper-ding_IbfMQYw.mp3",
    "chime": "success-chime.mp3",
}


def add_audio(video, out, events, music_sec, music_lufs, crf=23, reel=False):
    total = probe(video)
    if reel:      # 숏폼은 영상 구간에 맞춘 전용 편곡을 씁니다
        music = HERE / "assets" / "music_reel.wav"
        run(sys.executable, str(HERE / "music.py"), "--reel", "--out", str(music))
    else:
        music = HERE / "assets" / f"music_{int(music_sec)}s.wav"
        if not music.exists():
            run(sys.executable, str(HERE / "music.py"), "--sec", str(music_sec), "--out", str(music))
    args = ["ffmpeg", "-v", "error", "-y", "-i", str(video), "-i", str(music)]
    flt = [f"[1:a]atrim=0:{total:.2f},aformat=sample_rates=44100:channel_layouts=stereo,"
           f"loudnorm=I={music_lufs}:TP=-2:LRA=11,afade=t=in:d={0.05 if reel else 0.3},"
           f"afade=t=out:st={total - (0.9 if reel else 1.8):.2f}:d={0.9 if reel else 1.8}[m]"]
    tags, k = ["[m]"], 2
    for (t, name, gain) in events:
        f = HERE / "assets" / "sfx" / SFX[name]
        if not f.exists() or t >= total:
            continue
        args += ["-i", str(f)]
        flt.append(f"[{k}:a]aformat=sample_rates=44100:channel_layouts=stereo,volume={gain},"
                   f"adelay={int(t * 1000)}:all=1[s{k}]")
        tags.append(f"[s{k}]"); k += 1
    flt.append("".join(tags) + f"amix=inputs={len(tags)}:normalize=0:duration=first,"
               f"alimiter=limit=0.9[a]")
    run(*args, "-filter_complex", ";".join(flt), "-map", "0:v", "-map", "[a]",
        # 중간본은 손실이 거의 없게 받아 두고, 여기서 한 번만 제대로 줄입니다.
        # 메일 첨부로 나가는 파일이라 크기가 중요합니다.
        "-c:v", "libx264", "-preset", "slow", "-crf", str(crf), "-pix_fmt", "yuv420p",
        "-c:a", "aac", "-b:a", "160k", "-movflags", "+faststart",
        "-t", f"{total:.2f}", str(out))


# ════════════════════════════════════════════════════════════════════════
#  본편 (1920×1080)
# ════════════════════════════════════════════════════════════════════════
# (cues.json 에서 찾을 문구, 여유(초), 길이(초), 장, 주인공, 제목, 설명, 근거)
#
# 여유를 두는 이유: 신호를 넣은 순간부터 차량 화면이 바뀌기까지 한 박자 걸립니다.
# 장면 표시 시각에 딱 맞춰 자르면 자막은 '비' 인데 화면은 아직 이전 분위기입니다.
# 반대로 주행·분위기 장면은 여유가 음수입니다. 화면이 넘어가는 모습 자체를
# 보여 주려면 바뀌기 1초쯤 전부터 잡아야 합니다(장면 표시는 바뀐 직후에 찍힙니다).
#
# '주인공' 은 그 장면에서 봐야 할 기기입니다. 그쪽이 앞으로 나오고 다른 쪽이
# 한 걸음 물러납니다 — 화면 두 개를 나란히 두면 어디를 볼지 매번 찾아야 합니다.
SEGMENTS = [
    ("좌석이 곧 입장권", 0.6, 7.6, "탑승", "car",
     "앉으면 끝. 좌석이 입장권입니다",
     "착석 신호로 탑승자를 자동 인식합니다. 초대도, 세션 개설도 없습니다", None),
    ("최초 1회 QR", 0.5, 5.2, "탑승", "phone",
     "폰은 처음 한 번만 QR",
     "다음부터는 타기만 하면 자동으로 연결됩니다", None),
    ("추천이 떴습니다", 0.4, 5.6, "선곡", "car",
     "아무도 안 넣으면 차가 먼저 권합니다",
     "지금 분위기에 맞는 다섯 곡. 20초 안에 아무도 안 누르면 그대로 담깁니다", None),
    ("서아 → 괜찮아요", 0.2, 6.6, "선곡", "car",
     "그런데 뒷자리 7살은 생각이 다릅니다",
     "'괜찮아요' 를 누르고 아기상어를 넣습니다. 차례를 기다리지 않습니다", None),
    ("한 사람이 연달아 넣어도 막지 않습니다", 0.4, 5.6, "선곡", "car",
     "그리고 멈추지 않습니다",
     "아기상어, 그리고 또 아기상어. 한 사람이 연달아 넣어도 규칙으로 막지 않습니다", None),
    ("엄마 → ", 0.2, 10.4, "선곡", "phone",
     "조수석도, 뒷자리도, 각자의 폰에서",
     "운전자 폰을 건네받을 일이 없습니다. 검색하고 누르면 큐에 쌓입니다",
     "소리가 실제로 납니다  ·  공개 미리듣기 30초를 허브가 중계 (전체 음원 미보유)"),
    ("지호 → 건너뛰기 1/2", -0.5, 7.4, "선곡", "phone",
     "거슬리면 투표. 혼자서는 못 넘깁니다",
     "두 표가 모이면 다음 곡으로 넘어갑니다",
     "2열 좌 1/2  →  조수석 2/2  →  다음 곡"),
    # 기어를 넣는 순간부터 잡습니다. 화면이 넘어가는 모습 자체가 보여 줄 것입니다.
    ("주행하면 조작 권한이 동승자 폰으로", -1.2, 7.4, "주행", "car",
     "출발. 운전자 화면은 잠기고, 선곡권은 뒷자리로",
     "운전자에게 남는 건 '다음 곡' 과 '말하기' 둘뿐. 검색·목록·키보드가 사라집니다", None),
    ("날씨 API가 아니라 와이퍼로 읽는다", -1.6, 6.8, "주행", "car",
     "비가 오면 차가 압니다 — 와이퍼로",
     "날씨 API 가 아니라 차의 신호로. 화면·폰·실내 조명이 같이 바뀝니다",
     "주입: 와이퍼 Low  →  판정: 비 오는 날  →  앰비언트 조명 #4C6E8F"),
    ("전조등이 켜지면 밤처럼", -1.7, 6.0, "주행", "car",
     "전조등이 켜지면, 밤처럼",
     "왜 그렇게 판정했는지 근거를 함께 보여 줍니다",
     "주입: 전조등 LowBeam  →  판정: 밤 드라이브  →  앰비언트 조명 #3B4B7A"),
    ("지도 API 없이 조향 변동으로 지형을", -1.6, 6.4, "주행", "car",
     "핸들이 바쁘면, 굽잇길",
     "최근 12초 조향 변동폭으로 지형을 읽습니다. 지도 API 가 없어도 됩니다",
     "주입: 조향 ±75° 반복  →  변동폭 150° ≥ 55°  →  판정: 굽잇길"),
    # 장면 시작이 아니라 '누르는 순간' 을 잡습니다. 앞부분은 곡을 담는 시간이라
    # 보여 줄 것이 없고, 이 기능의 요점은 누르기 전후가 달라지는 것입니다.
    ("도착에 맞추기", -6.0, 12.5, "도착", "phone",
     "마지막 곡이 도착과 함께 끝납니다",
     "폰 플레이리스트는 목적지를 모릅니다. 차는 내비 ETA 를 알기 때문에 큐와 도착을 같은 자에 올립니다",
     "도착 시간은 시연용 주입값  ·  곡을 지우지 않고 순서만 바꿈  →  잘림 0초"),
    ("정차하면 운전자 화면이 다시 열린다", -0.8, 4.4, "도착", "car",
     "정차하면 운전자 화면이 돌아옵니다",
     "전체 큐·검색·좌석 현황이 다시 열립니다", None),
    ("좌석 지분이 드러납니다", 0.4, 5.6, "도착", "car",
     "독점은 막지 않고, 보이게 합니다",
     "차례를 돌리는 대신 좌석별 선곡 지분을 보여 줍니다. 사람은 숫자를 보고 스스로 비켜 줍니다", None),
    ("충전 중에는 라운지 모드", 2.5, 8.0, "충전 · 하차", "both",
     "충전 20분은 라운지가 됩니다",
     "정차해 있고 시간이 있는 상태. 다음 구간의 플레이리스트를 함께 만듭니다", None),
    ("도착하면 드라이브 앨범이 남는다", 1.0, 5.4, "충전 · 하차", "phone",
     "내리면 남는 것, 드라이브 앨범",
     "함께 들은 곡이 각자의 폰으로 전달됩니다", None),
]

# 문제 제기 카드 (본편 앞). (줄들, 작은 글, 길이, 그림)
# 기능을 보여 주기 전에 '왜' 를 먼저 보여 줍니다. 문제가 없으면 해결도 없습니다.
PROBLEM = [
    (["가족 넷, 주말 드라이브 세 시간."],
     ["운전석 아빠 · 조수석 엄마 · 뒷자리 지호 11살 · 서아 7살"], 3.0, None),
    (["차와 연결된 폰은", "하나뿐입니다. 운전석."],
     ["나머지 셋은 듣기만 합니다"], 3.8, "one"),
    (["그래서 세 시간 내내,", "운전석의 플레이리스트."],
     ["뒷자리: \"아빠, 노래 바꿔 줘\"  ×9", "조수석: \"폰 줘 봐, 내가 할게\""], 3.6, None),
    (["바꿔 줄 수 있는 단 한 사람은,", "운전 중입니다."],
     ["주행 중 화면 조작은 가장 위험한 순간입니다"], 3.8, None),
]
TITLE_DUR, TAIL_DUR = 4.6, 7.0


def cabin_im(conn, k=1.0):
    """차 안을 위에서 본 그림. 좌석 넷과 각자의 폰, 그리고 차와 이어진 선.

    연결된 폰만 파랗고 나머지는 흐립니다. 같은 그림을 '하나만 연결' 과
    '전부 연결' 두 번 쓰면 설명 없이도 전후가 읽힙니다."""
    return _cabin(tuple(sorted(conn)), k)


@lru_cache(maxsize=None)
def _cabin(conn, k):
    ss = 2
    k2 = k * ss
    w, h = int(700 * k2), int(700 * k2)
    im = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    I = lambda v: int(v * k2)
    body = [I(40), I(30), w - I(40), h - I(30)]
    d.rounded_rectangle(body, radius=I(120), fill=WHITE + (255,), outline=LINE + (255,), width=I(3))
    cx, top = w // 2, body[1] + I(70)
    d.rounded_rectangle([cx - I(170), top - I(13), cx + I(170), top + I(13)], radius=I(13), fill=LINE + (255,))
    seats = {
        "운전석": (cx - I(150), body[1] + I(215)), "조수석": (cx + I(150), body[1] + I(215)),
        "2열 좌": (cx - I(150), body[1] + I(445)), "2열 우": (cx + I(150), body[1] + I(445)),
    }
    sw, sh = I(200), I(180)
    fl, fs = font(I(27), "Bold"), font(I(20), "SemiBold")
    for name, (x, y) in seats.items():
        if name in conn:
            px, py = x + sw // 2 - I(36), y + sh // 2 - I(20)
            d.line([(px, py), (px, top + I(48)), (cx, top + I(48)), (cx, top)],
                   fill=BLUE + (255,), width=I(5), joint="curve")
    for name, (x, y) in seats.items():
        on = name in conn
        box = [x - sw // 2, y - sh // 2, x + sw // 2, y + sh // 2]
        d.rounded_rectangle(box, radius=I(30), fill=(BLUE_SOFT if on else BG) + (255,))
        d.text((x - d.textlength(name, font=fl) / 2, y - I(60)), name, font=fl,
               fill=(INK if on else SUB) + (255,))
        lab = "연결됨" if on else "듣기만"
        d.text((x - d.textlength(lab, font=fs) / 2, y - I(22)), lab, font=fs,
               fill=(BLUE_INK if on else SUB) + (255,))
        px, py = x + sw // 2 - I(36), y + sh // 2 - I(20)
        pw, ph = I(40), I(70)
        pbox = [px - pw // 2, py - ph // 2, px + pw // 2, py + ph // 2]
        if on:
            d.rounded_rectangle(pbox, radius=I(9), fill=BLUE + (255,))
        else:
            d.rounded_rectangle(pbox, radius=I(9), fill=WHITE + (255,), outline=(196, 203, 211, 255), width=I(3))
    r = I(28)
    d.ellipse([cx - r, top - r, cx + r, top + r], fill=BLUE + (255,))
    fn = font(I(30), "Bold")
    d.text((cx - d.textlength("♪", font=fn) / 2, top - I(21)), "♪", font=fn, fill=WHITE + (255,))
    return im.resize((w // ss, h // ss), Image.LANCZOS)


def build_demo(indir, work, phone_mp4, at, out):
    W, H = 1920, 1080
    bg = bg_im(W, H, BG, (214, 229, 250), .55)
    car = Device(960, int(round(960 * CAR_CROP[1] / CAR_CROP[0])), 15, 14)
    ph_h = 708
    phone = Device(int(round(ph_h * 390 / 844)), ph_h, 12, 40, island=False)
    CAR_AT, PH_AT = (648, 606), (1512, 606)

    total = sum(p[2] for p in PROBLEM) + TITLE_DUR + sum(s[2] for s in SEGMENTS) + TAIL_DUR
    # 장 — 아래 진행 줄에 찍힙니다. 2분짜리 영상에서 지금 어디쯤인지 보여야 합니다.
    chapters, t0 = [("문제", 0.0)], sum(p[2] for p in PROBLEM) + TITLE_DUR
    for s in SEGMENTS:
        if chapters[-1][0] != s[3]:
            chapters.append((s[3], t0))
        t0 += s[2]

    wr = Writer(work / "demo_silent.mp4", W, H)
    sfx = []
    X0, X1, BY = 120, W - 120, 1034

    def chrome(cv, a=1.0):
        t = wr.t
        d = ImageDraw.Draw(cv)
        d.rounded_rectangle([X0, BY, X1, BY + 5], radius=3, fill=mix(BG, (209, 214, 219), a) + (255,))
        d.rounded_rectangle([X0, BY, max(X0 + 6, lerp(X0, X1, t / total)), BY + 5], radius=3,
                            fill=mix(BG, BLUE, a) + (255,))
        for i, (name, st) in enumerate(chapters):
            end = chapters[i + 1][1] if i + 1 < len(chapters) else total
            on = st <= t < end
            x = lerp(X0, X1, st / total)
            d.ellipse([x - 5, BY - 3, x + 5, BY + 8], fill=(BLUE if t >= st else (196, 203, 211)) + (255,))
            comp(cv, text_im(name, 21, "Bold" if on else "Medium", INK if on else SUB), x - PAD, BY - 42 - PAD, a)

    def card(lines, sub, dur, art=None, big=82, brand=False, foot=None):
        n = frames(dur)
        aim = cabin_im({"운전석"} if art == "one" else {"운전석", "조수석", "2열 좌", "2열 우"}, .92) if art else None
        lh = int(big * 1.26)
        block = len(lines) * lh + (26 + len(sub) * 50 if sub else 0) + (150 if brand else 0)
        for k in range(n):
            t = k / FPS
            out_a = 1 - io(ent(t, dur - .32, .3))
            cv = bg.copy()
            lay = Image.new("RGBA", (W, H), (0, 0, 0, 0))
            y = (H - 70 - block) // 2
            left = aim is not None
            x = 150 if left else W // 2
            if brand:
                p = ob(ent(t, .0, .5))
                comp_c(lay, mark_im(112), (150 + 56) if left else W // 2, y + 56, oc(ent(t, 0, .25)), .6 + .4 * p)
                y += 150
            for i, ln in enumerate(lines):
                text(lay, ln, x, y, big, "Bold", INK, ent(t, .08 + i * .13, .5), center=not left)
                y += lh
            if sub:
                y += 26
                for i, ln in enumerate(sub):
                    text(lay, ln, x + (3 if left else 0), y, 33, "Medium", INK2,
                         ent(t, .36 + i * .1, .5), center=not left, dist=26)
                    y += 50
            if aim is not None:
                p = ent(t, .22, .7)
                comp_c(lay, aim, W - 150 - aim.width / 2, (H - 70) / 2 + (1 - oc(p)) * 40, oc(p), .94 + .06 * oc(p))
            if foot:
                for i, ln in enumerate(foot):
                    text(lay, ln, W // 2, H - 196 + i * 38, 23, "Medium", SUB, ent(t, .7 + i * .1, .5),
                         center=True, dist=14)
            comp(cv, lay, 0, 0, out_a)
            chrome(cv)
            wr.add(cv)

    print("     문제 제기 카드")
    for i, (lines, sub, dur, art) in enumerate(PROBLEM):
        sfx.append((wr.t + .05, "whoosh", .22))
        card(lines, sub, dur, art)
    sfx.append((wr.t + .1, "ding", .38))
    card(["플레이시트"], ["모든 좌석에 선곡권을. 각자의 폰으로.", "Pleos Connect 차량용 앱 · 팀 Semicolon"],
         TITLE_DUR, art="all", big=118, brand=True,
         foot=["화면은 에뮬레이터 실제 구동 녹화입니다 · 기기 프레임과 자막만 편집으로 얹었습니다",
               "배경음악·효과음은 편집에서 넣었습니다 (앱 재생음은 화면 녹화에 담기지 않습니다)"])

    # 주인공에 따라 두 기기가 서는 자세. (크기, 좌우 기울기)
    POSE = {
        "car":   ((1.035, -2.5), (0.93, 13.0)),
        "phone": ((0.945, -9.0), (1.05, 4.0)),
        "both":  ((1.0, -5.0), (1.0, 8.0)),
    }
    prev_pose = POSE["both"]
    prev_c = prev_p = None
    prev_cap = None
    XF = .4

    for i, (needle, lead, dur, _ch, focus, title, sub, note) in enumerate(SEGMENTS):
        print(f"     {i + 1:2}/{len(SEGMENTS)}  {title}")
        start = at(needle) + lead
        rc = Reader(indir / "car.mp4", start, car.sw, car.sh, crop=CAR_CROP)
        rp = Reader(phone_mp4, start, phone.sw, phone.sh)
        cap = (text_im(title, 52, "Bold", INK), text_im(sub, 27, "Medium", INK2),
               pill_im(note, 22, "SemiBold", BLUE_INK, BLUE_SOFT, 18, 9) if note else None)
        num = text_im(f"{i + 1:02d}", 22, "Bold", BLUE)
        den = text_im(f"/ {len(SEGMENTS)}", 22, "Medium", SUB)
        pose = POSE[focus]
        first, last = i == 0, i == len(SEGMENTS) - 1
        sfx.append((wr.t + .02, "swoosh" if i % 2 else "whoosh", .3 if first else .16))
        n = frames(dur)
        for k in range(n):
            t = k / FPS
            cf, pf = rc.next(), rp.next()
            if prev_c is not None and t < XF and cf is not None and pf is not None:
                cf = Image.blend(prev_c, cf, io(t / XF))
                pf = Image.blend(prev_p, pf, io(t / XF))
            cv = bg.copy()
            f = io(ent(t, 0, .8))
            (cs, cry), (ps, pry) = ((lerp(a[0], b[0], f), lerp(a[1], b[1], f))
                                    for a, b in zip(prev_pose, pose))
            g = wr.t
            # 가만히 멈춰 있으면 사진입니다. 아주 조금 숨을 쉽니다.
            cry += math.sin(g * .7) * .5; pry += math.sin(g * .6 + 1.3) * .7
            crx = 2.2 + math.sin(g * .5) * .3; prx = 2.6 + math.sin(g * .45 + .8) * .4
            cy_c = cy_p = 0.0
            a = 1.0
            if first:    # 기기가 공간 안으로 날아듭니다
                e, e2 = oc(ent(t, 0, 1.0)), oc(ent(t, .14, 1.0))
                cry += (1 - e) * -38; crx += (1 - e) * 14; cy_c = (1 - e) * 420; cs *= .82 + .18 * e
                pry += (1 - e2) * 46; prx += (1 - e2) * 14; cy_p = (1 - e2) * 520; ps *= .8 + .2 * e2
            if last:
                a = 1 - io(ent(t, dur - .4, .36))
            order = [("p", 0), ("c", 0)] if focus != "phone" else [("c", 0), ("p", 0)]
            for which, _ in order:
                if which == "c":
                    car.draw(cv, cf, CAR_AT[0], CAR_AT[1] + cy_c, crx, cry, 0, cs, a)
                else:
                    phone.draw(cv, pf, PH_AT[0], PH_AT[1] + cy_p, prx, pry, 0, ps, a)

            # 자막 — 앞 장면 것이 먼저 빠지고, 새 것이 한 줄씩 떠오릅니다.
            if prev_cap and t < .2:
                o = 1 - t / .2
                comp(cv, prev_cap[0], X0 - PAD, 56 - PAD - (1 - o) * 12, o)
                comp(cv, prev_cap[1], X0 + 2 - PAD, 128 - PAD - (1 - o) * 12, o)
                if prev_cap[2]:
                    comp(cv, prev_cap[2], X0, 176 - (1 - o) * 12, o)
            d0 = .16 if prev_cap else .3
            rise(cv, cap[0], X0 - PAD, 56 - PAD, ent(t, d0, .5) * a, 30)
            rise(cv, cap[1], X0 + 2 - PAD, 128 - PAD, ent(t, d0 + .1, .5) * a, 24)
            if cap[2]:
                rise(cv, cap[2], X0, 176, ent(t, d0 + .22, .5) * a, 20)
            comp(cv, num, X1 - (den.width - PAD * 2) - (num.width - PAD * 2) - 8 - PAD, 62 - PAD, a)
            comp(cv, den, X1 - (den.width - PAD * 2) - PAD, 62 - PAD, a)
            chrome(cv)
            wr.add(cv)
        prev_c, prev_p = rc.last.copy(), rp.last.copy()
        prev_pose, prev_cap = pose, cap
        rc.close(); rp.close()

    print("     마무리")
    sfx.append((wr.t + .1, "chime", .4))
    card(["운전석 한 명의 플레이리스트에서,", "네 명의 플레이리스트로."],
         ["좌석 · 주행 · 와이퍼 · 전조등 · 조향 · 충전",
          "폰은 알 수 없고 차만 아는 신호로 움직이는 앱입니다"],
         TAIL_DUR, big=78, brand=True, foot=["", "github.com/coalfire-create/playseat"])
    wr.close()
    # 본편의 음악은 배경입니다. 자막을 읽는 데 방해되지 않을 만큼만.
    add_audio(work / "demo_silent.mp4", out, sfx, math.ceil(total / 10) * 10 + 10, -30)


# ════════════════════════════════════════════════════════════════════════
#  숏폼 (1080×1920)
# ════════════════════════════════════════════════════════════════════════
# 숏폼은 '이 앱이 뭔지' 를 40초 안에 한눈에 알게 하는 소개 영상입니다.
#
#   문제(운전 중인 아빠에게 쏟아지는 선곡 요청)  →  해결(좌석마다 선곡권)
#   →  제목  →  STEP 01~07 로 기능을 하나씩  →  한 줄 정리
#
# 릴스 화면에서 가려지는 곳을 피합니다(1080×1920 기준).
#   위 250px  : '릴스' 머리글·카메라 아이콘
#   아래 420px: 계정명·캡션·음악 줄
#   오른쪽 140px: 좋아요·댓글·공유 버튼 (y 1050~1650)
# 그래서 글자는 x 84~940, y 250~640 안에, 기기는 y 680~1520 안에만 둡니다.
#
# 글자는 Pretendard 한 벌. 굵기 차이로만 위계를 줍니다.
#
# (문구, 여유, 박자 수, 배치, 꼬리표, 첫 줄, 둘째 줄(형광펜), 작은 글)
REEL = [
    ("좌석이 곧 입장권", 2.0, 8, "car", "탑승", "자리에 앉으면", "바로 연결돼요",
     "처음 한 번만 QR, 다음부턴 앉기만 하면 자동"),
    ("지호 → 검색어 입력", -0.4, 8, "phone", "신청", "듣고 싶은 노래는", "각자 폰으로 넣어요",
     "운전자에게 부탁할 필요도, 차례를 기다릴 필요도 없이"),
    ("지호 → 건너뛰기 1/2", -0.3, 8, "phone", "투표", "마음에 안 드는 곡은", "투표로 넘겨요",
     "두 명이 원하면 다음 곡 · 혼자서는 못 넘겨요"),
    ("주행하면 조작 권한이 동승자 폰으로", -0.9, 8, "car", "안전", "출발하면", "운전석 화면은 잠겨요",
     "선곡은 동승자 몫, 운전자는 운전에만 집중"),
    ("지호 → 도착에 맞추기", -2.6, 8, "phone", "도착", "도착 시간에 맞춰", "곡 순서를 바꿔요",
     "마지막 곡이 중간에 끊기지 않게, 차가 남은 시간을 계산"),
    ("날씨 API가 아니라 와이퍼로 읽는다", -1.6, 4, "car", "날씨", "비가 오면", "차가 먼저 알아요",
     "와이퍼가 움직이면 '비 오는 날' 선곡으로"),
    ("추천이 떴습니다", 0.4, 8, "car", "추천", "아무도 안 고를 땐", "차가 대신 골라요",
     "지금 분위기에 맞는 다섯 곡을 먼저 권해요"),
]
DARK = (15, 17, 21)
PAPER = (244, 245, 247)
YELLOW = (255, 214, 10)
CHAT = (254, 229, 0)
TX0, TXW = 84, 856          # 글자 영역 (왼쪽, 너비)

SANS_DIR = HERE / "assets" / "fonts"
SANS = {"Display": "ExtraBold", "Black": "Black", "Heavy": "Black", "ExtraBold": "ExtraBold",
        "Bold": "Bold", "SemiBold": "SemiBold", "Medium": "Medium", "Regular": "Medium"}
_use_sans = [False]
_font_sys = font


@lru_cache(maxsize=None)
def font(size, weight="Bold"):        # noqa: F811 — 숏폼에서는 Pretendard 로 갈아 끼웁니다
    if _use_sans[0]:
        p = SANS_DIR / f"Pretendard-{SANS.get(weight, 'SemiBold')}.otf"
        if p.exists():
            return ImageFont.truetype(str(p), int(size))
    return _font_sys(size, weight)


def use_sans():
    _use_sans[0] = True
    for f in (font, text_im, pill_im):
        f.cache_clear()


def fit(lines, size, weight="Display", maxw=TXW):
    while size > 40 and max(text_w(s, size, weight) for s in lines) > maxw:
        size -= 2
    return size


@lru_cache(maxsize=None)
def marker_im(w, h, color=YELLOW):
    """형광펜 한 줄. 끝이 둥근 반투명 띠."""
    ss = 3
    im = Image.new("RGBA", ((w + 24) * ss, (h + 8) * ss), (0, 0, 0, 0))
    ImageDraw.Draw(im).rounded_rectangle([4 * ss, 4 * ss, (w + 20) * ss, (h + 4) * ss], radius=6 * ss,
                                         fill=tuple(color) + (235,))
    return im.resize((w + 24, h + 8), Image.LANCZOS)


def headline(cv, s, x, y, size, t, delay, fill=INK, mark=None, weight="Display"):
    """굵은 한 줄. mark 가 있으면 글자 아래쪽 절반에 형광펜이 왼쪽에서 오른쪽으로 그어집니다."""
    im = text_im(s, size, weight, tuple(fill))
    if mark is not None:
        p = oc(ent(t, delay + .16, .34))
        if p > 0:
            mk = marker_im(im.width - PAD * 2 + 4, int(size * .42), mark)
            comp(cv, mk.crop((0, 0, max(1, int(mk.width * p)), mk.height)), x - 14, y + size * .66)
    rise(cv, im, x - PAD, y - PAD, ent(t, delay, .36), 48)


def build_reel(indir, work, phone_mp4, at, out):
    use_sans()
    W, H = 1080, 1920
    wr = Writer(work / "reel_silent.mp4", W, H)
    sfx = []
    flat = lambda c: Image.new("RGBA", (W, H), tuple(c) + (255,))
    bgDark, bgBlue, bgPaper, bgWhite = flat(DARK), flat(BLUE), flat(PAPER), flat(WHITE)
    MUTED = (150, 158, 170)

    # ── 1. 훅: 뒷자리에서 쏟아지는 요청 ─────────────────────────────────
    # 단톡방처럼 위에서 아래로 차곡차곡. 누가 말했는지(좌석)가 같이 보여야
    # '차 안 여러 사람 vs 운전자 한 명' 이 한 번에 읽힙니다.
    says = [("2열 왼쪽", "아빠, 노래 바꿔 줘"), ("2열 오른쪽", "이거 말고 다른 거!"),
            ("조수석", "이 노래 아까 들었잖아"), ("2열 왼쪽", "아빠아아, 노래"),
            ("2열 오른쪽", "폰 줘 봐, 내가 할게")]

    def bubble(who, s):
        lab = text_im(who, 30, "SemiBold", MUTED)
        t = text_im(s, 50, "Bold", WHITE)
        bw, bh = t.width - PAD * 2 + 72, 104
        im = Image.new("RGBA", (max(bw, lab.width) + 8, bh + 52), (0, 0, 0, 0))
        im.alpha_composite(lab, (6 - PAD + 4, 0))
        im.alpha_composite(rrect_im(bw, bh, 36, (44, 49, 58, 255)), (4, 46))
        im.alpha_composite(t, (4 + 36 - PAD, 46 + int(bh / 2 - 50 * .62) - PAD))
        return im

    bubs = [bubble(*x) for x in says]
    Y0, DY = 420, 178
    label = pill_im("고속도로 3시간째  ·  운전 중", 32, "SemiBold", (214, 220, 228), (40, 45, 54), 28, 14)
    count = pill_im("오늘만 9번째", 44, "Bold", WHITE, RED, 34, 18)

    def draw_bubbles(cv, t, a=1.0):
        for i, im in enumerate(bubs):
            p = ent(t, .08 + i * .2, .32)
            if p > 0:
                x = TX0 if i % 2 == 0 else TX0 + 120
                comp(cv, im, x, Y0 + i * DY + (1 - oc(p)) * 40, oc(p * 2) * a)

    dur = BEAT * 4
    for i in range(len(bubs)):
        sfx.append((wr.t + .08 + i * .2, "pop", .34 + .04 * (i % 3)))
    for k in range(frames(dur)):
        t = k / FPS
        cv = bgDark.copy()
        comp(cv, label, TX0, 280, oc(ent(t, 0, .3)))
        draw_bubbles(cv, t)
        p = ent(t, 1.3, .34)
        if p > 0:
            comp_c(cv, count, W - 140 - count.width / 2, Y0 + len(bubs) * DY + 60, oc(p * 3),
                   1.3 - .3 * ob(p, 1.2), -4)
        wr.add(cv)
    sfx.append((wr.t - dur + 1.3, "whoosh", .7))

    # ── 2. 문제 ───────────────────────────────────────────────────────
    dur = BEAT * 4
    sfx.append((wr.t, "swoosh", .6))
    sz = fit(["노래를 바꿀 수 있는 사람은", "운전 중인 아빠 한 명"], 96)
    for k in range(frames(dur)):
        t = k / FPS
        cv = bgDark.copy()
        comp(cv, label, TX0, 280, lerp(1.0, 0.0, oc(ent(t, 0, .25))))
        draw_bubbles(cv, 9, lerp(1.0, 0.0, oc(ent(t, 0, .25))))
        headline(cv, "노래를 바꿀 수 있는 사람은", TX0, 720, sz, t, .02, WHITE)
        headline(cv, "운전 중인 아빠 한 명", TX0, 720 + sz * 1.3, sz, t, .14, CHAT)
        text(cv, "차 안 선곡은 전부 운전석 화면 하나로만 돼요", TX0 + 4, 720 + sz * 2.6 + 30, 40, "Medium",
             (170, 178, 190), ent(t, .4, .4), dist=24)
        wr.add(cv)

    # ── 3. 해결 ───────────────────────────────────────────────────────
    dur = BEAT * 4
    sfx.append((wr.t, "whoosh", .7))
    sz = fit(["그래서,", "선곡권을 좌석마다 나눴어요"], 96)
    for k in range(frames(dur)):
        t = k / FPS
        cv = Image.blend(bgDark, bgBlue, io(t / .2)) if t < .2 else bgBlue.copy()
        headline(cv, "그래서,", TX0, 720, sz, t, .04, WHITE)
        headline(cv, "선곡권을 좌석마다 나눴어요", TX0, 720 + sz * 1.3, sz, t, .16, WHITE)
        text(cv, "차에 탄 모두가 자기 폰으로 함께 고르게", TX0 + 4, 720 + sz * 2.6 + 30, 42, "SemiBold",
             (214, 232, 255), ent(t, .42, .4), dist=24)
        wr.add(cv)

    # ── 4. 제목 ───────────────────────────────────────────────────────
    dur = BEAT * 4
    sfx.append((wr.t + .02, "ding", .7))
    seats = ["운전석", "조수석", "2열 왼쪽", "2열 오른쪽"]
    seat_px = [pill_im(s, 38, "Bold", BLUE_INK, BLUE_SOFT, 26, 16) for s in seats]
    gap = 16
    row_w = sum(p.width for p in seat_px) + gap * 3
    for i in range(4):
        sfx.append((wr.t + .6 + i * .12, "pop", .3))
    for k in range(frames(dur)):
        t = k / FPS
        cv = Image.blend(bgBlue, bgWhite, io(t / .18)) if t < .18 else bgWhite.copy()
        p = ent(t, 0, .45)
        comp_c(cv, mark_im(220), W // 2, 640, oc(p * 2.5), .35 + .65 * ob(p, 2.0), (1 - oc(p)) * -20)
        im = text_im("플레이시트", 150, "Black", INK)
        rise(cv, im, (W - im.width) / 2, 810 - PAD, ent(t, .1, .4), 48)
        text(cv, "차에 탄 모두가 함께 고르는 플레이리스트", W // 2, 1020, 44, "SemiBold", INK2,
             ent(t, .26, .4), center=True)
        x = (W - row_w) / 2
        for i, pim in enumerate(seat_px):
            p = ent(t, .6 + i * .12, .34)
            if p > 0:
                comp_c(cv, pim, x + pim.width / 2, 1180, oc(p * 2.5), .4 + .6 * ob(p, 2.2))
            x += pim.width + gap
        wr.add(cv)

    # ── 5. 기능 STEP 01~07 ────────────────────────────────────────────
    carA = Device(880, int(round(880 * CAR_CROP[1] / CAR_CROP[0])), 14, 16)
    phA = Device(int(round(560 * 390 / 844)), 560, 10, 34)
    carB = Device(620, int(round(620 * CAR_CROP[1] / CAR_CROP[0])), 11, 12)
    phB = Device(int(round(780 * 390 / 844)), 780, 12, 44)
    prev_bg = bgWhite
    N = len(REEL)
    for i, (needle, lead, beats, lay, tag, l1, l2, small) in enumerate(REEL):
        dur = BEAT * beats
        start = at(needle) + lead
        cdev, pdev = (carA, phA) if lay == "car" else (carB, phB)
        rc = Reader(indir / "car.mp4", start, cdev.sw, cdev.sh, crop=CAR_CROP)
        rp = Reader(phone_mp4, start, pdev.sw, pdev.sh)
        sfx.append((wr.t, "swoosh" if i % 2 else "whoosh", .55))
        side = -1 if i % 2 else 1
        sz = fit([l1, l2], 98)
        step = pill_im(f"STEP {i + 1:02d}  ·  {tag}", 32, "Bold", WHITE, BLUE, 24, 12)
        cnt = text_im(f"{i + 1} / {N}", 30, "SemiBold", SUB)
        for k in range(frames(dur)):
            t = k / FPS
            cf, pf = rc.next(), rp.next()
            cv = Image.blend(prev_bg, bgPaper, io(t / .2)) if t < .2 else bgPaper.copy()
            push = 1 + .03 * (t / dur)
            e1, e2 = ent(t, .0, .8), ent(t, .1, .8)
            if lay == "car":
                o1, o2 = oc(e1), oc(e2)
                cdev.draw(cv, cf, 520 + (1 - o1) * -110 * side, 1075 + (1 - ob(e1, 1.1)) * 300,
                          rx=6 + (1 - o1) * 16 + math.sin(t * 1.3) * .6,
                          ry=(-9 + (1 - o1) * -30) * side + math.sin(t * 1.1) * 1.2,
                          rz=(-1.2 + (1 - o1) * -5) * side, s=push * (.84 + .16 * o1), a=oc(e1 * 3))
                px = 790 if side > 0 else 250
                pdev.draw(cv, pf, px + (1 - o2) * 240 * side, 1320 + (1 - ob(e2, 1.1)) * 260,
                          rx=5 + math.sin(t * 1.2 + 1) * .7,
                          ry=(14 + (1 - o2) * 34) * side + math.sin(t * 1.0 + .6) * 1.4,
                          rz=(3 + (1 - o2) * 9) * side, s=push * (.84 + .16 * o2), a=oc(e2 * 3))
            else:
                o1, o2 = oc(e2), oc(e1)
                cx_c = 340 if side > 0 else 700
                cdev.draw(cv, cf, cx_c + (1 - o1) * -260 * side, 985 + (1 - o1) * 60,
                          rx=7, ry=(20 + (1 - o1) * 22) * side + math.sin(t * 1.0) * 1.2,
                          rz=(-5 + (1 - o1) * -6) * side, s=push * (.86 + .14 * o1), a=oc(e2 * 3))
                cx_p = 620 if side > 0 else 440
                pdev.draw(cv, pf, cx_p + (1 - o2) * 60 * side, 1145 + (1 - ob(e1, 1.1)) * 420,
                          rx=5 + (1 - o2) * 14 + math.sin(t * 1.2) * .6,
                          ry=(-10 + (1 - o2) * -28) * side + math.sin(t * 1.1 + .4) * 1.3,
                          rz=(2 + (1 - o2) * 6) * side, s=push * (.84 + .16 * o2), a=oc(e1 * 3), thick=18)
            comp(cv, step, TX0, 262, oc(ent(t, 0, .3)))
            comp(cv, cnt, 940 - cnt.width + PAD, 262 + step.height / 2 - 30 * .68 - PAD, oc(ent(t, 0, .3)))
            y1 = 262 + step.height + 28
            headline(cv, l1, TX0, y1, sz, t, .04, INK)
            headline(cv, l2, TX0, y1 + sz * 1.22, sz, t, .15, INK, mark=YELLOW)
            text(cv, small, TX0 + 4, y1 + sz * 2.44 + 18, 38, "Medium", INK2, ent(t, .36, .4), dist=22)
            wr.add(cv)
        rc.close(); rp.close()
        prev_bg = bgPaper

    # ── 6. 끝: 한 줄 정리 ─────────────────────────────────────────────
    dur = BEAT * 8
    sfx.append((wr.t, "whoosh", .6))
    sfx.append((wr.t + .2, "chime", .7))
    recap = ["앉으면 연결", "폰으로 신청", "투표로 스킵", "운전자는 운전만"]
    rec_px = [pill_im(s, 32, "Bold", WHITE, (36, 108, 222), 22, 14) for s in recap]
    for k in range(frames(dur)):
        t = k / FPS
        cv = Image.blend(prev_bg, bgBlue, io(t / .2)) if t < .2 else bgBlue.copy()
        p = ent(t, 0, .45)
        comp_c(cv, mark_im(190, WHITE, BLUE), W // 2, 560, oc(p * 2.5), .35 + .65 * ob(p, 2.0), (1 - oc(p)) * 20)
        im = text_im("플레이시트", 150, "Black", WHITE)
        rise(cv, im, (W - im.width) / 2, 710 - PAD, ent(t, .1, .4), 48)
        text(cv, "차의 모든 좌석이", W // 2, 930, 60, "Bold", WHITE, ent(t, .26, .4), center=True)
        text(cv, "DJ 부스가 됩니다", W // 2, 930 + 82, 60, "Bold", WHITE, ent(t, .36, .4), center=True)
        # 2×2 로 정리
        for j, pim in enumerate(rec_px):
            p = ent(t, .6 + j * .1, .34)
            if p > 0:
                cx = W // 2 + (-1 if j % 2 == 0 else 1) * 190
                comp_c(cv, pim, cx, 1170 + (j // 2) * 84, oc(p * 2.5), .5 + .5 * ob(p, 2.0))
        p = ent(t, 1.1, .4)
        if p > 0:
            pl = pill_im("Pleos Connect 차량용 앱  ·  팀 Semicolon", 30, "Bold", BLUE_INK, WHITE, 28, 14)
            comp_c(cv, pl, W // 2, 1390, oc(p * 2.5), .7 + .3 * ob(p))
        text(cv, "github.com/coalfire-create/playseat", W // 2, 1450, 28, "Medium", (214, 232, 255),
             ent(t, 1.3, .4), center=True, dist=16)
        wr.add(cv)
    for _ in range(frames(1.6)):
        wr.add(cv)
    wr.close()
    add_audio(work / "reel_silent.mp4", out, sfx, 0, -14, crf=21, reel=True)


# ── 진입 ────────────────────────────────────────────────────────────────
def build(indir: Path, outdir: Path, only=None):
    meta = json.loads((indir / "meta.json").read_text())
    cues = json.loads((indir / "cues.json").read_text())["cues"]
    fr = json.loads((indir / "frames.json").read_text())
    work = outdir / "작업"
    work.mkdir(parents=True, exist_ok=True)
    off = meta["scenarioStart"] + LEAD

    def at(needle):
        for c in cues:
            if needle in c["text"]:
                return c["t"] + off
        raise SystemExit(f"장면을 찾지 못했습니다: {needle}")

    # 폰 프레임 → 영상 (찍힌 시각을 그대로 길이로)
    print("1/3  폰 화면을 영상으로")
    car_dur = probe(indir / "car.mp4")
    lst = work / "phone.txt"
    with lst.open("w") as f:
        first = indir / "phone" / fr[0]["f"]
        f.write(f"file '{first.resolve()}'\nduration {fr[0]['t'] + LEAD:.3f}\n")
        for a, b in zip(fr, fr[1:]):
            f.write(f"file '{(indir / 'phone' / a['f']).resolve()}'\n")
            f.write(f"duration {max(0.02, b['t'] - a['t']):.3f}\n")
        last = indir / "phone" / fr[-1]["f"]
        f.write(f"file '{last.resolve()}'\nduration {max(0.1, car_dur - (fr[-1]['t'] + LEAD)):.3f}\n")
        f.write(f"file '{last.resolve()}'\n")
    phone_mp4 = work / "phone.mp4"
    run("ffmpeg", "-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", str(lst),
        "-vf", "fps=30", "-pix_fmt", "yuv420p", "-c:v", "libx264",
        "-preset", "veryfast", "-crf", "14", str(phone_mp4))

    outs = []
    if only in (None, "demo"):
        print("2/3  본편")
        outs.append(outdir / "시연영상_플레이시트.mp4")
        build_demo(indir, work, phone_mp4, at, outs[-1])
    if only in (None, "reel"):
        print("3/3  숏폼")
        outs.append(outdir / "숏폼_플레이시트.mp4")
        build_reel(indir, work, phone_mp4, at, outs[-1])
    for p in outs:
        print(f"\n{p}  —  {probe(p):.0f}초 · {p.stat().st_size / 1048576:.1f} MB")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--in", dest="indir", default="recording")
    ap.add_argument("--out", dest="outdir", default="영상")
    ap.add_argument("--only", choices=["demo", "reel"])
    a = ap.parse_args()
    if not shutil.which("ffmpeg"):
        sys.exit("ffmpeg 이 필요합니다.")
    build(Path(a.indir), Path(a.outdir), a.only)
