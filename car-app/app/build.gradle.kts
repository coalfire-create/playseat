import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * release 서명 키.
 *
 * `car-app/keystore.properties` 와 키스토어 파일은 커밋하지 않습니다(.gitignore).
 * 파일이 없으면 서명 설정 없이 빌드합니다 — 저장소를 받은 사람이 키 없이도
 * `assembleRelease` 를 돌릴 수 있어야 하기 때문입니다.
 */
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "dev.semicolon.playseat.car"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.semicolon.playseat.car"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // 허브 주소. 에뮬레이터에서 호스트 PC는 10.0.2.2 입니다.
        buildConfigField("String", "HUB_HOST", "\"10.0.2.2:8080\"")
        buildConfigField("String", "SESSION_ID", "\"a3f9\"")

        // 시뮬레이션 재생 속도. 시연 영상 녹화 때 올려 장거리 주행을 압축합니다.
        buildConfigField("int", "PLAYBACK_SPEED", "1")
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.zxing.core)
    implementation(libs.coil.compose)

    // Pleos Connect SDK. 실제 신호 구현은 vehicle/·voice/ 패키지 안에만 들어갑니다.
    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.navihelper)
    implementation(libs.pleos.tts)
    implementation(libs.pleos.stt)
    implementation(libs.pleos.fusedlocation)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
