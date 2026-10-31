# 플레이시트 통신 계약 v0.1

> 차량 앱(이정한) ↔ 허브 서버(이용준) ↔ 동승자 웹(이용준)
> 저장소로 옮길 위치: `contract/protocol.md`
> **이 문서가 두 사람의 유일한 접점입니다. 변경은 반드시 양쪽 합의 후 PR.**

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
  "skipThreshold": 3
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
| `reject` | `{ requestType, reason }` — `reason`: `NOT_YOUR_TURN` \| `PROFILE_BLOCKED` \| `SEAT_TAKEN` \| `DUPLICATE_VOTE` \| `DRIVING_RESTRICTED` |

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

`v0.1` — 2026-10-31 최초 확정.
변경 시 이 문서 상단 버전을 올리고, 양쪽 PR 승인 후 병합한다.
