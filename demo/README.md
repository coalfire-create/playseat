# demo

에뮬레이터와 동승자 폰을 **손으로 누르지 않고** 움직이는 스크립트들입니다.
의존성 없음 — Node 22+ 내장 WebSocket·fetch 만 씁니다.

| 파일 | 역할 |
|---|---|
| `scenario.mjs` | 시연 시나리오 자동 실행 (영상 녹화용) |
| `record.mjs` | 차량 화면 + 폰 화면 동시 녹화 |
| `compose.py` | 녹화본을 자막 얹은 한 편으로 편집 (가로 시연영상 + 세로 숏폼) |
| `shots.mjs` | 화면 시안 15장 캡처 |
| `console.mjs` | Pleos Virtual Console 신호 주입 (좌석·기어·와이퍼·전조등·조향·충전) |
| `phones.mjs` | 동승자 폰 흉내 (좌석 claim · 검색 · 곡 추가 · 투표) |
| `mock-car.mjs` | 에뮬레이터 없이 차량 앱 흉내 |

## 준비

```bash
# 에뮬레이터 + 차량 앱이 떠 있는 상태에서
adb forward tcp:8088 tcp:8088          # Virtual Console
cd hub-server && ./gradlew run         # 허브 + 동승자 웹 (8080)
```

## 시연 시나리오

```bash
node demo/scenario.mjs            # 전체 (약 4분)
node demo/scenario.mjs --fast     # 대기 시간 단축 (리허설)
node demo/scenario.mjs --step     # 장면마다 Enter (라이브 시연·PT)
node demo/scenario.mjs --from 5   # 5번 장면부터
node demo/scenario.mjs --reset    # 신호만 초기화
```

장면 순서: 탑승 → 폰 연결 → 차가 먼저 권함 → 뒷자리 6살이 거절하고 아기상어 →
연달아 넣기 → 조수석·2열 좌가 각자 폰에서 추가(영상 속 폰은 실제로 타이핑) →
스킵 투표 2/2 → 주행 → 비 → 터널 → 굽잇길 → 정차 → 충전 라운지 → 하차.

영상 속 폰(2열 좌)은 `record.mjs` 가 띄운 헤드리스 Chrome 입니다. `scenario.mjs` 가
CDP 로 붙어 글자를 치고 '추가'·'건너뛰기' 를 누르므로, 구경만 하는 폰이 아니라
실제로 쓰는 폰이 찍힙니다.

**2열 좌는 비워 둡니다.** 브라우저로 `http://localhost:8080` 을 열고 그 자리를
직접 잡으면, 사람이 쓰는 폰 화면이 영상에 같이 들어갑니다.

## 시연 영상

```bash
node demo/record.mjs              # recording/ 에 원본 (약 3분)
python3 demo/compose.py           # 영상/ 에 완성본
```

`record.mjs` 가 세 가지를 같은 시각 기준으로 묶습니다 — 에뮬레이터 `screenrecord`,
헤드리스 Chrome 의 폰 화면 프레임, 그리고 `scenario.mjs` 가 남기는 장면 시각(`cues.json`).
`compose.py` 는 그 시각으로 잘라 자막을 얹습니다. 눈으로 장면을 찾지 않으므로
다시 녹화해도 같은 편집이 그대로 나옵니다. 자막 문구와 길이는 `compose.py`
맨 위의 `SEGMENTS` 에 모아 두었습니다.

결과물

| 파일 | 규격 |
|---|---|
| `시연영상_플레이시트.mp4` | 1920×1080 · 약 1분 50초 (문제 제기 카드 → 시연 → 마무리) |
| `숏폼_플레이시트.mp4` | 1080×1920 · 약 40초 · 배경음악 포함 |

본편은 기능 나열이 아니라 **문제 → 해결** 순서입니다. 앞 15초는 "차와 연결된 폰은
운전석 하나뿐이고, 그 한 사람은 운전 중" 이라는 문제를 카드로 보여 주고, 그 다음에
탑승자 전원이 각자 폰으로 큐를 채우는 장면이 나옵니다. 카드 문구와 길이는
`compose.py` 의 `PROBLEM` · `SHORT_HOOK` 에 있습니다.

본편에는 소리를 넣지 않았습니다. 음악 앱 시연이라 남의 곡을 깔면 저작권 문제가 생기고,
시연 내용은 자막으로 전달됩니다. 숏폼의 배경음악은 `demo/music.py` 가 numpy 로 합성한
우리 곡(112 BPM)이라 어디에 올려도 됩니다.

`compose.py` 는 ffmpeg 와 Pillow 가 필요합니다. macOS 의 ffmpeg 에는 `drawtext`
필터가 빠져 있는 경우가 많아 자막은 Pillow 로 PNG 를 만들어 얹습니다.

## 화면 시안

```bash
node demo/shots.mjs                 # docs/screens/ 에 15장
node demo/shots.mjs --no-phone      # 차량 화면만 (Chrome 없이)
```

폰 화면은 헤드리스 Chrome 을 CDP 로 직접 몰아 390×844 @2x 로 찍습니다.
차량 화면은 `adb exec-out screencap` 후 앱 영역만 잘라냅니다(Pillow 가 있으면).

## 잔여 ETA 주입 — 도착 맞춤 편성을 보여 주려면

도착 맞춤 편성은 내비의 잔여 ETA 가 있어야 화면에 나타납니다. 에뮬레이터 기본
위치가 국내가 아니어서 경로를 만들 수 없으므로, 차량 앱이 열어 둔 통로로
남은 시간을 넣습니다. 없는 신호를 있는 척 만드는 대신 **넣는 길을 공개**해
두었고, 시연 영상 자막에도 주입했다고 적습니다.

```bash
adb shell am broadcast --user 10 \
  -a dev.semicolon.playseat.DEMO_ETA --ei seconds 180   # 3분
adb shell am broadcast --user 10 \
  -a dev.semicolon.playseat.DEMO_ETA --ei seconds -1    # 해제
```

주입값은 1초마다 줄고, 내비가 실제 경로를 주기 시작하면 그때부터 무시됩니다.
`scenario.mjs` 와 `shots.mjs` 는 이 값을 **큐에서 역산해서** 넣습니다 —
고정값을 넣으면 그때그때 큐 길이에 따라 도착선이 엉뚱한 데 떨어져
"맞출 조합이 없습니다" 가 뜨고, 그러면 보여 줄 장면이 비어 버립니다.

## 탑승 코드

차량 화면에 네 자리 코드가 뜨고 QR 에도 실립니다. 코드는 **허브로 나가지
않으므로** 흉내 폰은 기기 로그에서 읽어 씁니다.

```bash
adb logcat -d -s CarViewModel:I | grep '탑승 코드'
```

사람이 QR 을 찍어 받는 것과 같은 값입니다. 코드를 못 읽으면 좌석 잡기가
`BAD_JOIN_CODE` 로 전부 거절되므로, 두 스크립트 모두 시작할 때 한 번 읽습니다.

## 소리

곡이 실제로 재생됩니다. 다만 `adb screenrecord` 는 오디오를 담지 못해서
**시연 영상에는 소리가 없습니다.** 재생이 실제로 일어나는지는 이렇게 봅니다.

```bash
adb shell dumpsys media_session | grep -A3 PlaySeat    # state=PLAYING, 곡명·아티스트
adb logcat -d -s PlaySeatAudio                          # 폴백이 걸리면 여기 남습니다
```

## 신호 주입만

```bash
node demo/console.mjs           # 지금 신호 상태 덤프
node demo/console.mjs --probe   # 주입 가능한 항목 확인 (넣고 되돌립니다)
node demo/console.mjs --hold    # Virtual Console 화면을 손으로 조작할 때 세션 유지
```

Virtual Console 은 **브라우저가 보고 있는 동안에만** 신호를 받습니다
(`POST /browser/session` 을 1초마다). `console.mjs` 가 그 하트비트를 대신하고,
DDS 전송이 실제로 열릴 때까지(`availability.available`) 기다린 뒤 돌려줍니다.
이걸 안 기다리면 첫 신호가 조용히 묻혀서 시연 첫 장면만 틀리게 시작합니다.

## 차량 앱 없이

```bash
node demo/mock-car.mjs
node demo/mock-car.mjs --charging            # 충전 라운지 모드로 시작
node demo/mock-car.mjs --driving --eta 240   # 주행 + 랜딩 구간으로 시작
```

실행 중 키 입력으로 신호를 주입합니다.

| 키 | 동작 |
|---|---|
| `d` | 주행/정차 전환 |
| `c` | 충전 토글 |
| `e` | ETA 4분 (랜딩 구간) |
| `1`~`5` | 좌석 착석 토글 (R1L, R1R, R2L, R2C, R2R) |
| `n` | 다음 곡 |
| `q` | 종료 |

## 에뮬레이터가 이상할 때

- **화면이 까맣게 찍힌다** — 렌더러가 멈춘 것입니다. 에뮬레이터를 다시 띄우세요.
  `shots.mjs` 는 한 번 깨워서 다시 찍고, 그래도 까맣면 알려 줍니다.
- **신호를 넣었는데 앱이 모른다** — `node demo/console.mjs` 로 값이 들어갔는지 먼저
  확인하세요. 값은 맞는데 앱이 모르면 `availability.available` 이 false 인 상태에서
  넣은 것입니다.
- **2열 가운데가 늘 빈 좌석** — Virtual Console 에 그 좌석 항목이 없습니다.
  에뮬레이터의 한계이고 실차에서는 제공됩니다.
- **폰 화면이 '재연결 중' 으로 깜빡인다** — 같은 좌석(deviceId)으로 두 군데가
  붙어 있습니다. 앞선 실행에서 남은 헤드리스 Chrome 이 흔한 원인이라
  `record.mjs` 가 시작할 때 정리합니다. 손으로 연 탭이 있으면 닫으세요.
