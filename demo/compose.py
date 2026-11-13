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
    ("좌석이 곧 입장권", 0.5, 7.9,
     "좌석이 곧 입장권",
     "착석 신호로 탑승자를 자동 인식합니다. 초대도, 세션 개설도 없습니다"),
    ("최초 1회 QR", 0.5, 5.4,
     "최초 1회만 QR",
     "다음부터는 타기만 하면 자동으로 연결됩니다"),
    ("아무도 안 넣으면 차가 먼저 권한다", 1.0, 6.0,
     "아직 아무도 곡을 넣지 않았습니다",
     "차 안이 조용한 채로 흘러가는 시간"),
    ("추천이 떴습니다", 0.5, 9.0,
     "그러면 차가 먼저 권합니다",
     "지금 분위기에 맞는 다섯 곡과 20초"),
    ("그대로 담겼습니다", 0.5, 4.5,
     "멈추지 않으면 그대로 담깁니다",
     "폰에서 '괜찮아요' 를 누르면 담지 않습니다"),
    ("차례를 기다리지 않습니다", 0.5, 6.0,
     "듣고 싶은 걸 그냥 넣습니다",
     "차례도, 좌석별 상한도 없습니다"),
    ("한 사람이 연달아 넣어도 막지 않습니다", 1.0, 5.0,
     "한 사람이 연달아 넣어도 막지 않습니다",
     "규칙보다 눈치가 먼저 작동합니다. 그래도 불만이면 스킵 투표가 있습니다"),
    ("주행하면 조작 권한이 동승자 폰으로", 1.5, 6.5,
     "주행하면 조작 권한이 이사합니다",
     "운전자 화면은 '지금 곡 + 버튼 2개'. 검색·목록·키보드가 잠깁니다"),
    ("날씨 API가 아니라 와이퍼로 읽는다", 2.0, 6.8,
     "비 — 날씨 API가 아니라 와이퍼로",
     "터널에서도 지하주차장에서도 맞습니다. 화면·폰·실내 조명이 같이 바뀝니다"),
    ("전조등이 켜지면 밤처럼", 2.0, 6.0,
     "터널 — 전조등이 켜지면 밤처럼",
     "왜 그렇게 판정했는지 근거를 같이 보여 줍니다"),
    ("지도 API 없이 조향 변동으로 지형을", 4.5, 6.5,
     "굽잇길 — 지도 API 없이 조향으로",
     "최근 12초 조향 변동폭. 산길과 고속도로는 다릅니다"),
    ("정차하면 운전자 화면이 다시 열린다", 1.5, 5.5,
     "정차하면 운전자 화면이 다시 열립니다",
     "전체 큐·검색·좌석 현황이 돌아옵니다"),
    ("충전 중에는 라운지 모드", 2.5, 9.0,
     "충전 — 정차해 있고 시간이 있는 상태",
     "차 안에 머무는 20분이 하나의 모드가 됩니다"),
    ("도착하면 드라이브 앨범이 남는다", 1.0, 5.0,
     "도착하면 남는 것",
     "함께 들은 곡이 드라이브 앨범으로 각자 폰에 전달됩니다"),
]

# 숏폼 (문구, 여유, 길이, 큰 글자, 작은 글자)
SHORT = [
    ("좌석이 곧 입장권", 2.5, 5.5, "차에서 노래\n누가 틀어?", "보통은 운전석 한 명"),
    ("최초 1회 QR", 1.5, 4.0, "타면 전원\n자동으로 들어옵니다", "좌석 착석 신호로 인식"),
    ("추천이 떴습니다", 0.5, 6.5, "아무도 안 넣으면\n차가 먼저 권한다", "분위기에 맞는 5곡 · 20초"),
    ("주행하면 조작 권한이 동승자 폰으로", 1.5, 6.0, "달리면\n권한이 뒷자리로", "운전자 화면은 잠깁니다"),
    ("날씨 API가 아니라 와이퍼로 읽는다", 2.5, 6.0, "비는 와이퍼가 안다", "날씨 API 없이 차의 신호로"),
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


def chrome(w, h, devices):
    """기기 베젤. 화면 자리는 뚫어 두고 그 둘레만 그립니다."""
    layer = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    for (x, y, sw, sh, bez, radius) in devices:
        out = [x - bez, y - bez, x + sw + bez, y + sh + bez]
        d.rounded_rectangle(out, radius=radius + bez, fill=BEZEL + (255,))
        d.rounded_rectangle(out, radius=radius + bez, outline=EDGE + (255,), width=2)
        # 화면 자리를 뚫습니다 (ImageDraw 는 알파를 그대로 덮어씁니다)
        d.rounded_rectangle([x, y, x + sw, y + sh], radius=radius, fill=(0, 0, 0, 0))
    return layer


def labels(w, h, items):
    layer = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    f = font(23, 0)
    for (x, y, text) in items:
        d.text((x, y), text, font=f, fill=DIM + (255,))
    return layer


def caption(w, h, title, sub, x, y, big=46, small=27, rule=True):
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
    ch = 784
    cw = int(round(ch * CAR_CROP[0] / CAR_CROP[1] / 2) * 2)
    ph = 784
    pw = int(round(ph * 390 / 844 / 2) * 2)
    gap = 92
    x0 = (W - (cw + gap + pw)) // 2
    y0 = 180
    car_box = (x0, y0, cw, ch)
    phone_box = (x0 + cw + gap, y0, pw, ph)

    bg_png, chrome_png, label_png = work / "bg.png", work / "chrome.png", work / "labels.png"
    backdrop(W, H, [(x0 - 16, y0 - 16, cw + 32, ch + 32, 26),
                    (phone_box[0] - 14, y0 - 14, pw + 28, ph + 28, 58)]).save(bg_png)
    chrome(W, H, [(*car_box, 16, 12), (*phone_box, 14, 44)]).save(chrome_png)
    labels(W, H, [(x0 + 2, y0 + ch + 34, "차량 디스플레이"),
                  (phone_box[0] + 2, y0 + ph + 34, "동승자 폰")]).save(label_png)

    stage = work / "stage.mp4"
    if stage.exists():
        print("     (이미 있어 건너뜁니다)")
    else:
      run("ffmpeg", "-v", "error", "-y",
        "-loop", "1", "-i", str(bg_png),
        "-i", str(indir / "car.mp4"), "-i", str(phone_mp4),
        "-i", str(chrome_png), "-i", str(label_png),
        "-filter_complex",
        f"[1:v]crop={CAR_CROP[0]}:{CAR_CROP[1]}:{CAR_CROP[2]}:{CAR_CROP[3]},"
        f"scale={cw}:{ch},fps=30,setsar=1[car];"
        f"[2:v]scale={pw}:{ph},setsar=1[ph];"
        f"[0:v][car]overlay={car_box[0]}:{car_box[1]}[a];"
        f"[a][ph]overlay={phone_box[0]}:{phone_box[1]}[b];"
        f"[b][3:v]overlay=0:0[c];[c][4:v]overlay=0:0[v]",
        "-map", "[v]", "-t", f"{car_dur:.2f}", "-pix_fmt", "yuv420p",
        "-c:v", "libx264", "-preset", "medium", "-crf", "18", str(stage))

    # 3. 장면별로 자르고 자막을 넣고 빼기
    print("3/6  장면과 자막")
    parts = []
    for i, (needle, lead, dur, title, sub) in enumerate(SEGMENTS):
        cap = work / f"cap{i:02d}.png"
        caption(W, H, title, sub, x0, 56).save(cap)
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

    # 4. 제목·마무리
    print("4/6  제목과 마무리")
    head_png, tail_png = work / "head.png", work / "tail.png"
    card(W, H, ["플레이시트"],
         ["차에 타는 순간 모든 좌석이 DJ 부스가 된다",
          "Pleos Connect 차량용 앱 · 팀 Semicolon"],
         "에뮬레이터 실제 구동 화면 · 편집 외에 가공하지 않았습니다", big=96).save(head_png)
    card(W, H, ["폰은 알 수 없는 것들"],
         ["누가 어느 좌석에 탔는가 · 지금 주행 중인가",
          "비가 오는가 · 굽잇길인가 · 충전 중인가",
          "이 신호들이 있어야 성립하는 앱입니다"],
         "github.com/coalfire-create/playseat", big=78).save(tail_png)
    head, tail = work / "head.mp4", work / "tail.mp4"
    for png, mp4, d in ((head_png, head, 5.0), (tail_png, tail, 7.0)):
        run("ffmpeg", "-v", "error", "-y", "-loop", "1", "-t", f"{d}", "-i", str(png),
            "-vf", f"fps=30,fade=t=in:st=0:d=0.8,fade=t=out:st={d - 1.0:.2f}:d=0.9,format=yuv420p",
            "-c:v", "libx264", "-preset", "medium", "-crf", "18", str(mp4))

    # 5. 겹쳐 넘기며 잇기
    print("5/6  겹쳐 넘기며 잇기")
    clips = [(head, 5.0)] + parts + [(tail, 7.0)]
    final = outdir / "시연영상_플레이시트.mp4"
    xfade_concat(clips, final)

    # 6. 숏폼
    print("6/6  숏폼")
    short = outdir / "숏폼_플레이시트.mp4"
    build_short(indir, work, phone_mp4, at, short)

    for p in (final, short):
        print(f"\n{p}  —  {probe(p):.0f}초 · {p.stat().st_size / 1048576:.1f} MB")


def xfade_concat(clips, out):
    """크로스 디졸브로 잇습니다. 툭툭 끊기면 '자른 티' 가 그대로 납니다."""
    args = ["ffmpeg", "-v", "error", "-y"]
    for path, _ in clips:
        args += ["-i", str(path)]
    chain, prev, elapsed = [], "0:v", clips[0][1]
    for i in range(1, len(clips)):
        off = elapsed - XF
        tag = f"x{i}"
        chain.append(f"[{prev}][{i}:v]xfade=transition=fade:duration={XF}:offset={off:.3f}[{tag}]")
        prev = tag
        # xfade 출력 길이는 offset + 두 번째 입력 길이입니다. 겹친 만큼 줄어듭니다.
        elapsed = off + clips[i][1]
    args += ["-filter_complex", ";".join(chain), "-map", f"[{prev}]",
             "-c:v", "libx264", "-preset", "slow", "-crf", "19",
             "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(out)]
    run(*args)


def build_short(indir, work, phone_mp4, at, out):
    SW, SH = 1080, 1920
    # 세로 화면은 손안에서 봅니다. 작게 넣으면 뭘 보여 주는지 안 보입니다.
    # 큰 글자가 두 줄일 때 설명줄까지 들어갈 자리를 위에 비워 둬야 합니다.
    ch = 680
    cw = int(round(ch * CAR_CROP[0] / CAR_CROP[1] / 2) * 2)
    ph = 718
    pw = int(round(ph * 390 / 844 / 2) * 2)
    car_box = ((SW - cw) // 2, 384, cw, ch)
    phone_box = ((SW - pw) // 2, 1118, pw, ph)

    bg = work / "sbg.png"
    backdrop(SW, SH, [(car_box[0] - 14, car_box[1] - 14, cw + 28, ch + 28, 24),
                      (phone_box[0] - 12, phone_box[1] - 12, pw + 24, ph + 24, 52)]).save(bg)
    chm = work / "schrome.png"
    chrome(SW, SH, [(*car_box, 14, 12), (*phone_box, 12, 42)]).save(chm)

    clips = []
    for i, (needle, lead, dur, big, small) in enumerate(SHORT):
        cap = work / f"scap{i}.png"
        caption(SW, SH, big, small, 62, 104, big=80, small=35).save(cap)
        seg = work / f"short{i}.mp4"
        t = at(needle) + lead
        run("ffmpeg", "-v", "error", "-y",
            "-loop", "1", "-i", str(bg),
            "-ss", f"{t:.2f}", "-t", f"{dur}", "-i", str(indir / "car.mp4"),
            "-ss", f"{t:.2f}", "-t", f"{dur}", "-i", str(phone_mp4),
            "-i", str(chm), "-loop", "1", "-i", str(cap),
            "-filter_complex",
            f"[1:v]crop={CAR_CROP[0]}:{CAR_CROP[1]}:{CAR_CROP[2]}:{CAR_CROP[3]},"
            f"scale={cw}:{ch},fps=30,setsar=1[car];"
            f"[2:v]scale={pw}:{ph},setsar=1[ph];"
            f"[0:v][car]overlay={car_box[0]}:{car_box[1]}[a];"
            f"[a][ph]overlay={phone_box[0]}:{phone_box[1]}[b];"
            f"[b][3:v]overlay=0:0[c];"
            f"[4:v]format=rgba,fade=t=in:st=0.1:d=0.35:alpha=1,"
            f"fade=t=out:st={dur - 0.6:.2f}:d=0.35:alpha=1[cap];"
            f"[c][cap]overlay=0:0:shortest=1[v]",
            "-map", "[v]", "-t", f"{dur}", "-pix_fmt", "yuv420p", "-c:v", "libx264",
            "-preset", "medium", "-crf", "19", "-r", "30", str(seg))
        clips.append((seg, dur))

    end_png = work / "send.png"
    card(SW, SH, ["플레이시트"],
         ["차의 모든 좌석이", "DJ 부스가 된다"],
         "github.com/coalfire-create/playseat", big=84).save(end_png)
    end = work / "send.mp4"
    run("ffmpeg", "-v", "error", "-y", "-loop", "1", "-t", "3.2", "-i", str(end_png),
        "-vf", "fps=30,fade=t=in:st=0:d=0.5,format=yuv420p",
        "-c:v", "libx264", "-preset", "medium", "-crf", "19", str(end))
    clips.append((end, 3.2))
    xfade_concat(clips, out)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--in", dest="indir", default="recording")
    ap.add_argument("--out", dest="outdir", default="영상")
    a = ap.parse_args()
    if not shutil.which("ffmpeg"):
        sys.exit("ffmpeg 이 필요합니다.")
    build(Path(a.indir), Path(a.outdir))
