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
| `session/SessionEngine.kt` | **세션의 단일 진실 공급원.** 턴 순서·좌석 권한·스킵 과반·라운지 모드·랜딩·드라이브 앨범 판정 |
| `session/SessionModels.kt` | `contract/protocol.md` 와 1:1 대응하는 직렬화 모델 |
| `net/HubClient.kt` | 허브 WebSocket. 상태 송신, 폰 요청 수신, 자동 재접속 |
| `vehicle/` | 차량 신호 계층 (Pleos 의존성 격리) |
| `playback/` | 재생 계층. 음원을 직접 보유하지 않고 차량 음악 앱에 위임하는 경계 |
| `voice/` | 운전자 음성 입출력. 명령 해석은 `VoiceCommands` 순수 함수 |
| `ui/CarScreen.kt` | 좌석 배치도, 지금 재생, 큐, QR, 주행 중 축약, 랜딩·라운지 배너, 앨범 카드 |
| `CarViewModel.kt` | 신호 → 엔진 → 허브·재생·음성 배선 |

`SessionEngine` 과 `VoiceCommands` 는 안드로이드 의존성이 없어 단위 테스트로 검증합니다.

```bash
./gradlew :app:testDebugUnitTest   # 31개 통과
```

## 세 계층 모두 교체 가능합니다

실제 차량·실제 음원·Pleos 음성 엔진이 없어도 전체 앱이 돌아가게 설계했습니다.

| 계층 | 지금 | 교체 대상 |
|---|---|---|
| 차량 신호 | `FakeVehicleSignalSource` (디버그 버튼) | `PleosVehicleSignalSource` |
| 재생 | `SimulatedPlaybackGateway` (시간만 흐름) | `MediaSessionPlaybackGateway` |
| 음성 | `AndroidVoiceController` (표준 TTS/STT) | `PleosVoiceController` |

각 팩토리가 `isAvailable()` 로 둘 중 하나를 고릅니다. 실제 구현을 붙이면
`isAvailable()` 만 true 로 바꾸면 되고 세션 로직은 건드리지 않습니다.

## 주행 중 화면

`driveState == DRIVING` 이면 화면이 '지금 재생 + 큰 버튼 2개'로 축약되고
목록·검색·키보드가 사라집니다. 선곡 권한은 동승자 폰으로 넘어갑니다.

운전자 경로는 `다음 곡` 버튼과 `말하기`(음성) 둘뿐입니다.
음성은 "다음 곡", "내 차례 넘겨", "이 노래 좋아" 를 알아듣습니다.
마이크 권한을 거부해도 나머지 기능은 그대로 동작합니다.

## 디버그 신호 주입

Fake 신호원일 때 화면 하단에 노출됩니다.

- 주행/정차, 충전(→ 라운지 모드), 좌석 5개 착석 토글
- 목적지 ETA: 2시간 40분 / 8분 / 4분(랜딩) / 목적지 없음

ETA 는 주행 중 1초마다 실제로 줄어들어, 랜딩 구간과 드라이브 앨범까지 검증됩니다.

`BuildConfig.PLAYBACK_SPEED` 를 올리면 재생이 빨라집니다. 장거리 주행 시나리오를
수십 초로 압축해 시연 영상을 녹화할 때 씁니다.

## 남은 작업

- [x] Pleos Vehicle SDK 연결 — 좌석 착석(+안전벨트 폴백), 기어, 충전
- [x] NaviHelper 로 실제 ETA 구독 — `onDrivingInfo` → `destination.duration`
- [ ] 에뮬레이터에서 `CAR_SEATS` 실제 수신 검증 (**최우선**)
- [ ] 기어를 AAOS 표준 `GEAR_SELECTION` 으로 교체 (Pleos API 가 비권장.
      `android.car.jar` 가 platforms/android-35/optional 에 있어 `useLibrary("android.car")` 로 가능)
- [ ] Pleos TTS/STT 로 교체 (차량 마이크·스티어링 휠 음성 버튼 활용)
- [ ] `MediaSessionPlaybackGateway` — 차량 음악 앱 위임
- [ ] 드라이브 앨범 로컬 보관 (현재는 메모리, 앱 재시작 시 사라짐)
- [ ] 제출 전 `AndroidManifest.xml` 의 automotive feature 를 `required="true"` 로 변경
