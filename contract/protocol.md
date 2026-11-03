# 플레이시트 통신 계약 v0.5

> 차량 앱(이정한) ↔ 허브 서버(이용준) ↔ 동승자 웹(이용준)
> 저장소로 옮길 위치: `contract/protocol.md`
> **이 문서가 두 사람의 유일한 접점입니다. 변경은 반드시 양쪽 합의 후 PR.**

## 구현 상태 (2026-10-31)

계약에 적혀 있다고 동작하는 것은 아닙니다. 지금 신뢰할 수 있는 범위입니다.

| 항목 | 차량 앱 | 허브 | 동승자 웹 |
|---|---|---|---|
| `state.sync` 기본 필드 | 동작 | 중계 | 표시 |
| `seat.claim` | 동작 | 중계 | 동작 |
| `queue.add` / `queue.remove` | 동작 | 중계 | 추가만 |
| `vote.skip` / `reaction.like` / `turn.pass` | 동작 | 중계 | skip·like |
| `reject` (+`seatId`) | 동작 | 브로드캐스트 | 표시 |
| `turn.notify` | 동작 | 브로드캐스트 | 표시 |
| `driveState` 전환에 따른 권한 이동 | 동작 | — | 동작 |
| `etaSeconds` / `queueTargetMs` | 동작 (Fake 값이 실제로 흐름) | — | 표시 |
| `mode` (CHARGING_LOUNGE) | 동작 | — | 동작 |
| `landing` | 동작 | — | 동작 |
| `queueTotalMs` | 동작 | — | 표시 |
| `turnRemainingMs` (v0.3) | 동작 — 1초마다 갱신 | — | 표시 |
| `artworkUrl` (v0.4) | 동작 — 앨범 커버 표시 | 검색 결과에 포함 + 목록 곡은 예열 | 전달 |
| `mood*` (v0.5) | 동작 — 와이퍼·전조등·조향·충전·도착으로 판정 | — | 표시 |
| `suggestions` (v0.5) | 동작 — 큐가 비면 자동 담기 | `mood.query` 에 응답 | 표시 + 담기/거절 |
| `drive.album` | 동작 | 중계 | 표시 |
| `search.query` / `search.result` | 해당 없음 | **동작** — 큐레이션 목록 74곡 + Deezer 보완 | 동작 |

계약 밖의 차량 앱 내부 상태입니다.

| 계층 | 상태 |
|---|---|
| 차량 신호 (좌석·기어) | **Pleos Vehicle SDK 연동 완료** (좌석 착석 + 안전벨트 폴백, 기어). 자동차 단말이 아니면 `FakeVehicleSignalSource` 로 자동 대체 |
| 차량 신호 (충전) | **연동 완료** — 충전 스위치 상태 + 충전구 개폐·배터리 잔량 변화로 갱신 |
| 재생 (`PlaybackGateway`) | `SimulatedPlaybackGateway` — 소리 없이 시간만 흐름. **MediaSession 위임 미구현** |
| 음성 (`VoiceController`) | Android 표준 TTS·SpeechRecognizer 로 동작. **Pleos TTS/STT SDK 미연동** |
| 잔여 ETA | **NaviHelper 연동 완료** — onDrivingInfo 의 destination.duration 사용. Fake 신호원에서는 1초마다 감소 |

즉 **계약상의 모든 메시지는 양쪽이 주고받을 수 있고**, 아직 실제 차량·실제 음원에
연결되지 않았을 뿐입니다. 동승자 웹은 차량 신호가 Fake 인지 실제인지 구분할 필요가 없습니다.

## 원칙

1. **차량 앱이 단일 진실 공급원.** 모든 판정(턴 순서, 좌석 권한, 스킵 과반, 큐 순서)은 차량 앱이
   한다. 서버는 중계와 곡 검색만 한다.
2. **웹앱은 요청만 보내고 아무것도 스스로 판단하지 않는다.** 화면은 차량이 보낸 `state.sync`를
   그대로 그린다. 낙관적 업데이트(먼저 UI 반영) 금지 — 차량이 거절할 수 있다.
3. 모든 메시지는 JSON 1줄. 공통 필드 `{ "type": ..., "ts": <epoch ms> }`.
4. 차량 앱은 상태가 바뀔 때마다 **전체 상태**를 `state.sync`로 보낸다(부분 갱신 없음). 단순함이
   2주 일정에서 훨씬 중요하다.

## 좌석 ID

고정 문자열만 사용한다. `R1L` `R1R` `R2L` `R2C` `R2R`
(R1L = 1열 좌측 = 운전석 기준. 좌핸들 가정.)

## 세션 상태 (`state.sync` 의 payload)

```json
{
  "type": "state.sync",
  "ts": 1762000000000,
  "sessionId": "a3f9",
  "driveState": "PARKED",
  "seats": [
    {
      "seatId": "R1L",
      "occupied": true,
      "deviceId": "dev_8812",
      "nickname": "아빠",
      "profile": "DRIVER",
      "connected": true
    }
  ],
  "turnSeatId": "R2L",
  "turnDeadlineMs": 30000,
  "nowPlaying": {
    "itemId": "q_17",
    "title": "...",
    "artist": "...",
    "durationMs": 214000,
    "positionMs": 41000,
    "requestedBySeat": "R1R",
    "artworkUrl": "https://.../cover_medium.jpg"
  },
  "queue": [
    { "itemId": "q_18", "title": "...", "artist": "...", "durationMs": 198000, "requestedBySeat": "R2L" }
  ],
  "etaSeconds": 9600,
  "queueTargetMs": 9600000,
  "skipVotes": ["R2L", "R2R"],
  "skipThreshold": 3,
  "mode": "NORMAL",
  "landing": false,
  "queueTotalMs": 1240000,
  "turnRemainingMs": 18000,
  "moodId": "RAIN",
  "moodLabel": "비 오는 날",
  "moodReason": "와이퍼가 움직이고 있어요",
  "suggestions": [
    { "itemId": "catalog:10", "title": "비 오는 날", "artist": "검정치마",
      "durationMs": 254000, "requestedBySeat": "AUTO",
      "artworkUrl": "https://.../cover_medium.jpg" }
  ],
  "suggestAutoAddMs": 14000
}
```

필드 규약
- `driveState`: `"PARKED"` | `"DRIVING"` — 웹앱은 `DRIVING`일 때 전체 조작 UI를 켠다
  (운전자 화면이 잠기는 대신 폰이 권한을 받는다).
- `profile`: `"DRIVER"` | `"ADULT"` | `"CHILD"` | `"GUEST"`
- `occupied` 는 착석 신호, `connected` 는 폰 연결 여부. **둘은 다르다.**
  착석했지만 폰 미연결인 좌석은 좌석도에 "참여 대기"로 표시.
- `turnSeatId` 가 `null` 이면 턴 대기 상태(예: 주행 시작 전).
- `turnRemainingMs` (v0.3): 지금 차례인 좌석에 남은 시간. **`turnDeadlineMs` 를 넘기면
  차량이 순서를 다음 좌석으로 넘깁니다.** 이게 없으면 차례인 사람이 아무것도 하지 않을 때
  순서가 영영 멈춥니다. 특히 운전석은 주행 중 화면을 만질 수 없고 음성으로도 곡을 추가할
  수 없어서, 운전석 차례에서 전체가 막혔습니다.
  `null` 이면 제한시간이 돌고 있지 않다는 뜻이고, 세 경우입니다 —
  라운지 모드, 착석한 좌석이 하나뿐, 아직 아무도 폰을 연결하지 않음.
  마지막 경우는 넘겨도 쓸 사람이 없고 차례 안내 음성만 반복되므로 일부러 세웁니다.
- `etaSeconds`, `queueTargetMs` 는 내비 목적지가 없으면 `null`.
- `mode`: `"NORMAL"` | `"CHARGING_LOUNGE"` (v0.2). 충전 중 정차 상태에서 라운지 모드가 켜지고,
  **이때는 좌석 턴 규칙이 해제되어 아무 좌석이나 곡을 넣을 수 있습니다.** 차 안에 갇혀 있는
  충전 대기 시간에는 차례를 기다리게 할 이유가 없기 때문입니다.
- `landing`: 도착 5분 이내 (v0.2). 안내용 플래그이며 큐 추가를 막지는 않습니다.
  차량은 랜딩 진입 시 음성 안내를 한 번 하고, 폰은 배너를 띄웁니다.
- `queueTotalMs` (v0.2): 현재 큐의 총 재생 길이. `queueTargetMs` 와 비교해 "도착까지 몇 곡 더"를
  폰에 표시하는 데 씁니다.
- `moodId` / `moodLabel` / `moodReason` (v0.5): 지금 차 안의 분위기.
  `RAIN` `NIGHT` `SUNNY` `WINDING` `LOUNGE` `ARRIVING` `CALM` 일곱 가지이며,
  모르는 값이 오면 폰은 회색 점으로 그립니다(화면이 멈추면 안 됩니다).
  **`moodReason` 은 반드시 같이 그립니다.** 이름만 띄우면 판정이 틀렸을 때
  그냥 멍청한 기능이지만, 근거("와이퍼가 움직이고 있어요")가 보이면 사람이
  납득하거나 직접 고칩니다.
  판정 근거는 전부 차량 신호입니다 — 와이퍼 작동·단계, 하향등 점등,
  최근 12초 조향 변동폭, 충전 상태, 도착까지 남은 시간, 차량 설정의 야간 모드.
  날씨 API 를 쓰지 않습니다. 터널이나 지하주차장에서 "맑음" 이라고 답하는
  좌표 기반 날씨보다, 지금 이 앞유리에 비가 오는지를 아는 와이퍼가 정확합니다.
- `suggestions` / `suggestAutoAddMs` (v0.5): 아무도 곡을 넣지 않을 때 차가 권하는 곡.
  **카운트다운이 0 이 되면 차가 그대로 큐에 담고 재생을 시작합니다.**
  그래서 폰에서는 '담기' 보다 '괜찮아요'(`mood.dismiss`)가 더 중요한 버튼입니다.
  차가 끼어들지 않는 경우 — 재생 중이거나 큐에 곡이 있을 때, 아무도 폰을
  연결하지 않았을 때, 방금 누가 `mood.dismiss` 를 눌렀을 때(10분).
  담긴 곡의 `requestedBySeat` 는 좌석이 아니라 `"AUTO"` 이고, 화면에는
  "분위기 추천" 으로 표시합니다. 누가 골랐는지를 속이지 않기 위해서입니다.

## 폰 → 서버 → 차량 (요청)

| type | payload | 차량의 검증 |
|---|---|---|
| `seat.claim` | `{ deviceId, seatId, nickname }` | 해당 좌석이 `occupied` 인가, 이미 다른 기기가 점유했는가 |
| `queue.add` | `{ seatId, title, artist, externalId?, artworkUrl? }` | 지금 그 좌석의 차례인가, 좌석 프로필이 허용하는가, 연속 독점 아닌가 |
| `queue.remove` | `{ seatId, itemId }` | 본인이 넣은 곡인가 |
| `vote.skip` | `{ seatId }` | 중복 투표 아닌가, 운전자 거부권 적용 |
| `reaction.like` | `{ seatId }` | — |
| `turn.pass` | `{ seatId }` | 지금 그 좌석의 차례인가 |
| `mood.accept` (v0.5) | `{ seatId }` | — (차례 무관) |
| `mood.dismiss` (v0.5) | `{ seatId }` | — (차례 무관) |

`mood.accept` / `mood.dismiss` 는 **차례를 확인하지 않습니다.** 차가 권한 곡을
받아들이거나 물리는 것일 뿐, 한 사람이 선곡권을 더 갖는 것이 아니기 때문입니다.
차례를 요구하면 정작 차례가 아닌 사람은 멋대로 들어가는 곡을 막을 수 없습니다.

## 차량 → 서버 → 폰 (응답·통지)

| type | payload |
|---|---|
| `state.sync` | 위 전체 상태 |
| `turn.notify` | `{ seatId, deadlineMs }` — 해당 좌석 폰에 차례 알림 |
| `drive.album` | `{ startedAt, endedAt, durationMs, tracks: [{ title, artist, requestedBySeat }], seats: [{ seatId, nickname }] }` — 주행이 끝나면 한 번 전송 (v0.2) |
| `reject` | `{ requestType, reason, seatId? }` — `reason`: `NOT_YOUR_TURN` \| `PROFILE_BLOCKED` \| `SEAT_TAKEN` \| `DUPLICATE_VOTE` \| `DRIVING_RESTRICTED` |

### 수신자 판별 — 허브는 브로드캐스트한다

허브는 차량이 보낸 메시지를 **모든 폰에 그대로 뿌립니다.** 폰별 라우팅을 하지 않습니다.
그래서 `turn.notify` 와 `reject` 는 `seatId` 를 담고, 폰이 자기 좌석 것만 골라 씁니다.

```js
if (m.type === "reject" && (!m.seatId || m.seatId === mySeat)) showToast(m.reason);
if (m.type === "turn.notify" && m.seatId === mySeat) showMyTurn();
```

`seatId` 가 없는 `reject` 는 수신자를 특정할 수 없는 경우이므로 모든 폰이 표시해도 됩니다.

## 서버 단독 처리 (차량 경유 안 함)

| type | payload |
|---|---|
| `search.query` | `{ q, limit }` |
| `search.result` | `{ items: [{ title, artist, durationMs, externalId, artworkUrl? }] }` |
| `mood.query` (v0.5) | `{ mood, limit, kidsAboard, exclude: [] }` — **차량이 보냅니다** |
| `mood.result` (v0.5) | `{ mood, items: [{ title, artist, durationMs, externalId, artworkUrl? }] }` |

`mood.query` 는 폰이 아니라 **차량이** 허브에 묻는 유일한 메시지이고, 답은 물어본
차에만 돌아간다(폰으로 뿌리지 않는다). 분위기 판정은 차가, 곡 선정은 허브가 한다 —
곡 목록이 허브에 있고, 지금 비가 오는지는 차만 알기 때문이다.
`exclude` 에는 방금 들은 곡을 `"제목|가수"` 소문자 형태로 넣어 같은 곡이 다시
권해지는 것을 막는다. 추천은 큐레이션 목록 안에서만 고른다 — 아무도 누르지
않으면 자동으로 들어가는 곡이므로, 사람이 확인한 곡이어야 한다.

곡 검색은 서버가 처리한다. 차량 앱은 검색을 모른다. `artworkUrl` 은 있을 때만 포함된다. 재생은 `queue.add` 로 들어온
`title`/`artist` 를 차량이 `PlaybackGateway`로 해석해 차량 음악 앱에 위임한다.

## 연결 규약

- 엔드포인트: `ws://<host>:8080/session/{sessionId}`
- 쿼리로 역할 구분: `?role=car` | `?role=phone&deviceId=...`
- 차량이 접속을 끊으면 서버는 세션방을 유지하고 폰에 `{"type":"car.offline"}` 통지.
- 폰이 재접속하면 `deviceId` 로 기존 좌석에 자동 복귀 (QR 재스캔 없음 — 이게 리텐션 핵심).
- 에뮬레이터에서 호스트 PC는 `10.0.2.2`.

## 목(mock) 구현 — 상대를 기다리지 않기 위한 장치

- **이용준 → `MockCarClient`**: `role=car` 로 접속해 3초마다 `state.sync` 를 쏘고,
  `queue.add` 를 받으면 큐에 넣은 새 상태를 돌려보낸다. 웹앱 단독 개발·테스트용.
- **이정한 → `FakeVehicleSignalSource`**: 좌석·도어·기어·속도를 코드나 디버그 버튼으로 주입.
  에뮬레이터 검증 전에도 `SessionEngine` 개발 가능.

## 버전

- `v0.5` — 2026-11-03. 분위기(`moodId`/`moodLabel`/`moodReason`)와 자동 추천
  (`suggestions`/`suggestAutoAddMs`)을 `state.sync` 에 추가. 폰 → 차량에
  `mood.accept`/`mood.dismiss`, 차량 → 허브에 `mood.query`/`mood.result` 추가.
  전부 추가분이며 v0.4 클라이언트는 그대로 동작합니다(추천이 보이지 않을 뿐
  자동 담기는 그대로 일어납니다 — 거절할 방법이 없으므로 폰을 같이 올려야 합니다).
- `v0.4` — 2026-11-01. `queue.add` 와 `state.sync` 의 트랙에 선택 필드 `artworkUrl` 추가.
  폰이 검색 결과에서 받은 커버 주소를 그대로 넘기고, 차량은 그것만 그립니다.
  차량이 다시 검색하지 않습니다. 큐레이션 목록 곡은 허브가 시작 직후 백그라운드로
  커버를 채워 두며, 못 찾으면 커버 없이 둡니다. 추가분이며 v0.3 클라이언트는 그대로 동작합니다.
- `v0.3` — 2026-10-31. `state.sync` 에 선택 필드 `turnRemainingMs` 추가.
  차례 제한시간이 실제로 적용됩니다. 추가분이며 v0.2 클라이언트는 그대로 동작합니다.
- `v0.2` — 2026-10-31. `state.sync` 에 선택 필드 `mode`, `landing`, `queueTotalMs` 추가.
  차량 → 폰 메시지 `drive.album` 추가. 모두 추가분이며 v0.1 클라이언트는 그대로 동작합니다.
- `v0.1.1` — 2026-10-31. `reject` 에 선택 필드 `seatId` 추가. 허브가 브로드캐스트하므로
  폰이 자기 것을 골라낼 수단이 필요했습니다. 기존 필드는 그대로이며 하위 호환됩니다.
- `v0.1` — 2026-10-31 최초 확정.

변경 시 이 문서 상단 버전을 올리고, 양쪽 PR 승인 후 병합한다.
