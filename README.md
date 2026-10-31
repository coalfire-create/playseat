# 플레이시트 (PlaySeat)

차에 타는 순간 모든 좌석이 DJ 부스가 되는 Pleos Connect 차량용 앱입니다.
좌석 착석 신호로 탑승자를 자동 인식해, 운전자와 동승자가 서로 다른 음악 앱을 쓰더라도
하나의 플레이리스트를 함께 만들고 함께 조작합니다.

현대자동차그룹 차량용 앱 개발 공모전 출품작 (대학생 부문).

## 핵심 동작

- **좌석이 곧 입장권** — 도어 개폐 후 착석이 감지되면 그 좌석이 세션 슬롯이 됩니다.
  초대·세션 개설 절차가 없습니다. (`CAR_SEATS`, `CAR_DOORS`)
- **좌석별 턴** — 선착순 큐가 아니라 좌석 라운드로빈으로 선곡권을 분배합니다.
- **주행하면 조작 권한이 이사한다** — 주행 중 운전자 화면은 잠기고, 조작 권한이
  동승자 폰으로 이전됩니다. 운전자는 음성으로만 참여합니다. (`CAR_DRIVE`, STT/TTS)
- **경로에 맞춰 끝나는 플레이리스트** — 잔여 ETA에 큐 길이를 맞춥니다. (NaviHelper)

음원은 직접 재생하지 않습니다. 선곡은 '요청'으로 수집하고, 실제 재생은 AAOS 표준
MediaSession/MediaController로 차량의 음악 앱에 위임합니다.

## 저장소 구조

| 경로 | 내용 |
|---|---|
| `car-app/` | Android 차량 앱 (Kotlin, Jetpack Compose, Pleos Connect SDK) |
| `hub-server/` | Ktor WebSocket 허브 (차량 ↔ 동승자 폰 중계, 곡 검색) |
| `passenger-web/` | 동승자 웹앱 (설치 불필요, QR로 진입) |
| `demo/` | Pleos Emulator Virtual Console 자동 조작·녹화 스크립트 |
| `contract/` | 차량 앱 ↔ 서버 ↔ 웹앱 통신 계약 |
| `apk/` | 제출용 release APK |
| `docs/` | 개발 계획·환경 안내 |

## 개발 환경

| 항목 | 값 |
|---|---|
| 플랫폼 | Pleos Connect (Android Automotive OS 기반) |
| 언어 / UI | Kotlin, Jetpack Compose + Material 3 |
| Android SDK | min 28 / compile·target 35 |
| JDK | 17 |
| Gradle / AGP | 8.x |
| 서버 | Kotlin + Ktor (WebSocket) |
| 검증 | Pleos Connect Emulator (Virtual Console로 신호 주입) |

## 실행 방법

작성 예정 — 핵심 루프 구현 후 다음을 채웁니다.
1. 허브 서버 실행
2. Pleos Connect AVD 부팅 후 차량 앱 설치·실행
3. 차량 화면의 QR을 폰으로 스캔해 동승자 웹앱 접속
4. Virtual Console에서 도어·착석·기어를 조작해 동작 확인

에뮬레이터에서 호스트 PC는 `10.0.2.2` 로 접근합니다.

## 제출용 APK

`apk/Semicolon_플레이시트_release.apk` (구현 완료 후 추가)

## 통신 계약

차량 앱과 동승자 웹앱의 유일한 접점은 `contract/protocol.md` 입니다.
변경은 양쪽 합의 후 PR로만 진행합니다.
