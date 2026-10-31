# 플레이시트 통신 계약 v0.2

> 차량 앱(이정한) ↔ 허브 서버(이용준) ↔ 동승자 웹(이용준)
> 저장소로 옮길 위치: `contract/protocol.md`
> **이 문서가 두 사람의 유일한 접점입니다. 변경은 반드시 양쪽 합의 후 PR.**

## 구현 상태 (2026-10-31)

계약에 적혀 있다고 동작하는 것은 아닙니다. 지금 신뢰할 수 있는 범위입니다.

| 항목 | 차량 앱 | 허브 | 동승자 웹 |
|---|---|---|---|
| `state.sync` (seats, turnSeatId, nowPlaying, queue, skipVotes, skipThreshold) | 동작 | 중계 | 표시 |
| `seat.claim` | 동작 | 중계 | 동작 |
| `queue.add` / `queue.remove` | 동작 | 중계 | 추가만 |
| `vote.skip` / `reaction.like` / `turn.pass` | 동작 | 중계 | skip·like |
| `reject` (+`seatId`) | 동작 | 브로드캐스트 | 표시 |
| `turn.notify` | 동작 | 브로드캐스트 | 표시 |
| `search.query` / `search.result` | 해당 없음 | **스텁** (고정 목록) | 동작 |
| `driveState` 전환에 따른 권한 이동 | 동작 | — | 배너만 |
| `etaSeconds` / `queueTargetMs` | Fake 값 | — | 미사용 |
| `mode` (CHARGING_LOUNGE) | **미구현** | — | **미구현** |
| `landing` | **미구현** | — | **미구현** |
| `queueTotalMs` | **미구현** | — | **미구현** |
| `drive.album` | **미구현** | — | **미구현** |

**미구현 항목에 의존하는 코드를 먼저 쓰지 마세요.** v0.2 추가분은 설계를 미리 합의해 둔
것이고, 차량 앱이 내려보내기 시작하면 이 표를 갱신합니다. 그때까지 폰은 해당 필드가
`undefined` 인 상태를 정상으로 다뤄야 합니다.

차량 신호도 아직 실제 차량에서 오지 않습니다. `FakeVehicleSignalSource` 가 화면의 디버그
버튼으로 주입하는 값입니다. Pleos Vehicle SDK 연결은 미완입니다.

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
    "requestedBySeat": "R1R"
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
  "queueTotalMs": 1240000
}
```

필드 규약
- `driveState`: `"PARKED"` | `"DRIVING"` — 웹앱은 `DRIVING`일 때 전체 조작 UI를 켠다
  (운전자 화면이 잠기는 대신 폰이 권한을 받는다).
- `profile`: `"DRIVER"` | `"ADULT"` | `"CHILD"` | `"GUEST"`
- `occupied` 는 착석 신호, `connected` 는 폰 연결 여부. **둘은 다르다.**
  착석했지만 폰 미연결인 좌석은 좌석도에 "참여 대기"로 표시.
- `turnSeatId` 가 `null` 이면 턴 대기 상태(예: 주행 시작 전).
- `etaSeconds`, `queueTargetMs` 는 내비 목적지가 없으면 `null`.
- `mode`: `"NORMAL"` | `"CHARGING_LOUNGE"` (v0.2 · **미구현**). 충전 중 정차 상태에서 라운지 모드가 켜지고,
  **이때는 좌석 턴 규칙이 해제되어 아무 좌석이나 곡을 넣을 수 있습니다.** 차 안에 갇혀 있는
  충전 대기 시간에는 차례를 기다리게 할 이유가 없기 때문입니다.
- `landing`: 도착 5분 이내 (v0.2 · **미구현**). 템포를 낮추고 큐 추가를 더 받지 않는 구간입니다.
- `queueTotalMs` (v0.2 · **미구현**): 현재 큐의 총 재생 길이. `queueTargetMs` 와 비교해 "도착까지 몇 곡 더"를
  폰에 표시하는 데 씁니다.

## 폰 → 서버 → 차량 (요청)

| type | payload | 차량의 검증 |
|---|---|---|
| `seat.claim` | `{ deviceId, seatId, nickname }` | 해당 좌석이 `occupied` 인가, 이미 다른 기기가 점유했는가 |
| `queue.add` | `{ seatId, title, artist, externalId? }` | 지금 그 좌석의 차례인가, 좌석 프로필이 허용하는가, 연속 독점 아닌가 |
| `queue.remove` | `{ seatId, itemId }` | 본인이 넣은 곡인가 |
| `vote.skip` | `{ seatId }` | 중복 투표 아닌가, 운전자 거부권 적용 |
| `reaction.like` | `{ seatId }` | — |
| `turn.pass` | `{ seatId }` | 지금 그 좌석의 차례인가 |

## 차량 → 서버 → 폰 (응답·통지)

| type | payload |
|---|---|
| `state.sync` | 위 전체 상태 |
| `turn.notify` | `{ seatId, deadlineMs }` — 해당 좌석 폰에 차례 알림 |
| `drive.album` | `{ startedAt, endedAt, durationMs, tracks: [{ title, artist, requestedBySeat }], seats: [{ seatId, nickname }] }` — 주행이 끝나면 한 번 전송 (v0.2 · **미구현**) |
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

곡 검색은 서버가 처리한다. 차량 앱은 검색을 모른다. 재생은 `queue.add` 로 들어온
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

- `v0.2` — 2026-10-31. `state.sync` 에 선택 필드 `mode`, `landing`, `queueTotalMs` 추가.
  차량 → 폰 메시지 `drive.album` 추가. 모두 추가분이며 v0.1 클라이언트는 그대로 동작합니다.
- `v0.1.1` — 2026-10-31. `reject` 에 선택 필드 `seatId` 추가. 허브가 브로드캐스트하므로
  폰이 자기 것을 골라낼 수단이 필요했습니다. 기존 필드는 그대로이며 하위 호환됩니다.
- `v0.1` — 2026-10-31 최초 확정.

변경 시 이 문서 상단 버전을 올리고, 양쪽 PR 승인 후 병합한다.
