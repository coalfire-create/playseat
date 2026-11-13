# 제출용 release APK

| 항목 | 값 |
|---|---|
| 파일 | `Semicolon_플레이시트_release.apk` |
| 버전 | 0.1.0 (versionCode 1) |
| 크기 | 40.7 MB |
| SHA-256 | `85f352fdee881e1e13e65637c1aa9752ae3f3d2b150a2172ca598b0dfc74338c` |
| 서명 | 자체 서명 (CN=Semicolon) · SHA-256 `cd747d10…57b7` |
| 타깃 | Pleos Connect (AAOS) · minSdk 28 / targetSdk 35 |

## 설치

Pleos Connect 에뮬레이터는 디스플레이가 **사용자 10번**에 붙어 있습니다.
기본 사용자(0번)에 설치하면 앱이 화면에 뜨지 않습니다.

```bash
adb install -r --user 10 "Semicolon_플레이시트_release.apk"
adb shell am start --user 10 -n dev.semicolon.playseat.car/.MainActivity
```

## 동작에 필요한 것

차량 앱만 설치해도 화면은 뜨지만, 동승자 폰이 붙으려면 허브 서버가 필요합니다.

```bash
cd hub-server && ./gradlew run      # 호스트 PC 에서 실행 (8080)
```

APK 에는 허브 주소가 `10.0.2.2:8080` (에뮬레이터에서 본 호스트 PC) 으로
박혀 있습니다. 실차나 다른 호스트에서 쓰려면 `car-app/app/build.gradle.kts` 의
`HUB_HOST` 를 바꿔 다시 빌드하세요.

## 다시 빌드하려면

```bash
cd car-app
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
./gradlew :app:assembleRelease
```

서명 키(`keystore.properties`, `playseat-release.jks`)는 저장소에 포함하지
않습니다. 키가 없으면 **서명 없는** release APK 가 만들어집니다 — 빌드는 됩니다.
