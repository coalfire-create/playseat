#!/usr/bin/env python3
"""
시연 영상 편집.

`record.mjs` 가 남긴 차량 영상·폰 프레임·장면 기록을 한 편으로 붙입니다.
장면을 눈으로 찾아 자르지 않고 `cues.json` 의 시각으로 자르기 때문에,
다시 녹화해도 같은 편집이 그대로 나옵니다.

    python3 demo/compose.py --in recording --out 영상

만드는 것
    시연영상_플레이시트.mp4        1920x1080 · 심사 제출용
    숏폼_플레이시트.mp4            1080x1920 · 세로

화면을 그냥 얹지 않고 **기기 안에 넣습니다.** 베젤·둥근 모서리·그림자가 없으면
떠 있는 사각형 두 개로 보이고, 그 순간 '화면 녹화를 붙인 것' 이 됩니다.
장면은 겹쳐 넘기고(크로스 디졸브) 자막은 들어오고 나갈 때 흐려집니다.

필요한 것: ffmpeg, Python Pillow.
자막은 Pillow 로 PNG 를 만들어 얹습니다 — macOS 의 ffmpeg 에는 drawtext 가
빠져 있는 경우가 많아 글자를 ffmpeg 에 맡기지 않습니다.
"""

import argparse, json, shutil, subprocess, sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter, ImageFont
import numpy as np

FONT = "/System/Library/Fonts/AppleSDGothicNeo.ttc"

INK = (248, 249, 251)
DIM = (142, 146, 156)
ACCENT = (96, 165, 250)
BEZEL = (24, 25, 29)
EDGE = (56, 58, 66)

CAR_CROP = (1688, 1210, 856, 88)   # 에뮬레이터 전체 화면 중 앱 영역
LEAD = 2.5                          # record.mjs 와 같은 값
XF = 0.45                           # 장면 사이 겹침(초)


def font(size, weight=0):
    return ImageFont.truetype(FONT, size, index=weight)


def run(*args):
    subprocess.run(args, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)


def probe(path):
    return float(subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", str(path)],
        capture_output=True, text=True).stdout.strip())


# ── 장면 ────────────────────────────────────────────────────────────────
# (cues.json 에서 찾을 문구, 여유(초), 길이(초), 자막 제목, 자막 설명)
#
# 여유를 두는 이유: 신호를 넣은 순간부터 차량 화면이 바뀌기까지 한 박자 걸립니다.
# 장면 표시 시각에 딱 맞춰 자르면 자막은 '비' 인데 화면은 아직 이전 분위기입니다.
# 굽잇길은 조향 변동이 쌓여야 판정되므로 여유가 더 깁니다.
SEGMENTS = [
    ("좌석이 곧 입장권", 0.6, 7.6,
     "앉으면 끝. 좌석이 입장권입니다",
     "착석 신호로 탑승자를 자동 인식합니다. 초대도, 세션 개설도 없습니다"),
    ("최초 1회 QR", 0.5, 5.2,
     "폰은 처음 한 번만 QR",
     "다음부터는 타기만 하면 자동으로 연결됩니다"),
    ("추천이 떴습니다", 0.4, 5.6,
     "아무도 안 넣으면 차가 먼저 권합니다",
     "지금 분위기에 맞는 다섯 곡. 20초 안에 아무도 안 누르면 그대로 담깁니다"),
    ("서아 → 괜찮아요", 0.2, 6.6,
     "그런데 뒷자리 7살은 생각이 다릅니다",
     "'괜찮아요' 를 누르고 아기상어를 넣습니다. 차례를 기다리지 않습니다"),
    ("한 사람이 연달아 넣어도 막지 않습니다", 0.4, 5.6,
     "그리고 멈추지 않습니다",
     "아기상어, 그리고 또 아기상어. 한 사람이 연달아 넣어도 규칙으로 막지 않습니다"),
    ("엄마 → ", 0.2, 10.4,
     "조수석도, 뒷자리도, 각자의 폰에서",
     "운전자 폰을 건네받을 일이 없습니다. 검색하고 누르면 큐에 쌓입니다",
     "소리가 실제로 납니다  —  공개 미리듣기 30초를 허브가 중계 (전체 음원 미보유)"),
    ("지호 → 건너뛰기 1/2", -0.5, 7.4,
     "거슬리면 투표. 혼자서는 못 넘깁니다",
     "두 표가 모이면 다음 곡으로 넘어갑니다",
     "2열 좌 1/2  →  조수석 2/2  →  다음 곡"),
    ("주행하면 조작 권한이 동승자 폰으로", 1.5, 6.4,
     "출발. 운전자 화면은 잠기고, 선곡권은 뒷자리로",
     "운전자에게 남는 건 '다음 곡' 과 '말하기' 둘뿐. 검색·목록·키보드가 사라집니다"),
    ("날씨 API가 아니라 와이퍼로 읽는다", 2.0, 6.4,
     "비가 오면 차가 압니다 — 와이퍼로",
     "날씨 API 가 아니라 차의 신호로. 화면·폰·실내 조명이 같이 바뀝니다",
     "주입: 와이퍼 Low  →  판정: 비 오는 날  →  앰비언트 조명 #4C6E8F"),
    ("전조등이 켜지면 밤처럼", 2.0, 5.6,
     "전조등이 켜지면, 밤처럼",
     "왜 그렇게 판정했는지 근거를 함께 보여 줍니다",
     "주입: 전조등 LowBeam  →  판정: 밤 드라이브  →  앰비언트 조명 #3B4B7A"),
    ("지도 API 없이 조향 변동으로 지형을", 4.5, 6.0,
     "핸들이 바쁘면, 굽잇길",
     "최근 12초 조향 변동폭으로 지형을 읽습니다. 지도 API 가 없어도 됩니다",
     "주입: 조향 ±75° 반복  →  변동폭 150° ≥ 55°  →  판정: 굽잇길"),
    ("폰은 도착을 모릅니다. 차는 압니다", 3.0, 8.4,
     "마지막 곡이 도착과 함께 끝납니다",
     "폰 플레이리스트는 목적지를 모릅니다. 차는 내비 ETA 를 알기 때문에 큐와 도착을 같은 자에 올립니다",
     "도착선이 3번째 곡에 걸침  →  곡을 지우지 않고 순서만 바꿈  →  잘림 0초"),
    ("정차하면 운전자 화면이 다시 열린다", 1.5, 4.4,
     "정차하면 운전자 화면이 돌아옵니다",
     "전체 큐·검색·좌석 현황이 다시 열립니다"),
    ("좌석 지분이 드러납니다", 0.4, 5.6,
     "독점은 막지 않고, 보이게 합니다",
     "차례를 돌리는 대신 좌석별 선곡 지분을 보여 줍니다. 사람은 숫자를 보고 스스로 비켜 줍니다"),
    ("충전 중에는 라운지 모드", 2.5, 8.0,
     "충전 20분은 라운지가 됩니다",
     "정차해 있고 시간이 있는 상태. 다음 구간의 플레이리스트를 함께 만듭니다"),
    ("도착하면 드라이브 앨범이 남는다", 1.0, 5.4,
     "내리면 남는 것, 드라이브 앨범",
     "함께 들은 곡이 각자의 폰으로 전달됩니다"),
]

# 문제 제기 카드 (본편 앞). (줄들, 작은 글, 길이, 그림)
# 기능을 보여 주기 전에 '왜' 를 먼저 보여 줍니다. 문제가 없으면 해결도 없습니다.
PROBLEM = [
    (["가족 넷, 주말 드라이브 세 시간."],
     ["운전석 아빠 · 조수석 엄마 · 뒷자리 지호 11살 · 서아 7살"], 3.0, None),
    (["차와 연결된 폰은 하나뿐입니다.", "운전석."],
     ["나머지 셋은 듣기만 합니다"], 3.8, "one"),
    (["그래서 세 시간 내내,", "운전석의 플레이리스트."],
     ["뒷자리: \"아빠, 노래 바꿔 줘\"  ×9", "조수석: \"폰 줘 봐, 내가 할게\""], 3.6, None),
    (["바꿔 줄 수 있는 단 한 사람은,", "운전 중입니다."],
     ["주행 중 화면 조작은 가장 위험한 순간입니다"], 3.8, None),
]

# 숏폼 (문구, 여유, 길이, 큰 글자, 작은 글자) — 세로. 첫 2초 안에 문제가 보여야 합니다.
SHORT_HOOK = [
    (["차에서 노래,", "누가 고르지?"], ["항상 운전석. 세 시간 내내"], 2.6, "one"),
    (["바꿀 수 있는 한 사람은", "운전 중."], ["뒷자리: \"아빠, 노래 바꿔 줘\"  ×9"], 2.8, None),
]
SHORT = [
    ("좌석이 곧 입장권", 2.0, 4.6, "앉으면\n자동 입장", "좌석 착석 신호로 탑승자 인식"),
    ("서아 → 괜찮아요", 1.4, 5.0, "뒷자리 7살,\n아기상어 투하", "각자 폰에서 그냥 넣습니다"),
    ("엄마 → ", 3.2, 6.2, "조수석도 뒷자리도\n각자의 폰으로", "운전자 폰을 건넬 일이 없습니다"),
    ("지호 → 건너뛰기 1/2", -0.3, 5.4, "거슬리면\n투표로 넘깁니다", "혼자서는 못 넘깁니다 · 2표"),
    ("주행하면 조작 권한이 동승자 폰으로", 1.5, 4.6, "달리면\n운전석 화면은 잠김", "선곡권은 뒷자리로"),
    ("날씨 API가 아니라 와이퍼로 읽는다", 2.5, 4.6, "비는\n와이퍼가 안다", "날씨 API 없이 차의 신호로"),
]


# ── 그리기 ──────────────────────────────────────────────────────────────
def backdrop(w, h, boxes):
    """배경과 기기 그림자. 평평한 검정이면 화면이 공중에 뜹니다."""
    bg = Image.new("RGB", (w, h), (10, 11, 14))
    top, bottom = (22, 24, 30), (9, 10, 13)
    d = ImageDraw.Draw(bg)
    for y in range(h):
        t = y / max(1, h - 1)
        d.line([(0, y), (w, y)], fill=tuple(int(a + (b - a) * t) for a, b in zip(top, bottom)))

    # 기기 뒤의 옅은 빛 — 화면이 놓인 자리를 눈이 먼저 찾습니다.
    glow = Image.new("L", (w, h), 0)
    gd = ImageDraw.Draw(glow)
    for (x, y, bw, bh, _r) in boxes:
        gd.ellipse([x - bw * 0.3, y - bh * 0.2, x + bw * 1.3, y + bh * 1.2], fill=46)
    glow = glow.filter(ImageFilter.GaussianBlur(170))
    bg = Image.composite(Image.new("RGB", (w, h), (46, 52, 66)), bg, glow)

    # 그림자
    sh = Image.new("L", (w, h), 0)
    sd = ImageDraw.Draw(sh)
    for (x, y, bw, bh, r) in boxes:
        sd.rounded_rectangle([x, y + 16, x + bw, y + bh + 26], radius=r, fill=150)
    sh = sh.filter(ImageFilter.GaussianBlur(34))
    bg = Image.composite(Image.new("RGB", (w, h), (0, 0, 0)), bg, sh)
    return bg


def chrome(w, h, devices, ss=4):
    """기기 베젤. 화면 자리는 뚫어 두고 그 둘레만 그립니다.

    ImageDraw 는 안티앨리어싱이 없어 둥근 모서리가 계단으로 깨집니다. 4배로
    그려서 줄입니다 — 베젤 안쪽의 둥근 선이 깨져 있으면 바로 '합성' 티가 납니다."""
    layer = Image.new("RGBA", (w * ss, h * ss), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    for (x, y, sw, sh, bez, radius) in devices:
        x, y, sw, sh, bez, radius = (v * ss for v in (x, y, sw, sh, bez, radius))
        out = [x - bez, y - bez, x + sw + bez, y + sh + bez]
        d.rounded_rectangle(out, radius=radius + bez, fill=BEZEL + (255,))
        d.rounded_rectangle(out, radius=radius + bez, outline=EDGE + (255,), width=2 * ss)
        # 화면 자리를 뚫습니다 (ImageDraw 는 알파를 그대로 덮어씁니다)
        d.rounded_rectangle([x, y, x + sw, y + sh], radius=radius, fill=(0, 0, 0, 0))
    return layer.resize((w, h), Image.LANCZOS)


def screen_mask(w, h, radius, ss=4):
    """화면 영상의 모서리를 둥글게 깎는 마스크(흰색=보임).

    베젤만 둥글게 그리면 화면 영상의 네모난 꼭짓점이 베젤 바깥으로 삐져나옵니다
    (꼭짓점까지의 거리 r·√2 가 r+베젤두께보다 크므로). 영상 쪽을 깎아야 깨끗합니다."""
    m = Image.new("L", (w * ss, h * ss), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, w * ss - 1, h * ss - 1], radius=radius * ss, fill=255)
    return m.resize((w, h), Image.LANCZOS)


def labels(w, h, items):
    layer = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    f = font(23, 0)
    for (x, y, text) in items:
        d.text((x, y), text, font=f, fill=DIM + (255,))
    return layer


def caption(w, h, title, sub, x, y, big=46, small=27, rule=True, note=None):
    """note 는 '무엇을 넣어 무엇이 나왔는가' 한 줄. 말만으로는 증거가 안 됩니다."""
    layer = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    if rule:
        d.rectangle([x, y - 26, x + 54, y - 22], fill=ACCENT + (255,))
    ft, fs = font(big, 2), font(small, 0)
    yy = y
    for line in title.split("\n"):
        d.text((x, yy), line, font=ft, fill=INK + (255,))
        yy += int(big * 1.25)
    if sub:
        yy += 6
        for line in sub.split("\n"):
            d.text((x + 2, yy), line, font=fs, fill=DIM + (255,))
            yy += int(small * 1.45)
    if note:
        yy += 10
        fn = font(int(small * 0.92), 0)
        d.text((x + 2, yy), note, font=fn, fill=ACCENT + (255,))
    return layer


def card(w, h, lines, sub=None, foot=None, big=86):
    im = backdrop(w, h, [])
    d = ImageDraw.Draw(im)
    fb, fm, ff = font(big, 2), font(34, 0), font(27, 0)
    total = len(lines) * int(big * 1.2) + (len(sub) * 50 if sub else 0)
    y = (h - total) // 2
    for ln in lines:
        d.text(((w - d.textlength(ln, font=fb)) / 2, y), ln, font=fb, fill=INK)
        y += int(big * 1.2)
    if sub:
        y += 20
        for ln in sub:
            d.text(((w - d.textlength(ln, font=fm)) / 2, y), ln, font=fm, fill=DIM)
            y += 50
    if foot:
        d.text(((w - d.textlength(foot, font=ff)) / 2, h - 118), foot, font=ff, fill=ACCENT)
    return im


# ── 문제 제기 그림과 카드 ────────────────────────────────────────────────
def cabin_art(size, connected, scale=1.0, light=False):
    """차 안을 위에서 본 그림. 좌석 넷과 각자의 폰, 그리고 차와 이어진 선.

    연결된 폰만 강조색이고 나머지는 흐립니다. 같은 그림을 '하나만 연결' 과
    '전부 연결' 두 번 쓰면 설명 없이도 전후가 읽힙니다."""
    w, h = size
    im = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    k = scale
    # 밝은 배경(숏폼)에서는 흰 선·흰 폰, 어두운 배경(본편)에서는 강조색
    EDGE_, ACC_, INK_, DIM_, FILL_, NODEINK_ = (
        ((255, 255, 255, 150), (255, 255, 255, 255), (255, 255, 255, 255), (255, 255, 255, 170),
         (255, 255, 255, 40), (37, 99, 235, 255))
        if light else
        (EDGE + (255,), ACCENT + (255,), INK + (255,), DIM + (255,), (30, 32, 38, 255), (12, 14, 18, 255)))
    # 차체
    body = [int(40 * k), int(30 * k), w - int(40 * k), h - int(30 * k)]
    d.rounded_rectangle(body, radius=int(120 * k), outline=EDGE_, width=max(2, int(3 * k)))
    # 대시보드(스피커 노드)
    cx, top = w // 2, body[1] + int(70 * k)
    d.rounded_rectangle([cx - int(170 * k), top - int(14 * k), cx + int(170 * k), top + int(14 * k)],
                        radius=int(14 * k), fill=EDGE_)
    node_r = int(26 * k)
    seats = {
        "운전석": (cx - int(150 * k), body[1] + int(170 * k)),
        "조수석": (cx + int(150 * k), body[1] + int(170 * k)),
        "2열 좌": (cx - int(150 * k), body[1] + int(400 * k)),
        "2열 우": (cx + int(150 * k), body[1] + int(400 * k)),
    }
    sw, sh = int(190 * k), int(170 * k)
    fl, fs = font(int(26 * k), 2), font(int(20 * k), 0)
    # 선을 먼저 긋습니다 (좌석 아래로 들어가게)
    for name, (x, y) in seats.items():
        on = name in connected
        px, py = x + sw // 2 - int(34 * k), y + sh // 2 - int(18 * k)
        if on:
            d.line([(px, py), (px, top + int(40 * k)), (cx, top + int(40 * k)), (cx, top)],
                   fill=ACC_, width=max(2, int(4 * k)), joint="curve")
    for name, (x, y) in seats.items():
        on = name in connected
        box = [x - sw // 2, y - sh // 2, x + sw // 2, y + sh // 2]
        d.rounded_rectangle(box, radius=int(26 * k), fill=FILL_,
                            outline=(EDGE_ if not on else ACC_), width=max(2, int(3 * k)))
        d.rounded_rectangle([x - int(60 * k), box[1] - int(10 * k), x + int(60 * k), box[1] + int(4 * k)],
                            radius=int(6 * k), fill=EDGE_)
        d.text((x - d.textlength(name, font=fl) / 2, y - int(58 * k)), name, font=fl,
               fill=(INK_ if on else DIM_))
        # 폰
        px, py = x + sw // 2 - int(34 * k), y + sh // 2 - int(18 * k)
        pw, ph = int(38 * k), int(66 * k)
        pbox = [px - pw // 2, py - ph // 2, px + pw // 2, py + ph // 2]
        if on:
            d.rounded_rectangle(pbox, radius=int(8 * k), fill=ACC_)
        else:
            d.rounded_rectangle(pbox, radius=int(8 * k), outline=DIM_, width=max(2, int(3 * k)))
            d.line([(pbox[0] + int(9 * k), pbox[1] + int(22 * k)), (pbox[2] - int(9 * k), pbox[3] - int(22 * k))],
                   fill=DIM_, width=max(2, int(3 * k)))
            d.line([(pbox[2] - int(9 * k), pbox[1] + int(22 * k)), (pbox[0] + int(9 * k), pbox[3] - int(22 * k))],
                   fill=DIM_, width=max(2, int(3 * k)))
        lab = "연결됨" if on else "듣기만"
        d.text((x - d.textlength(lab, font=fs) / 2, y + int(8 * k)), lab, font=fs,
               fill=(ACC_ if on else DIM_))
    d.ellipse([cx - node_r, top - node_r, cx + node_r, top + node_r], fill=ACC_)
    fn = font(int(28 * k), 2)
    d.text((cx - d.textlength("♪", font=fn) / 2, top - int(19 * k)), "♪", font=fn, fill=NODEINK_)
    return im


def motion_card(work, name, w, h, lines, sub, dur, art=None, big=None, foot=None,
                brand=False, vertical=False):
    """카드 한 장. 글자가 아래에서 살짝 떠오르며 들어오고 나갈 때 흐려집니다.

    카드가 툭 나타나면 슬라이드 쇼가 됩니다. 움직임은 작게, 한 방향으로만."""
    bg = backdrop(w, h, [])
    fg = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(fg)
    big = big or (84 if not vertical else 80)
    fb, fm, ff = font(big, 2), font(32 if not vertical else 34, 0), font(25, 0)
    art_im = None
    if art is not None:
        conn = {"운전석"} if art == "one" else {"운전석", "조수석", "2열 좌", "2열 우"}
        if vertical:
            art_im = cabin_art((700, 700), conn, scale=1.0)
        else:
            art_im = cabin_art((760, 720), conn, scale=1.05)

    lh = int(big * 1.22)
    block = len(lines) * lh + (16 + len(sub) * 50 if sub else 0)
    if vertical:
        x = 84
        y = 300
        for ln in lines:
            d.text((x, y), ln, font=fb, fill=INK + (255,)); y += lh
        if sub:
            y += 16
            for ln in sub:
                d.text((x + 2, y), ln, font=fm, fill=DIM + (255,)); y += 50
        if art_im is not None:
            fg.alpha_composite(art_im, ((w - art_im.width) // 2, max(y + 60, 820)))
    else:
        if art_im is not None:
            x = 150
            ax = w - art_im.width - 150
            fg.alpha_composite(art_im, (ax, (h - art_im.height) // 2))
            y = (h - block) // 2
            for ln in lines:
                d.text((x, y), ln, font=fb, fill=INK + (255,)); y += lh
            if sub:
                y += 16
                for ln in sub:
                    d.text((x + 2, y), ln, font=fm, fill=DIM + (255,)); y += 50
        else:
            y = (h - block) // 2
            for ln in lines:
                d.text(((w - d.textlength(ln, font=fb)) / 2, y), ln, font=fb, fill=INK + (255,)); y += lh
            if sub:
                y += 16
                for ln in sub:
                    d.text(((w - d.textlength(ln, font=fm)) / 2, y), ln, font=fm, fill=DIM + (255,)); y += 50
    if brand:
        d.rectangle([(w - 54) // 2 if art_im is None and not vertical else (84 if vertical else 150),
                     (h - block) // 2 - 34 if not vertical else 266,
                     ((w - 54) // 2 if art_im is None and not vertical else (84 if vertical else 150)) + 54,
                     (h - block) // 2 - 30 if not vertical else 270], fill=ACCENT + (255,))
    if foot:
        d.text(((w - d.textlength(foot, font=ff)) / 2, h - 118), foot, font=ff, fill=ACCENT + (255,))

    bgp, fgp = work / f"{name}_bg.png", work / f"{name}_fg.png"
    bg.save(bgp); fg.save(fgp)
    out = work / f"{name}.mp4"
    run("ffmpeg", "-v", "error", "-y", "-loop", "1", "-i", str(bgp), "-loop", "1", "-i", str(fgp),
        "-filter_complex",
        f"[1:v]format=rgba,fade=t=in:st=0:d=0.55:alpha=1,"
        f"fade=t=out:st={dur - 0.55:.2f}:d=0.45:alpha=1[fg];"
        f"[0:v][fg]overlay=0:'26*pow(1-min(1,t/0.8),2)':shortest=1,fps=30,format=yuv420p[v]",
        "-map", "[v]", "-t", f"{dur}", "-c:v", "libx264", "-preset", "medium", "-crf", "18", str(out))
    return out


# ── 본편 ────────────────────────────────────────────────────────────────
def build(indir: Path, outdir: Path):
    meta = json.loads((indir / "meta.json").read_text())
    cues = json.loads((indir / "cues.json").read_text())["cues"]
    frames = json.loads((indir / "frames.json").read_text())
    work = outdir / "작업"
    work.mkdir(parents=True, exist_ok=True)

    off = meta["scenarioStart"] + LEAD

    def at(needle):
        for c in cues:
            if needle in c["text"]:
                return c["t"] + off
        raise SystemExit(f"장면을 찾지 못했습니다: {needle}")

    car_dur = probe(indir / "car.mp4")

    # 1. 폰 프레임 → 영상 (찍힌 시각을 그대로 길이로)
    print("1/6  폰 화면을 영상으로")
    lst = work / "phone.txt"
    with lst.open("w") as f:
        first = indir / "phone" / frames[0]["f"]
        f.write(f"file '{first.resolve()}'\nduration {frames[0]['t'] + LEAD:.3f}\n")
        for a, b in zip(frames, frames[1:]):
            f.write(f"file '{(indir / 'phone' / a['f']).resolve()}'\n")
            f.write(f"duration {max(0.02, b['t'] - a['t']):.3f}\n")
        last = indir / "phone" / frames[-1]["f"]
        f.write(f"file '{last.resolve()}'\nduration {max(0.1, car_dur - (frames[-1]['t'] + LEAD)):.3f}\n")
        f.write(f"file '{last.resolve()}'\n")
    phone_mp4 = work / "phone.mp4"
    if phone_mp4.exists():
        print("     (이미 있어 건너뜁니다)")
    else:
      run("ffmpeg", "-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", str(lst),
        "-vf", "fps=30", "-pix_fmt", "yuv420p", "-c:v", "libx264",
        "-preset", "veryfast", "-crf", "18", str(phone_mp4))

    # 2. 기기 안에 넣고 한 화면으로
    print("2/6  기기 안에 넣기")
    W, H = 1920, 1080
    # 기기 아래 이름표까지 화면 안에 들어와야 합니다. 밑이 잘리면 바로 티가 납니다.
    ch = 766
    cw = int(round(ch * CAR_CROP[0] / CAR_CROP[1] / 2) * 2)
    ph = 766
    pw = int(round(ph * 390 / 844 / 2) * 2)
    gap = 92
    x0 = (W - (cw + gap + pw)) // 2
    y0 = 190
    car_box = (x0, y0, cw, ch)
    phone_box = (x0 + cw + gap, y0, pw, ph)

    bg_png, chrome_png, label_png = work / "bg.png", work / "chrome.png", work / "labels.png"
    backdrop(W, H, [(x0 - 16, y0 - 16, cw + 32, ch + 32, 26),
                    (phone_box[0] - 14, y0 - 14, pw + 28, ph + 28, 58)]).save(bg_png)
    chrome(W, H, [(*car_box, 16, 12), (*phone_box, 14, 44)]).save(chrome_png)
    labels(W, H, [(x0 + 2, y0 + ch + 34, "차량 디스플레이"),
                  (phone_box[0] + 2, y0 + ph + 34, "동승자 폰")]).save(label_png)

    cmask, pmask = work / "cmask.png", work / "pmask.png"
    screen_mask(cw, ch, 12).save(cmask)
    screen_mask(pw, ph, 44).save(pmask)
    stage = work / "stage.mp4"
    if stage.exists():
        print("     (이미 있어 건너뜁니다)")
    else:
      run("ffmpeg", "-v", "error", "-y",
        "-loop", "1", "-i", str(bg_png),
        "-i", str(indir / "car.mp4"), "-i", str(phone_mp4),
        "-i", str(chrome_png), "-i", str(label_png),
        "-loop", "1", "-i", str(cmask), "-loop", "1", "-i", str(pmask),
        "-filter_complex",
        f"[1:v]crop={CAR_CROP[0]}:{CAR_CROP[1]}:{CAR_CROP[2]}:{CAR_CROP[3]},"
        f"scale={cw}:{ch},fps=30,setsar=1,format=rgba[car0];[car0][5:v]alphamerge[car];"
        f"[2:v]scale={pw}:{ph},setsar=1,format=rgba[ph0];[ph0][6:v]alphamerge[ph];"
        f"[0:v][car]overlay={car_box[0]}:{car_box[1]}[a];"
        f"[a][ph]overlay={phone_box[0]}:{phone_box[1]}[b];"
        f"[b][3:v]overlay=0:0[c];[c][4:v]overlay=0:0[v]",
        "-map", "[v]", "-t", f"{car_dur:.2f}", "-pix_fmt", "yuv420p",
        "-c:v", "libx264", "-preset", "medium", "-crf", "18", str(stage))

    # 3. 장면별로 자르고 자막을 넣고 빼기
    print("3/6  장면과 자막")
    parts = []
    for i, seg_def in enumerate(SEGMENTS):
        needle, lead, dur, title, sub = seg_def[:5]
        note = seg_def[5] if len(seg_def) > 5 else None
        cap = work / f"cap{i:02d}.png"
        caption(W, H, title, sub, x0, 40, note=note).save(cap)
        seg = work / f"seg{i:02d}.mp4"
        run("ffmpeg", "-v", "error", "-y", "-ss", f"{at(needle) + lead:.2f}", "-t", f"{dur}",
            "-i", str(stage), "-loop", "1", "-i", str(cap),
            "-filter_complex",
            # 자막은 들어오고 나갈 때 흐려집니다. 툭 나타나면 자동 생성물처럼 보입니다.
            f"[1:v]format=rgba,fade=t=in:st=0.15:d=0.45:alpha=1,"
            f"fade=t=out:st={dur - 0.75:.2f}:d=0.45:alpha=1[cap];"
            f"[0:v][cap]overlay=0:0:shortest=1[v]",
            "-map", "[v]", "-t", f"{dur}", "-pix_fmt", "yuv420p", "-c:v", "libx264",
            "-preset", "medium", "-crf", "18", "-r", "30", str(seg))
        parts.append((seg, dur))
        print(f"     {i + 1:2}/{len(SEGMENTS)}  {title}")

    # 4. 문제 제기 → 제목 → (본편) → 마무리
    print("4/6  문제 제기와 제목")
    fronts = []
    for i, (lines, sub, dur, art) in enumerate(PROBLEM):
        fronts.append((motion_card(work, f"p{i}", W, H, lines, sub, dur, art=art), dur))
    title_dur = 4.4
    fronts.append((motion_card(
        work, "title", W, H, ["플레이시트"],
        ["모든 좌석에 선곡권을. 각자의 폰으로.", "", "Pleos Connect 차량용 앱 · 팀 Semicolon"],
        title_dur, art="all", big=112, brand=True,
        foot="에뮬레이터 실제 구동 화면 · 편집 외에 가공하지 않았습니다"), title_dur))
    tail_dur = 7.0
    tail = motion_card(
        work, "tail", W, H,
        ["운전석 한 명의 플레이리스트에서,", "네 명의 플레이리스트로."],
        ["좌석 · 주행 · 와이퍼 · 전조등 · 조향 · 충전",
         "폰은 알 수 없고 차만 아는 신호로 움직이는 앱입니다"],
        tail_dur, big=76, foot="github.com/coalfire-create/playseat")

    # 5. 겹쳐 넘기며 잇기
    print("5/6  겹쳐 넘기며 잇기")
    clips = fronts + parts + [(tail, tail_dur)]
    final = outdir / "시연영상_플레이시트.mp4"
    xfade_concat(clips, final)

    # 6. 숏폼
    print("6/6  숏폼")
    short = outdir / "숏폼_플레이시트.mp4"
    build_short(indir, work, phone_mp4, at, short)

    for p in (final, short):
        print(f"\n{p}  —  {probe(p):.0f}초 · {p.stat().st_size / 1048576:.1f} MB")


def xfade_concat(clips, out, xf=XF):
    """크로스 디졸브로 잇습니다. 툭툭 끊기면 '자른 티' 가 그대로 납니다."""
    args = ["ffmpeg", "-v", "error", "-y"]
    for path, _ in clips:
        args += ["-i", str(path)]
    chain, prev, elapsed = [], "0:v", clips[0][1]
    for i in range(1, len(clips)):
        off = elapsed - xf
        tag = f"x{i}"
        chain.append(f"[{prev}][{i}:v]xfade=transition=fade:duration={xf}:offset={off:.3f}[{tag}]")
        prev = tag
        # xfade 출력 길이는 offset + 두 번째 입력 길이입니다. 겹친 만큼 줄어듭니다.
        elapsed = off + clips[i][1]
    args += ["-filter_complex", ";".join(chain), "-map", f"[{prev}]",
             "-c:v", "libx264", "-preset", "slow", "-crf", "19",
             "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(out)]
    run(*args)


# ── 숏폼 ───────────────────────────────────────────────────────────────
# 숏폼은 심사용 본편과 다른 물건입니다. 손안에서 엄지로 넘기다 멈춰야 하므로
# 색이 세고, 글자가 크고, 음악이 있습니다. 본편의 어두운 톤을 그대로 쓰면 묻힙니다.
def hexrgb(h):
    h = h.lstrip("#"); return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


# 장면별 색. (왼쪽 위, 오른쪽 아래)
REEL_PAL = {
    "hook":  ("#1E40AF", "#7C3AED"),
    "logo":  ("#2563EB", "#06B6D4"),
    "seat":  ("#0EA5E9", "#6366F1"),
    "shark": ("#F97316", "#EC4899"),
    "phone": ("#7C3AED", "#2563EB"),
    "vote":  ("#059669", "#0EA5E9"),
    "drive": ("#F59E0B", "#EF4444"),
    "rain":  ("#334155", "#2563EB"),
    "end":   ("#111827", "#1D4ED8"),
}

# (문구, 여유, 길이, 큰 글자, 작은 글자, 색, 칩)
REEL = [
    ("좌석이 곧 입장권", 2.0, 4.3, "앉으면\n자동 입장", "좌석 착석 신호로 탑승자를 인식합니다", "seat", "초대 없음 · 세션 개설 없음"),
    ("서아 → 괜찮아요", 1.4, 4.8, "뒷자리 7살,\n아기상어 투하", "차가 권한 곡을 거절하고 자기 곡을 넣습니다", "shark", "차례 없음 · 바로 재생"),
    ("한 사람이 연달아 넣어도 막지 않습니다", 0.6, 3.2, "그리고\n멈추지 않습니다", "뽀로로, 또 아기상어", "shark", "아기상어 ×2"),
    ("엄마 → ", 3.2, 5.9, "각자의 폰으로\n그냥 넣습니다", "운전자 폰을 건넬 일이 없습니다", "phone", "조수석 · 2열 좌 · 2열 우"),
    ("지호 → 건너뛰기 1/2", -0.3, 5.4, "거슬리면\n투표로", "혼자서는 못 넘깁니다. 두 표면 넘어갑니다", "vote", "1/2 → 2/2 → 다음 곡"),
    ("주행하면 조작 권한이 동승자 폰으로", 1.5, 4.3, "달리면\n운전석은 잠김", "선곡권은 뒷자리로 이사합니다", "drive", "운전자: 다음 곡 · 말하기"),
    ("날씨 API가 아니라 와이퍼로 읽는다", 2.5, 4.3, "비는\n와이퍼가 안다", "날씨 API 없이 차의 신호로 분위기를 읽습니다", "rain", "화면 · 폰 · 실내 조명이 같이"),
]
REEL_HOOK = [
    (["차에서 노래,", "누가 고르지?"], ["항상 운전석. 세 시간 내내."], 2.7, "one", "hook"),
    (["바꿀 수 있는", "한 사람은 운전 중."], ["뒷자리: \"아빠, 노래 바꿔 줘\"  ×9"], 2.7, None, "hook"),
]


def vivid(w, h, pal, boxes=()):
    """대각선 그러데이션 + 부드러운 빛 두 덩이. 평평한 단색이면 템플릿처럼 보입니다."""
    c1, c2 = (hexrgb(x) for x in REEL_PAL[pal])
    yy, xx = np.mgrid[0:h, 0:w]
    t = ((xx / w) * 0.55 + (yy / h) * 0.45)[..., None]
    arr = (np.array(c1) * (1 - t) + np.array(c2) * t).astype(np.uint8)
    bg = Image.fromarray(arr, "RGB")
    glow = Image.new("L", (w, h), 0)
    gd = ImageDraw.Draw(glow)
    gd.ellipse([-w * 0.2, -h * 0.05, w * 0.55, h * 0.35], fill=70)
    gd.ellipse([w * 0.45, h * 0.55, w * 1.25, h * 1.05], fill=55)
    glow = glow.filter(ImageFilter.GaussianBlur(160))
    bg = Image.composite(Image.new("RGB", (w, h), (255, 255, 255)), bg, glow)
    if boxes:
        sh = Image.new("L", (w, h), 0)
        sd = ImageDraw.Draw(sh)
        for (x, y, bw, bh, r) in boxes:
            sd.rounded_rectangle([x, y + 22, x + bw, y + bh + 34], radius=r, fill=170)
        sh = sh.filter(ImageFilter.GaussianBlur(38))
        bg = Image.composite(Image.new("RGB", (w, h), (0, 0, 0)), bg, sh)
    return bg


def chip(d, x, y, text, f):
    """알약 모양 라벨. 흰 바탕에 진한 글자 — 그러데이션 위에서 제일 잘 읽힙니다."""
    tw = d.textlength(text, font=f)
    ph, pw = 22, 30
    hgt = f.size + ph
    d.rounded_rectangle([x, y, x + tw + pw * 2, y + hgt], radius=hgt // 2, fill=(255, 255, 255, 235))
    d.text((x + pw, y + ph // 2 - 2), text, font=f, fill=(17, 24, 39, 255))
    return hgt


def reel_caption(w, h, big, small, label):
    layer = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    x, y = 72, 92
    fb, fs, fc = font(88, 2), font(36, 0), font(30, 2)
    for ln in big.split("\n"):
        d.text((x, y), ln, font=fb, fill=(255, 255, 255, 255)); y += int(88 * 1.16)
    y += 10
    d.text((x + 2, y), small, font=fs, fill=(255, 255, 255, 205)); y += 36 + 22
    if label:
        chip(d, x, y, label, fc)
    return layer


def reel_card(work, name, w, h, lines, sub, dur, pal, art=None, big=96, foot=None, brand=False):
    """숏폼 카드. motion_card 와 같은 움직임, 색만 셉니다."""
    bg = vivid(w, h, pal)
    fg = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(fg)
    fb, fm, ff = font(big, 2), font(38, 0), font(27, 0)
    x, y = 84, 300
    if brand:
        d.rectangle([x, y - 34, x + 60, y - 28], fill=(255, 255, 255, 255))
    for ln in lines:
        d.text((x, y), ln, font=fb, fill=(255, 255, 255, 255)); y += int(big * 1.2)
    if sub:
        y += 14
        for ln in sub:
            d.text((x + 2, y), ln, font=fm, fill=(255, 255, 255, 210)); y += 54
    if art is not None:
        conn = {"운전석"} if art == "one" else {"운전석", "조수석", "2열 좌", "2열 우"}
        a = cabin_art((700, 700), conn, scale=1.0, light=True)
        fg.alpha_composite(a, ((w - a.width) // 2, max(y + 50, 820)))
    if foot:
        d.text(((w - d.textlength(foot, font=ff)) / 2, h - 118), foot, font=ff, fill=(255, 255, 255, 220))
    bgp, fgp = work / f"{name}_bg.png", work / f"{name}_fg.png"
    bg.save(bgp); fg.save(fgp)
    out = work / f"{name}.mp4"
    run("ffmpeg", "-v", "error", "-y", "-loop", "1", "-i", str(bgp), "-loop", "1", "-i", str(fgp),
        "-filter_complex",
        f"[1:v]format=rgba,fade=t=in:st=0:d=0.4:alpha=1,"
        f"fade=t=out:st={dur - 0.4:.2f}:d=0.3:alpha=1[fg];"
        f"[0:v][fg]overlay=0:'30*pow(1-min(1,t/0.6),2)':shortest=1,fps=30,format=yuv420p[v]",
        "-map", "[v]", "-t", f"{dur}", "-c:v", "libx264", "-preset", "medium", "-crf", "18", str(out))
    return out


def build_short(indir, work, phone_mp4, at, out):
    SW, SH = 1080, 1920
    # 차량 화면은 폭을 꽉 채우고, 폰은 그 아래 — 둘 다 손안에서 읽혀야 합니다.
    cw = 1000
    ch = int(round(cw * CAR_CROP[1] / CAR_CROP[0] / 2) * 2)
    ph = 760
    pw = int(round(ph * 390 / 844 / 2) * 2)
    car_box = ((SW - cw) // 2, 372, cw, ch)
    phone_box = ((SW - pw) // 2, 372 + ch + 44, pw, ph)
    boxes = [(car_box[0] - 12, car_box[1] - 12, cw + 24, ch + 24, 22),
             (phone_box[0] - 12, phone_box[1] - 12, pw + 24, ph + 24, 54)]
    chm = work / "rchrome.png"
    chrome(SW, SH, [(*car_box, 12, 12), (*phone_box, 12, 44)]).save(chm)
    rcm, rpm = work / "rcmask.png", work / "rpmask.png"
    screen_mask(cw, ch, 12).save(rcm)
    screen_mask(pw, ph, 44).save(rpm)

    clips = []
    for i, (lines, sub, dur, art, pal) in enumerate(REEL_HOOK):
        clips.append((reel_card(work, f"rh{i}", SW, SH, lines, sub, dur, pal, art=art), dur))
    clips.append((reel_card(work, "rlogo", SW, SH, ["플레이시트"],
                            ["모든 좌석에 선곡권을.", "각자의 폰으로."], 2.6, "logo",
                            art="all", big=104, brand=True), 2.6))
    for i, (needle, lead, dur, big, small, pal, label) in enumerate(REEL):
        bg = work / f"rbg{i}.png"
        vivid(SW, SH, pal, boxes).save(bg)
        cap = work / f"rcap{i}.png"
        reel_caption(SW, SH, big, small, label).save(cap)
        seg = work / f"reel{i}.mp4"
        t = at(needle) + lead
        run("ffmpeg", "-v", "error", "-y",
            "-loop", "1", "-i", str(bg),
            "-ss", f"{t:.2f}", "-t", f"{dur}", "-i", str(indir / "car.mp4"),
            "-ss", f"{t:.2f}", "-t", f"{dur}", "-i", str(phone_mp4),
            "-i", str(chm), "-loop", "1", "-i", str(cap),
            "-loop", "1", "-i", str(rcm), "-loop", "1", "-i", str(rpm),
            "-filter_complex",
            f"[1:v]crop={CAR_CROP[0]}:{CAR_CROP[1]}:{CAR_CROP[2]}:{CAR_CROP[3]},"
            f"scale={cw}:{ch},fps=30,setsar=1,format=rgba[car0];[car0][5:v]alphamerge[car];"
            f"[2:v]scale={pw}:{ph},setsar=1,format=rgba[ph0];[ph0][6:v]alphamerge[ph];"
            f"[0:v][car]overlay={car_box[0]}:{car_box[1]}[a];"
            f"[a][ph]overlay={phone_box[0]}:{phone_box[1]}[b];"
            f"[b][3:v]overlay=0:0[c];"
            f"[4:v]format=rgba,fade=t=in:st=0.05:d=0.3:alpha=1,"
            f"fade=t=out:st={dur - 0.45:.2f}:d=0.3:alpha=1[cap];"
            f"[c][cap]overlay=0:'22*pow(1-min(1,t/0.5),2)':shortest=1[v]",
            "-map", "[v]", "-t", f"{dur}", "-pix_fmt", "yuv420p", "-c:v", "libx264",
            "-preset", "medium", "-crf", "19", "-r", "30", str(seg))
        clips.append((seg, dur))

    end_dur = 3.4
    clips.append((reel_card(work, "rend", SW, SH, ["플레이시트"],
                            ["차의 모든 좌석이", "DJ 부스가 된다"], end_dur, "end",
                            big=104, brand=True, foot="github.com/coalfire-create/playseat"), end_dur))

    silent = work / "reel_silent.mp4"
    xfade_concat(clips, silent, xf=0.28)

    # 음악. demo/music.py 가 만든 우리 곡입니다 — 남의 곡은 올릴 수 없습니다.
    music = Path(__file__).parent / "assets" / "reel_music.wav"
    if not music.exists():
        run(sys.executable, str(Path(__file__).parent / "music.py"), "--sec", "48", "--out", str(music))
    total = probe(silent)
    run("ffmpeg", "-v", "error", "-y", "-i", str(silent), "-i", str(music),
        "-filter_complex",
        f"[1:a]atrim=0:{total:.2f},afade=t=out:st={total - 1.6:.2f}:d=1.6,"
        f"loudnorm=I=-15:TP=-1.5:LRA=9[a]",
        "-map", "0:v", "-map", "[a]", "-c:v", "copy", "-c:a", "aac", "-b:a", "192k",
        "-movflags", "+faststart", "-shortest", str(out))


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--in", dest="indir", default="recording")
    ap.add_argument("--out", dest="outdir", default="영상")
    a = ap.parse_args()
    if not shutil.which("ffmpeg"):
        sys.exit("ffmpeg 이 필요합니다.")
    build(Path(a.indir), Path(a.outdir))
