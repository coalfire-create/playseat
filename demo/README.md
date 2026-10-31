# demo

## mock-car.mjs — 차량 앱 목(mock)

동승자 웹앱을 차량 앱 없이 개발·테스트하기 위한 스크립트입니다.
`contract/protocol.md` 의 차량 측 역할(단일 진실 공급원, 요청 검증·거절)을 그대로 수행합니다.
의존성 없음 — Node 22+ 내장 WebSocket을 씁니다.

```bash
node demo/mock-car.mjs
node demo/mock-car.mjs --session a3f9 --host localhost:8080
```

실행 중 키 입력으로 차량 신호를 주입합니다 (Pleos Virtual Console 대역).

| 키 | 동작 |
|---|---|
| `d` | 주행/정차 전환 — 폰의 권한 UI가 바뀌고, 주행이 끝나면 드라이브 앨범이 전송됩니다 |
| `c` | 충전 토글 — 정차 중이면 충전 라운지 모드 (차례 없이 모두 추가 가능) |
| `e` | ETA 를 4분으로 — 랜딩 구간 진입 확인 |
| `1`~`5` | 좌석 착석 토글 (R1L, R1R, R2L, R2C, R2R) — 하차 시 큐·턴 정리 확인 |
| `n` | 다음 곡 |
| `q` | 종료 |

시작 상태를 플래그로 지정할 수도 있습니다.

```bash
node demo/mock-car.mjs --charging            # 충전 라운지 모드로 시작
node demo/mock-car.mjs --driving --eta 240   # 주행 + 랜딩 구간으로 시작
node demo/mock-car.mjs --session lounge      # 다른 세션방으로
```

## 전체 데모

터미널 3개:

```bash
# 1
cd hub-server && ./gradlew run
# 2
node demo/mock-car.mjs
# 3 — 브라우저에서 http://localhost:8080 열기
```

## 앞으로 추가할 것

- Pleos Connect Emulator Virtual Console 자동 조작 스크립트
- 시나리오별 녹화 스크립트
