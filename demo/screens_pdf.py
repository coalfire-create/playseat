#!/usr/bin/env python3
"""
화면 시안 PDF.

`shots.mjs` 가 찍은 docs/screens/*.png 13장을 제목·설명을 붙여 A4 가로 13쪽으로 묶습니다.

    python3 demo/screens_pdf.py --out "제출/Semicolon_플레이시트_화면시안.pdf"

필요한 것: Pillow (macOS 는 /usr/bin/python3 에 있습니다).
"""
import argparse, glob, os
from PIL import Image, ImageDraw, ImageFont

CAPS = {
 "01": ("정차 중 — 좌석이 곧 입장권", "착석이 감지된 좌석이 차 그림 위에 뜹니다. 앉은 사람은 누구나 바로 넣습니다"),
 "02": ("동승자 폰 — 최초 1회 좌석 선택", "차량 화면과 같은 차 그림. 다음부터는 타기만 하면 자동 연결"),
 "03": ("아무도 안 넣을 때 — 차가 먼저 권한다", "분위기에 맞는 다섯 곡과 20초 카운트다운"),
 "04": ("같은 추천을 폰에서", "'괜찮아요' 를 누르면 담지 않습니다 — 누구든"),
 "05": ("각 좌석이 넣은 곡", "차례가 없습니다. 지금 나오는 곡을 고른 자리가 좌석도에서 켜집니다"),
 "06": ("동승자 폰 메인", "좌석 · 지금 재생 · 검색 · 대기열"),
 "07": ("주행 시작 — 운전자 화면 축약", "'지금 곡 + 버튼 2개'. 맨 위에 지금 분위기가 큰 띠로 붙습니다"),
 "08": ("같은 순간의 폰", "주행 중에는 선곡 조작이 동승자 폰으로 이전됩니다"),
 "09": ("비 — 와이퍼가 분위기를 바꾼다", "와이퍼 Low → 판정 '비 오는 날' → 화면·폰·실내 조명이 같은 색으로"),
 "10": ("폰 배경도 같은 색으로", "차량 화면·폰·실내 조명 세 곳이 동시에 바뀝니다"),
 "11": ("충전 라운지", "정차해 있고 시간이 있는 상태"),
 "12": ("야간 모드 — 차량 설정을 따름", "밤에 흰 화면이 켜지면 운전에 방해가 됩니다"),
 "13": ("폰 야간 모드", "폰은 폰의 설정을 따릅니다"),
}
FONT = "/System/Library/Fonts/AppleSDGothicNeo.ttc"


def build(indir, out):
    big = ImageFont.truetype(FONT, 34, index=2)
    small = ImageFont.truetype(FONT, 24, index=0)
    num = ImageFont.truetype(FONT, 22, index=0)
    pages = []
    files = sorted(glob.glob(os.path.join(indir, "*.png")))
    for f in files:
        k = os.path.basename(f)[:2]
        title, sub = CAPS.get(k, (os.path.basename(f), ""))
        im = Image.open(f).convert("RGB")
        W, H = 1754, 1240
        canvas = Image.new("RGB", (W, H), "white")
        top = 150
        sc = min((W - 120) / im.width, (H - top - 70) / im.height)
        im2 = im.resize((int(im.width * sc), int(im.height * sc)), Image.LANCZOS)
        x = (W - im2.width) // 2
        canvas.paste(im2, (x, top))
        d = ImageDraw.Draw(canvas)
        d.rectangle([x - 1, top - 1, x + im2.width, top + im2.height], outline=(214, 214, 214))
        d.text((60, 52), title, font=big, fill=(0, 0, 0))
        d.text((60, 100), sub, font=small, fill=(124, 124, 124))
        d.text((W - 130, 58), f"{int(k)} / {len(files)}", font=num, fill=(150, 150, 150))
        d.text((60, H - 44), "플레이시트(PlaySeat) · 팀 Semicolon · Pleos Connect 에뮬레이터 실제 화면",
               font=num, fill=(150, 150, 150))
        pages.append(canvas)
    pages[0].save(out, save_all=True, append_images=pages[1:], resolution=150.0)
    print(f"{out}  {os.path.getsize(out) / 1048576:.1f} MB · {len(pages)}쪽")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--in", dest="indir", default="docs/screens")
    ap.add_argument("--out", default="docs/screens/화면시안.pdf")
    a = ap.parse_args()
    build(a.indir, a.out)
