# 개발 환경 설정

## 요구 사항

| 항목 | 값 | 출처 |
|---|---|---|
| Minimum Android SDK | API 28 이상 | 차량 사이버 보안 요건(사이닝 키 로테이션·리니지) |
| Gradle | 8.0 이상 | 공식 문서 |
| 언어 | Java, Kotlin | 공식 문서 |
| Pleos Connect Emulator | 2.1.0 | Virtual Console 지원 최소 버전 |
| 시스템 이미지 | Android API 34 | |
| JDK | 17 | |

## 1. Pleos Connect Emulator 설치

Android Studio 최신 버전이 필요합니다. 이전 버전의 Pleos Connect Emulator가 깔려 있으면
먼저 삭제하세요.

1. Android Studio → **Tools → SDK Manager**
2. **SDK Update Sites** 탭 → 좌측 상단 **+**
3. 아래를 입력하고 OK
   - Name: `Pleos Connect System Image`
   - URL (macOS Apple Silicon):
     `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml`
   - URL (Windows/Linux/Intel Mac):
     `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml`
4. **SDK Platforms** 탭 → 하단 **Show Package Details** 체크
5. **Android API Level 34** 아래의 **Pleos Connect System Image** 체크 → OK
6. 설치 완료 후 **Android Studio 재시작**

> 디스크 공간을 먼저 확인하세요. AAOS 시스템 이미지는 용량이 큽니다.
> `df -h ~` 로 여유를 보고, 부족하면 공간을 먼저 확보하세요.

## 2. AVD 생성

1. **Tools → Device Manager** → **Create device**
2. Form Factor: **Automotive**
3. **Pleos Connect v2.1.0** 선택 → Next
4. **Advanced Settings** 탭
   - Startup → Default boot: **Cold**
   - Emulated Performance → RAM Size: **4 GB**
   - (구버전 Android Studio: Graphics `Hardware`, Boot option `Cold boot`)
5. **Device** 탭 → Select system image → API **34** → **Pleos Connect v2.1.0 APIs** → Finish

구형 그래픽 설정으로 장시간 실행하면 에뮬레이터가 스스로 종료되는 사례가 보고되어 있습니다.
`-gpu host` 를 권장합니다.

## 3. 차량 앱 빌드·설치

```bash
cd car-app
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # 최초 1회
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

Device Manager에서 AVD를 실행한 뒤 Android Studio의 Run, 또는:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Pleos 에뮬레이터는 화면 사용자가 10번일 수 있습니다. 그 경우:

```bash
adb install -r --user 10 app/build/outputs/apk/debug/app-debug.apk
adb shell am start --user 10 -n dev.semicolon.playseat.car/.MainActivity
```

## 4. Pleos Connect SDK 좌표

`nexus-playground.pleos.ai` 의 maven-metadata 로 확인한 값입니다.

저장소 (`car-app/settings.gradle.kts` 에 등록되어 있음):
`https://nexus-playground.pleos.ai/repository/maven-releases/`

| 모듈 | 좌표 | 사용 버전 |
|---|---|---|
| Vehicle | `ai.pleos.playground:Vehicle` | 2.3.2 |
| NaviHelper | `ai.pleos.playground:NaviHelper` | 3.0.5 |
| TextToSpeech | `ai.pleos.playground:TextToSpeech` | 3.3.1 |
| SpeechToText | `ai.pleos.playground:SpeechToText` | 3.3.1 |
| FusedLocation | `ai.pleos.playground:FusedLocation` | 1.1.3 |

## 5. 퍼미션 이름 주의

맨이름으로 선언하면 동작하지 않습니다. **`pleos.car.permission.{상수}`** 형식입니다.

```xml
<uses-permission android:name="pleos.car.permission.CAR_SEATS" />
<uses-permission android:name="pleos.car.permission.CAR_DRIVE" />
```

| 도메인 | 퍼미션 |
|---|---|
| Seat (착석·안전벨트) | `pleos.car.permission.CAR_SEATS` |
| Door | `pleos.car.permission.CAR_DOORS` |
| DrivingMode, Brake, Odometer | `pleos.car.permission.CAR_DRIVE` |
| EvBattery, Fuel | `pleos.car.permission.CAR_ENERGY` |
| NaviHelper | `pleos.car.permission.NAVI_ROUTE`, `NAVI_ROUTE_SEARCH` |
| TextToSpeech | `pleos.car.permission.TTS_SERVICE` |
| SpeechToText | `pleos.car.permission.STT_SERVICE` |
| FusedLocation | `pleos.car.permission.FUSED_LOCATION` |

## 6. Vehicle SDK 사용 형태

진입점은 `Vehicle(context)` 입니다. `initialize()` 후 `getXxx()` 로 도메인 객체를 얻고,
사용이 끝나면 `release()` 합니다. 차량 통신은 IPC 이므로 결과가 콜백으로 비동기 전달되며,
콜백이 끝나기 전에 `release()` 하면 안 됩니다.

```kotlin
val vehicle = Vehicle(context)
vehicle.initialize()

val seat = vehicle.getSeat()

// 어느 좌석이 착석 감지를 지원하는지 먼저 확인한다
seat.checkSeatOccupancyCapabilities({ _, areas -> /* areas 만 구독 */ }, { e -> })

// 구독
seat.registerSeatOccupancy(SeatArea(SeatValue.ROW_2_LEFT), listener)

vehicle.release()
```

좌석 상수는 `SeatValue.ROW_1_LEFT` ~ `ROW_3_RIGHT` (9석)입니다.
리스너는 `VehicleListener` 를 상속하므로 `onFailed(e: Exception)` 도 구현해야 합니다.
이때 파라미터는 non-null 입니다 — `Exception?` 로 쓰면 override 가 되지 않습니다.

구현 예는 `car-app/.../vehicle/PleosVehicleSignalSource.kt` 를 보세요.

## 7. 알려진 제약

- **기어 API 비권장** — `getCurrentGearSelectionState` / `registerGearSelection` 에
  "use the Google GEAR_SELECTION property" 경고가 붙습니다. AAOS 표준
  `CarPropertyManager` 의 `GEAR_SELECTION` 으로 교체해야 합니다.
- **충전 상태 전용 API 미확인** — `EvChargePortOpenListener` 를 대리 신호로 쓸지,
  `EvBatteryStateOfCharge` 증가 추세로 판정할지 결정이 필요합니다.
  그때까지 충전 라운지 모드는 실제 차량에서 켜지지 않습니다.
- **ETA 미연동** — NaviHelper 구독이 아직 없어 실제 차량에서는 랜딩 구간이
  발생하지 않습니다. Fake 신호원에서는 동작합니다.
