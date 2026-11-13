# demo

에뮬레이터와 동승자 폰을 **손으로 누르지 않고** 움직이는 스크립트들입니다.
의존성 없음 — Node 22+ 내장 WebSocket·fetch 만 씁니다.

| 파일 | 역할 |
|---|---|
| `scenario.mjs` | 시연 시나리오 자동 실행 (영상 녹화용) |
| `record.mjs` | 차량 화면 + 폰 화면 동시 녹화 |
| `compose.py` | 녹화본을 자막 얹은 한 편으로 편집 (가로 시연영상 + 세로 숏폼) |
| `shots.mjs` | 화면 시안 13장 캡처 |
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

장면 순서: 탑승 → 폰 연결 → 좌석 턴 → 주행 → 비 → 굽잇길 → 터널 → 추천 →
도착 → 충전 라운지 → 하차.

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
| `시연영상_플레이시트.mp4` | 1920×1080 · 약 1분 43초 |
| `숏폼_플레이시트.mp4` | 1080×1920 · 약 30초 |

소리는 넣지 않았습니다. 음악 앱 시연이라 곡을 깔면 저작권 문제가 생기고,
시연 내용은 자막으로 전달됩니다. 내레이션은 필요하면 나중에 얹으면 됩니다.

`compose.py` 는 ffmpeg 와 Pillow 가 필요합니다. macOS 의 ffmpeg 에는 `drawtext`
필터가 빠져 있는 경우가 많아 자막은 Pillow 로 PNG 를 만들어 얹습니다.

## 화면 시안

```bash
node demo/shots.mjs                 # docs/screens/ 에 13장
node demo/shots.mjs --no-phone      # 차량 화면만 (Chrome 없이)
```

폰 화면은 헤드리스 Chrome 을 CDP 로 직접 몰아 390×844 @2x 로 찍습니다.
차량 화면은 `adb exec-out screencap` 후 앱 영역만 잘라냅니다(Pillow 가 있으면).

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
