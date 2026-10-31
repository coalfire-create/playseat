# car-app

Pleos Connect 차량 앱. Kotlin + Jetpack Compose.

## 빌드·실행

```bash
cd car-app
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # 최초 1회
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

Pleos Connect AVD를 띄운 뒤 Android Studio의 Run, 또는:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

허브 주소는 `app/build.gradle.kts` 의 `HUB_HOST` 입니다. 기본값 `10.0.2.2:8080`
— 에뮬레이터에서 호스트 PC를 가리킵니다.

## Pleos SDK 가 없어도 돈다

차량 신호 의존성을 `vehicle/` 패키지 안에만 두었습니다.

| 파일 | 역할 |
|---|---|
| `VehicleSignals.kt` | 좌석·주행·충전 신호 모델과 `VehicleSignalSource` 인터페이스 |
| `FakeVehicleSignalSource.kt` | 화면 하단 디버그 버튼으로 신호를 직접 주입 |
| `PleosVehicleSignalSource.kt` | 실제 SDK 구현 자리. 현재 `isAvailable()` 이 false |

`VehicleSignalSourceFactory` 가 둘 중 하나를 고르므로, 에뮬레이터 검증 전에도
세션 로직과 UI를 전부 개발할 수 있습니다. SDK를 붙이는 순서는
`PleosVehicleSignalSource.kt` 상단 주석에 적어 두었습니다.

## 구조

| 경로 | 역할 |
|---|---|
| `session/SessionEngine.kt` | **세션의 단일 진실 공급원.** 턴 순서·좌석 권한·스킵 과반 판정 |
| `session/SessionModels.kt` | `contract/protocol.md` 와 1:1 대응하는 직렬화 모델 |
| `net/HubClient.kt` | 허브 WebSocket. 상태 송신, 폰 요청 수신, 자동 재접속 |
| `vehicle/` | 차량 신호 계층 (Pleos 의존성 격리) |
| `ui/CarScreen.kt` | 좌석 배치도, 지금 재생, 큐, QR, 주행 중 축약 화면 |
| `CarViewModel.kt` | 신호 → 엔진 → 허브 배선 |

`SessionEngine` 은 안드로이드 의존성이 없어 단위 테스트로 규칙을 검증합니다.

```bash
./gradlew :app:testDebugUnitTest   # 16개 통과
```

## 주행 중 화면

`driveState == DRIVING` 이면 화면이 '지금 재생 + 큰 버튼 2개'로 축약되고
목록·검색·키보드가 사라집니다. 선곡 권한은 동승자 폰으로 넘어갑니다.
운전자 경로는 `driverSkip()` / `driverPassTurn()` 이며, 여기에 STT/TTS를 연결합니다.

## 남은 작업

- [ ] Pleos Vehicle SDK 연결 (`CAR_SEATS` 수신 검증이 최우선)
- [ ] NaviHelper 로 실제 ETA 구독
- [ ] STT/TTS 음성 조작
- [ ] `PlaybackGateway` — MediaSession/MediaController 로 차량 음악 앱에 재생 위임
- [ ] 제출 전 `AndroidManifest.xml` 의 automotive feature 를 `required="true"` 로 변경
