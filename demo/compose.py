#!/usr/bin/env python3
"""
시연 영상 편집.

`record.mjs` 가 남긴 차량 영상·폰 프레임·장면 기록을 한 편으로 붙입니다.
장면을 눈으로 찾아 자르지 않고 `cues.json` 의 시각으로 자르기 때문에,
다시 녹화해도 같은 편집이 그대로 나옵니다.

    python3 demo/compose.py --in recording --out 영상

만드는 것
    시연영상_플레이시트.mp4        1920x1080 · 약 2분 · 심사 제출용
    숏폼_플레이시트.mp4            1080x1920 · 약 35초 · 선택 제출용

필요한 것: ffmpeg, Python Pillow.
자막은 Pillow 로 PNG 를 만들어 얹습니다 — macOS 의 ffmpeg 에는 drawtext 가
빠져 있는 경우가 많아 글자를 ffmpeg 에 맡기지 않습니다.
"""

import argparse, json, os, shutil, subprocess, sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

FONT = "/System/Library/Fonts/AppleSDGothicNeo.ttc"
BG = (18, 18, 20)
FG = (255, 255, 255)
DIM = (150, 152, 158)
ACCENT = (90, 160, 255)

# 에뮬레이터 전체 화면에서 앱이 차지하는 영역
CAR_CROP = (1688, 1210, 856, 88)
LEAD = 2.5          # screenrecord 가 실제로 물리기까지의 여유 (record.mjs 와 같은 값)


def font(size, weight=0):
    return ImageFont.truetype(FONT, size, index=weight)


def run(*args, **kw):
    subprocess.run(args, check=True,
                   stdout=kw.get("out", subprocess.DEVNULL),
                   stderr=subprocess.STDOUT)


# ── 장면 ────────────────────────────────────────────────────────────────
# (cues.json 에서 찾을 문구, 그 시각으로부터의 여유(초), 길이(초), 자막 제목, 자막 설명)
#
# 여유를 두는 이유: 신호를 넣은 순간부터 차량 화면이 바뀌기까지 한 박자 걸립니다.
# 장면 표시 시각에 딱 맞춰 자르면 자막은 '비' 인데 화면은 아직 이전 분위기입니다.
# 굽잇길은 조향 변동이 쌓여야 판정되므로 여유가 더 깁니다.
SEGMENTS = [
    ("좌석이 곧 입장권", 0.5, 8.0,
     "좌석이 곧 입장권",
     "착석 신호로 탑승자를 자동 인식합니다. 초대도, 세션 개설도 없습니다"),
    ("최초 1회 QR", 0.5, 5.4,
     "최초 1회만 QR",
     "다음부터는 타기만 하면 자동으로 연결됩니다"),
    ("아무도 안 넣으면 차가 먼저 권한다", 1.0, 6.0,
     "운전석 차례 — 큐가 비어 있습니다",
     "운전자는 폰으로 곡을 넣지 않습니다. 음성으로만 참여합니다"),
    ("추천이 떴습니다", 0.5, 8.5,
     "아무도 안 넣으면 차가 먼저 권한다",
     "지금 분위기에 맞는 5곡과 20초 카운트다운"),
    ("그대로 담겼습니다", 0.5, 4.0,
     "멈추지 않으면 그대로 담깁니다",
     "폰에서 '괜찮아요' 를 누르면 담지 않습니다 — 차례와 무관하게 누구든"),
    ("엄마 →", 0.5, 7.0,
     "선착순이 아니라 좌석 라운드로빈",
     "자기 차례에 한 곡. 좌석 위 번호가 곧 순서입니다"),
    ("서아 →", -4.0, 6.0,
     "제한시간이 지나면 다음 좌석으로",
     "한 사람이 연속으로 독점할 수 없습니다"),
    ("주행하면 조작 권한이 동승자 폰으로", 1.5, 6.5,
     "주행하면 조작 권한이 이사한다",
     "운전자 화면은 '지금 곡 + 버튼 2개'. 검색·목록·키보드가 잠깁니다"),
    ("날씨 API가 아니라 와이퍼로 읽는다", 2.0, 7.0,
     "비 — 날씨 API가 아니라 와이퍼로 읽는다",
     "터널에서도 지하주차장에서도 맞습니다. 화면·폰·실내 조명이 같이 바뀝니다"),
    ("전조등이 켜지면 밤처럼", 2.0, 6.0,
     "터널 — 전조등이 켜지면 밤처럼",
     "왜 그렇게 판정했는지 근거를 같이 보여 줍니다"),
    ("지도 API 없이 조향 변동으로 지형을", 4.5, 6.8,
     "굽잇길 — 지도 API 없이 조향 변동으로",
     "최근 12초 조향 변동폭. 산길과 고속도로는 다릅니다"),
    ("정차하면 운전자 화면이 다시 열린다", 1.5, 5.5,
     "정차하면 운전자 화면이 다시 열린다",
     "전체 큐·검색·좌석 현황이 돌아옵니다"),
    ("충전 중에는 차례 없이 모두 추가", 2.5, 9.3,
     "충전 라운지 — 차례 없이 모두",
     "정차해 있고 시간이 있는 상태입니다"),
    ("도착하면 드라이브 앨범이 남는다", 1.0, 5.0,
     "도착하면 남는 것",
     "함께 들은 곡이 드라이브 앨범으로 각자 폰에 전달됩니다"),
]

# 숏폼에 쓸 장면 (문구, 여유, 길이, 큰 글자, 작은 글자)
SHORT = [
    ("최초 1회 QR", 2.0, 6.0, "타면 끝",
     "착석 신호만으로\n탑승자를 자동 인식합니다"),
    ("추천이 떴습니다", 0.5, 7.0, "아무도 안 넣으면\n차가 먼저 권한다",
     "분위기에 맞는 5곡\n20초 뒤 자동으로 담김"),
    ("주행하면 조작 권한이 동승자 폰으로", 1.5, 6.5, "주행하면\n권한이 이사한다",
     "운전자 화면은 잠기고\n동승자 폰이 받는다"),
    ("날씨 API가 아니라 와이퍼로 읽는다", 2.5, 7.0, "비는 와이퍼가 안다",
     "날씨 API 없이\n차의 신호로 분위기를"),
]


# ── 자막 이미지 ─────────────────────────────────────────────────────────
def caption(size, title, sub, scale=1.0):
    """투명 배경에 자막만 그린 전체 캔버스 PNG."""
    W, H = size
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    ft = font(int(44 * scale), 2)
    fs = font(int(26 * scale), 0)
    d.text((72, int(46 * scale)), title, font=ft, fill=FG)
    if sub:
        d.text((74, int(108 * scale)), sub, font=fs, fill=DIM)
    return im


def card(size, lines, sub=None, foot=None):
    """제목·마무리 카드."""
    W, H = size
    im = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(im)
    fb = font(84, 2)
    fm = font(36, 0)
    ff = font(28, 0)
    y = H // 2 - 40 * len(lines) - (40 if sub else 0)
    for ln in lines:
        w = d.textlength(ln, font=fb)
        d.text(((W - w) / 2, y), ln, font=fb, fill=FG)
        y += 104
    if sub:
        y += 16
        for ln in sub:
            w = d.textlength(ln, font=fm)
            d.text(((W - w) / 2, y), ln, font=fm, fill=DIM)
            y += 52
    if foot:
        w = d.textlength(foot, font=ff)
        d.text(((W - w) / 2, H - 110), foot, font=ff, fill=ACCENT)
    return im


def frame_chrome(size, car_box, phone_box):
    """화면 둘레의 테두리와 이름표. 한 번 그려 영상 내내 얹습니다."""
    W, H = size
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    f = font(24, 0)
    for box, label in ((car_box, "차량 디스플레이"), (phone_box, "동승자 폰")):
        x, y, w, h = box
        d.rectangle([x - 1, y - 1, x + w, y + h], outline=(70, 72, 78))
        d.text((x, y + h + 14), label, font=f, fill=DIM)
    return im


# ── 본편 ────────────────────────────────────────────────────────────────
def build(indir: Path, outdir: Path):
    meta = json.loads((indir / "meta.json").read_text())
    cues = json.loads((indir / "cues.json").read_text())["cues"]
    frames = json.loads((indir / "frames.json").read_text())
    work = outdir / "작업"
    work.mkdir(parents=True, exist_ok=True)

    # 장면 시각 → 영상 시각
    off = meta["scenarioStart"] + LEAD

    def at(needle):
        for c in cues:
            if needle in c["text"]:
                return c["t"] + off
        raise SystemExit(f"장면을 찾지 못했습니다: {needle}")

    # 1. 폰 프레임 → 영상. 찍힌 시각을 그대로 길이로 씁니다(가변 프레임레이트).
    print("1/6  폰 화면을 영상으로")
    lst = work / "phone.txt"
    car_dur = float(subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration",
         "-of", "csv=p=0", str(indir / "car.mp4")],
        capture_output=True, text=True).stdout.strip())
    with lst.open("w") as f:
        first = indir / "phone" / frames[0]["f"]
        f.write(f"file '{first.resolve()}'\nduration {frames[0]['t'] + LEAD:.3f}\n")
        for a, b in zip(frames, frames[1:]):
            f.write(f"file '{(indir / 'phone' / a['f']).resolve()}'\n")
            f.write(f"duration {max(0.02, b['t'] - a['t']):.3f}\n")
        last = indir / "phone" / frames[-1]["f"]
        tail = max(0.1, car_dur - (frames[-1]["t"] + LEAD))
        f.write(f"file '{last.resolve()}'\nduration {tail:.3f}\n")
        f.write(f"file '{last.resolve()}'\n")
    phone_mp4 = work / "phone.mp4"
    run("ffmpeg", "-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", str(lst),
        "-vf", "fps=30,scale=-2:860", "-pix_fmt", "yuv420p",
        "-c:v", "libx264", "-preset", "veryfast", "-crf", "20", str(phone_mp4))

    # 2. 차량 + 폰을 한 화면에
    print("2/6  두 화면을 한 화면에")
    W, H = 1920, 1080
    cw, ch = 1296, 929
    car_box = (72, 138, cw, ch)
    pw = int(round(860 * 390 / 844 / 2) * 2)
    phone_box = (W - 72 - pw, 150, pw, 860)
    chrome = work / "chrome.png"
    frame_chrome((W, H), car_box, phone_box).save(chrome)

    stage = work / "stage.mp4"
    run("ffmpeg", "-v", "error", "-y",
        "-i", str(indir / "car.mp4"), "-i", str(phone_mp4), "-i", str(chrome),
        "-filter_complex",
        f"color=c=0x{BG[0]:02x}{BG[1]:02x}{BG[2]:02x}:s={W}x{H}:d={car_dur}[bg];"
        f"[0:v]crop={CAR_CROP[0]}:{CAR_CROP[1]}:{CAR_CROP[2]}:{CAR_CROP[3]},"
        f"scale={cw}:{ch},fps=30,setsar=1[car];"
        f"[1:v]scale={pw}:860,setsar=1[ph];"
        f"[bg][car]overlay={car_box[0]}:{car_box[1]}[a];"
        f"[a][ph]overlay={phone_box[0]}:{phone_box[1]}[b];"
        f"[b][2:v]overlay=0:0[v]",
        "-map", "[v]", "-pix_fmt", "yuv420p", "-c:v", "libx264",
        "-preset", "medium", "-crf", "19", str(stage))

    # 3. 장면별로 잘라 자막을 얹습니다
    print("3/6  장면을 자르고 자막을 얹습니다")
    parts = []
    for i, (needle, lead, dur, title, sub) in enumerate(SEGMENTS):
        cap = work / f"cap{i:02d}.png"
        caption((W, H), title, sub).save(cap)
        seg = work / f"seg{i:02d}.mp4"
        run("ffmpeg", "-v", "error", "-y", "-ss", f"{at(needle) + lead:.2f}", "-t", f"{dur}",
            "-i", str(stage), "-i", str(cap),
            "-filter_complex", "[0:v][1:v]overlay=0:0[v]", "-map", "[v]",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "medium",
            "-crf", "19", "-r", "30", str(seg))
        parts.append(seg)
        print(f"     {i+1:2}/{len(SEGMENTS)}  {title}")

    # 4. 제목·마무리 카드
    print("4/6  제목과 마무리")
    head = work / "head.png"
    card((W, H), ["플레이시트 (PlaySeat)"],
         ["차에 타는 순간 모든 좌석이 DJ 부스가 된다",
          "Pleos Connect 차량용 앱 · 팀 Semicolon"],
         "에뮬레이터 실제 구동 화면 · 편집 외에 가공하지 않았습니다").save(head)
    tail = work / "tail.png"
    card((W, H), ["폰은 알 수 없는 것들"],
         ["누가 어느 좌석에 탔는가 · 지금 주행 중인가",
          "비가 오는가 · 굽잇길인가 · 충전 중인가",
          "이 신호들이 있어야 성립하는 앱입니다"],
         "github.com/coalfire-create/playseat").save(tail)
    head_mp4, tail_mp4 = work / "head.mp4", work / "tail.mp4"
    for png, mp4, d in ((head, head_mp4, 5), (tail, tail_mp4, 7)):
        run("ffmpeg", "-v", "error", "-y", "-loop", "1", "-t", str(d), "-i", str(png),
            "-vf", "fps=30,format=yuv420p", "-c:v", "libx264", "-preset", "medium",
            "-crf", "19", str(mp4))

    # 5. 이어 붙이기
    print("5/6  이어 붙이기")
    order = [head_mp4] + parts + [tail_mp4]
    cat = work / "list.txt"
    cat.write_text("".join(f"file '{p.resolve()}'\n" for p in order))
    final = outdir / "시연영상_플레이시트.mp4"
    run("ffmpeg", "-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", str(cat),
        "-vf", "fade=t=in:st=0:d=0.4", "-c:v", "libx264", "-preset", "slow",
        "-crf", "20", "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(final))

    # 6. 숏폼 — 세로. 폰을 크게 두고 차량 화면을 위에 얹습니다.
    print("6/6  숏폼")
    SW, SH = 1080, 1920
    sparts = []
    for i, (needle, lead, dur, big, small) in enumerate(SHORT):
        cap = Image.new("RGBA", (SW, SH), (0, 0, 0, 0))
        d = ImageDraw.Draw(cap)
        fb, fs = font(70, 2), font(34, 0)
        y = 70
        for ln in big.split("\n"):
            d.text((60, y), ln, font=fb, fill=FG); y += 86
        y += 10
        for ln in small.split("\n"):
            d.text((62, y), ln, font=fs, fill=DIM); y += 46
        capf = work / f"scap{i}.png"
        cap.save(capf)
        seg = work / f"short{i}.mp4"
        t = at(needle) + lead
        run("ffmpeg", "-v", "error", "-y", "-ss", f"{t:.2f}", "-t", f"{dur}",
            "-i", str(indir / "car.mp4"), "-ss", f"{t:.2f}", "-t", f"{dur}",
            "-i", str(phone_mp4), "-i", str(capf),
            "-filter_complex",
            f"color=c=0x{BG[0]:02x}{BG[1]:02x}{BG[2]:02x}:s={SW}x{SH}:d={dur}[bg];"
            f"[0:v]crop={CAR_CROP[0]}:{CAR_CROP[1]}:{CAR_CROP[2]}:{CAR_CROP[3]},"
            f"scale=1020:-2,fps=30,setsar=1[car];"
            f"[1:v]scale=-2:760,setsar=1[ph];"
            f"[bg][car]overlay=30:330[a];"
            f"[a][ph]overlay=(W-w)/2:1110[b];"
            f"[b][2:v]overlay=0:0[v]",
            "-map", "[v]", "-pix_fmt", "yuv420p", "-c:v", "libx264",
            "-preset", "medium", "-crf", "20", "-r", "30", str(seg))
        sparts.append(seg)

    scard = work / "scard.png"
    sc = Image.new("RGB", (SW, SH), BG)
    d = ImageDraw.Draw(sc)
    fb, fm = font(78, 2), font(34, 0)
    for j, ln in enumerate(["플레이시트", "PlaySeat"]):
        w = d.textlength(ln, font=fb); d.text(((SW - w) / 2, 820 + j * 96), ln, font=fb, fill=FG)
    for j, ln in enumerate(["차의 모든 좌석이", "DJ 부스가 된다"]):
        w = d.textlength(ln, font=fm); d.text(((SW - w) / 2, 1040 + j * 50), ln, font=fm, fill=DIM)
    w = d.textlength("github.com/coalfire-create/playseat", font=fm)
    d.text(((SW - w) / 2, 1200), "github.com/coalfire-create/playseat", font=fm, fill=ACCENT)
    sc.save(scard)
    scard_mp4 = work / "scard.mp4"
    run("ffmpeg", "-v", "error", "-y", "-loop", "1", "-t", "3.5", "-i", str(scard),
        "-vf", "fps=30,format=yuv420p", "-c:v", "libx264", "-preset", "medium",
        "-crf", "20", str(scard_mp4))

    scat = work / "slist.txt"
    scat.write_text("".join(f"file '{p.resolve()}'\n" for p in sparts + [scard_mp4]))
    short = outdir / "숏폼_플레이시트.mp4"
    run("ffmpeg", "-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", str(scat),
        "-c:v", "libx264", "-preset", "slow", "-crf", "20", "-pix_fmt", "yuv420p",
        "-movflags", "+faststart", str(short))

    for p in (final, short):
        dur = subprocess.run(["ffprobe", "-v", "error", "-show_entries",
                              "format=duration", "-of", "csv=p=0", str(p)],
                             capture_output=True, text=True).stdout.strip()
        mb = p.stat().st_size / 1048576
        print(f"\n{p}  —  {float(dur):.0f}초 · {mb:.1f} MB")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--in", dest="indir", default="recording")
    ap.add_argument("--out", dest="outdir", default="영상")
    a = ap.parse_args()
    if not shutil.which("ffmpeg"):
        sys.exit("ffmpeg 이 필요합니다.")
    build(Path(a.indir), Path(a.outdir))
