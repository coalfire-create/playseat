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

### 2. 허브에 아예 붙지 못했습니다

```
W/HubClient: 허브 연결 실패:
  CLEARTEXT communication to 10.0.2.2 not permitted by network security policy
```

Android 9 부터 평문 통신이 기본 차단입니다. 허브는 로컬 네트워크에서만 도는
서버라 인증서가 없고 `ws://` 로 붙는데, 그 선언이 빠져 있었습니다.

좌석·기어·충전은 차량 신호라 잘 들어오는데 **곡과 좌석 닉네임만 영영 비어 있는**
상태라 원인을 짐작하기 어렵습니다. 에뮬레이터 없이 2차 PT 당일 처음 돌렸다면
시연이 그대로 멈췄을 문제입니다.

`res/xml/network_security_config.xml` 을 두고 매니페스트에서 가리킵니다.
허브 주소가 `BuildConfig.HUB_HOST` 로 바뀌기 때문에(시연장 노트북 IP 등)
특정 도메인만 여는 방식은 쓸 수 없었습니다.

### 3. 주행 중 앱이 통째로 가려졌습니다

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


## 시연할 때 알아둘 것

### 충전 라운지는 충전구나 배터리가 움직여야 켜집니다

Virtual Console 에서 `charge_switch` 만 바꾸면 **아무 VHAL 이벤트도 올라오지 않습니다.**
앱은 충전구 개폐(`EvChargePortOpenListener`)와 배터리 잔량 변화를 받을 때
충전 스위치를 다시 조회하는 방식이라, 스위치만 토글하면 화면이 그대로입니다.

실제 차에서는 플러그를 꽂으면 충전구가 먼저 열리므로 자연스럽게 동작합니다.
에뮬레이터에서는 둘을 같이 주세요.

```bash
curl -X PUT ".../vehicle/ev-battery?field=charge_switch&value=true"
curl -X PUT ".../vehicle/ev-battery?field=charge_port_open&value=true"
```

### 선곡 차례는 30초마다 자동으로 넘어갑니다

처음에는 `turnDeadlineMs` 가 계약에만 있고 적용되지 않아서, 차례인 좌석이
아무것도 하지 않으면 순서가 영영 멈췄습니다. 특히 운전석은 주행 중 화면을
만질 수 없고 음성으로도 곡을 추가할 수 없어서 거기서 전체가 막혔습니다.
지금은 제한시간이 실제로 돌고, 남은 시간이 `turnRemainingMs` 로 폰까지 내려갑니다.

에뮬레이터에서 폰 3대를 붙여 두고 90초간 아무것도 하지 않고 지켜본 결과입니다.

```
[ 0초] 차례 → 2열 좌
[10초] 차례 → 2열 우
[40초] 차례 → 운전석      ← 여기서 멈추던 자리
[70초] 차례 → 조수석
```

제한시간이 돌지 않는 경우가 셋 있고, 모두 의도한 것입니다.
라운지 모드(턴 규칙 자체가 풀림), 착석한 좌석이 하나뿐(넘겨도 제자리),
아직 아무도 폰을 연결하지 않음(넘겨도 쓸 사람이 없고 안내 음성만 반복됨).


## 화면 디자인은 Pleos 시스템 UI 를 따릅니다

색과 구성은 추측이 아니라 에뮬레이터의 차량 설정 화면을 캡처해 픽셀에서 뽑았습니다.

| | 주간 | 야간 |
|---|---|---|
| 배경·셀 | `#FFFFFF` | `#151515` |
| 선택된 셀 | `#F0F0F0` | `#222222` |
| 구분선 | `#D6D6D6` | `#333333` |
| 본문 | `#000000` | `#DDDDDD` |
| 보조 | `#7C7C7C` | `#8E8E8E` |
| 강조 | `#1262F5` | `#1262F5` |

`adb shell cmd uimode night yes|no` 로 주야간을 바꿔 양쪽을 확인했습니다.
Pleos 는 이 설정을 따라 전체 UI 가 바뀌므로, 우리 앱도 `isSystemInDarkTheme()` 로 따라갑니다.

관찰한 규칙입니다.

- 면을 둥근 카드로 띄우지 않습니다. 1dp 헤어라인으로 나눈 평면 셀을 붙입니다
- 셀은 [선 아이콘 → 이름(굵게) → 상태값(작게)] 순서로 왼쪽 정렬하고,
  아이콘은 셀 위쪽, 글자는 아래쪽에 둡니다
- 색을 거의 쓰지 않습니다. 파랑은 '지금 활성' 인 값 하나에만 씁니다
- 선택된 셀은 배경을 한 단계 올리고 값 글자를 파랑으로 바꿉니다
- 선택된 세그먼트·탭은 검정으로 채우고 글자를 흰색으로 뒤집습니다

헤드유닛은 앱 하나를 쓰는 기기가 아닙니다. 내비·전화·차량설정 사이를 계속 오가기 때문에,
우리 앱만 다른 언어를 쓰면 나중에 얹은 것으로 보입니다.


## 앨범 커버는 74곡 중 40곡까지입니다

큐레이션 목록은 네트워크 없이 즉시 답하는 것이 존재 이유라 메타데이터만 들고 있습니다.
커버는 허브가 시작 직후 백그라운드로 Deezer 에서 찾아 채웁니다.

결과는 **40/74** 입니다. 못 찾은 34곡은 대부분 한글 제목의 국내 발라드·동요입니다.
Deezer 가 그 곡들을 색인하지 않았거나, 영문 표기로만 들고 있어 제목이 맞지 않습니다.

숫자를 올리려고 두 가지를 시도했고 둘 다 접었습니다.

**관련도로 느슨하게 걸러 보기** — 55/74 까지 올라갔지만 틀린 커버가 섞였습니다.
세븐틴 '손오공' 에 "손오공 (SEVENTEEN) KARAOKE" 노래방 음원의 커버가 붙었습니다.
제목에 가수 이름이 들어간 음원이 많아서 생긴 구멍입니다.
지금은 아티스트 이름이 실제로 같을 때만 받습니다(`ArtistAlias.sameArtist`).
그림이 틀리면 없는 것만 못합니다.

**iTunes Search API** — 한글 아티스트명에 강하다고 알려져 있고, 예열은 느린
백그라운드 작업이라 요청 제한을 피할 수 있을 줄 알았습니다. 실제로는 제한이 아니라
한국 스토어가 곡 엔티티를 내주지 않는 것이 문제였습니다.

```
entity=song  + country=KR  → 0건 (HTTP 200, 정상 응답)
entity=musicTrack          → 뮤직비디오만, 그것도 다른 곡
```

커버가 없는 곡은 음표 자리표시자가 나옵니다. 목록의 줄 높이가 흔들리지 않도록
자리는 항상 차지합니다. Deezer 로 보완된 곡과 폰이 직접 검색해 넣은 곡은
커버가 정상적으로 나옵니다.

## 주간·야간 모두 확인했습니다

`adb shell cmd uimode night yes|no` 로 전환해 좌석도·앨범 커버·대기열을 모두 봤습니다.
야간에는 좌석이 실내보다 어둡고(주간은 반대), 양쪽 다 타고 있는 좌석이 구분됩니다.
