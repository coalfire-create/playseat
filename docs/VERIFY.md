# 실차 환경 검증 기록

Pleos Connect v2.1.0 에뮬레이터에서 차량 앱을 직접 돌려 확인한 결과입니다.
목(Fake) 신호가 아니라 실제 Pleos SDK · VHAL 을 거친 값입니다.

검증일 2026-10-31 · Android 14 / API 34 / arm64 / 사용자 10번

## 환경 만들기

AVD 는 Android Studio 없이 만들 수 있습니다. `~/.android/avd/` 에 파일 두 개면 됩니다.

```
~/.android/avd/Pleos_Car.ini          ← path · target=android-34
~/.android/avd/Pleos_Car.avd/config.ini
```

`config.ini` 의 핵심 값입니다.

| 키 | 값 | 이유 |
|---|---|---|
| `image.sysdir.1` | `system-images/android-34/pleos_car_emulator/arm64-v8a/` | 시스템 이미지 위치 |
| `tag.id` | `android-automotive` | 이 값이 없으면 자동차 이미지로 뜨지 않습니다 |
| `hw.device.name` | `Pleos_Connect_v2_0` | 이미지의 `devices.xml` 에 정의된 기기 id |
| `hw.lcd.width/height` | `2560` / `1440` | 헤드유닛 해상도 |
| `hw.lcd.density` | `160` | mdpi |
| `hw.ramSize` | `4096` | 이미지 기본값은 8192 이지만 16GB 호스트에서는 과합니다 |
| `hw.gpu.mode` | `host` | Apple Silicon 에서 Metal 경유 |
| `fastboot.forceColdBoot` | `yes` | 차량 신호를 매번 초기 상태에서 시작 |

실행과 설치는 이렇습니다. **Pleos 는 화면 사용자가 10번**이라 `--user 10` 이 필요합니다.

```bash
~/Library/Android/sdk/emulator/emulator -avd Pleos_Car -no-snapshot-load -gpu host
adb install -r --user 10 car-app/app/build/outputs/apk/debug/app-debug.apk
adb shell am start --user 10 -n dev.semicolon.playseat.car/.MainActivity
```

## 차량 신호를 바꾸는 법 — Virtual Console

기기 안에서 `ai.pleos.connectwebconsole` 이 **8088 포트**로 웹 콘솔을 띄웁니다.

```bash
adb forward tcp:8088 tcp:8088
curl http://127.0.0.1:8088/vehicle/catalog   # 조작 가능한 63개 항목 전부
curl http://127.0.0.1:8088/vehicle/state     # 현재 값
```

**주의**: 쓰기는 `PUT` 이고, 그냥 보내면 `{"error":"emulator_inactive"}` 가 돌아옵니다.
콘솔 페이지가 1초마다 보내는 하트비트가 있어야 쓰기가 열립니다.

```bash
# 백그라운드로 계속 돌려둡니다
while true; do
  curl -s -X POST "http://127.0.0.1:8088/browser/session?client=cli" -o /dev/null
  sleep 1
done &

curl -X PUT "http://127.0.0.1:8088/vehicle/seat?kind=occupied&area=rear_left&value=true"
curl -X PUT "http://127.0.0.1:8088/vehicle/gear?gear=Drive"
curl -X PUT "http://127.0.0.1:8088/vehicle/ev-battery?field=charge_switch&value=true"
```

좌석은 `driver` `passenger` `rear_left` `rear_right` 네 곳만 조작됩니다.
2열 중앙은 SDK 가 착석 감지 대상으로 보고하지만 콘솔에서 바꿀 수 없습니다.

## 확인된 것

### 퍼미션 — 전부 통과

기기에 선언된 퍼미션은 모두 `prot=normal` 이고, 설치 즉시 `granted=true` 였습니다.
별도 승인 절차가 없습니다.

```
pleos.car.permission.CAR_SEATS         granted=true
pleos.car.permission.CAR_DRIVE         granted=true
pleos.car.permission.CAR_ENERGY        granted=true
pleos.car.permission.NAVI_ROUTE        granted=true
pleos.car.permission.NAVI_ROUTE_SEARCH granted=true
pleos.car.permission.TTS_SERVICE       granted=true
pleos.car.permission.STT_SERVICE       granted=true
```

`android.permission.RECORD_AUDIO` 만 런타임 권한이라 따로 받아야 합니다.

### 좌석 착석 — 기획의 전제가 성립합니다

```
I/PleosVehicle: 착석 감지 지원: [R2L, R2R, R2C, R1L, R1R]
```

5석 모두 `checkSeatOccupancyCapabilities` 가 지원으로 응답했고,
콘솔에서 좌석을 바꾸면 앱 UI 가 즉시 따라옵니다.
**"좌석에 앉으면 자동으로 들어온다"는 설계가 실제로 동작합니다.**

### 기어 · 충전 — 동작

- 기어 D → 주행 모드 전환, 조작권이 폰으로 이전
- 충전 스위치 ON → 충전 라운지 모드 발동, 차례 없이 모두 추가 가능

### ETA — 미검증

`NaviHelper.initialize` 는 성공하고 리스너도 등록됩니다(`NaviHelper 연결 완료`).
다만 `onDrivingInfo` 는 내비 앱에 실제 경로가 설정돼야 올라오는데,
에뮬레이터 기본 위치가 Mountain View 라 국내 경로를 만들지 못했습니다.
`ai.umos.maps.android.navigation.app.SET_DESTINATION` 인텐트가 있어 이쪽으로 더 볼 수 있습니다.

## 고친 것

### 1. SDK 의 착석 Boolean 이 뒤집혀 있습니다

`ai.pleos.playground:Vehicle:2.3.2` 의 `SeatOccupancyListener` 와
`getCurrentSeatOccupancy` 가 넘기는 Boolean 이 **실제 착석과 반대**입니다.

콘솔로 네 좌석을 모두 착석시키고 관찰한 결과입니다.

| 좌석 | 콘솔(실제) | VHAL raw | SDK Boolean | 보정 전 앱 |
|---|---|---|---|---|
| 운전석 | 착석 | 2 | `false` | 빈 좌석 |
| 조수석 | 착석 | 2 | `false` | 빈 좌석 |
| 2열 좌 | 착석 | 2 | `false` | 빈 좌석 |
| 2열 우 | 착석 | 2 | `false` | 빈 좌석 |
| 2열 중 | (조작 불가) | 1 | `true` | 참여 대기 |

VHAL 은 AAOS 표준(`1 = VACANT`, `2 = OCCUPIED`)을 그대로 따릅니다.
SDK 가 `value == VACANT` 를 참으로 넘기는 것으로 보입니다.

그대로 쓰면 **앉으면 빠지고 비면 들어오는** 정반대 동작이 됩니다.
`PleosVehicleSignalSource.readOccupancy` 에서 뒤집고,
근거를 클래스 KDoc 에 남겼습니다. SDK 가 고쳐지면
`OCCUPANCY_BOOLEAN_IS_INVERTED` 를 `false` 로 두면 됩니다.

### 2. 주행 중 앱이 통째로 가려졌습니다

기어를 D 로 넣는 순간 AAOS 가 앱 전체를
**"주행 중에는 사용할 수 없습니다"** 안내로 덮었습니다.
운전자 주의분산 제한(CarUxRestrictions)입니다.

주행 중 화면을 유지하는 것이 기획의 핵심이라 매니페스트에 선언을 넣었습니다.

```xml
<meta-data android:name="distractionOptimized" android:value="true" />
```

선언만 하면 되는 게 아니라 **실제로 그 조건을 지켜야** 합니다.
`CarScreen.DrivingView` 가 주행 중에는 목록·검색·키보드를 걷어내고
터치 타깃을 2개(다음 곡 · 말하기)로 줄입니다. 선곡 조작 자체가
동승자 폰으로 넘어가므로 운전자 화면에는 지금 재생 중인 곡과
다음 차례만 남습니다.

2차 PT 에서 설명할 거리입니다 — 주행 중 UI 를 줄인 건 디자인 취향이 아니라
플랫폼이 요구하는 조건이고, 우리 기획은 그 제약을 피해가는 게 아니라
제약을 그대로 제품의 명제로 삼았습니다.
