plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
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

    buildTypes {
        release {
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

    // TODO(Pleos): 저장소·좌표 확인 후 활성화합니다.
    //   PleosVehicleSignalSource 가 이 의존성만 사용하도록 격리되어 있어,
    //   없는 상태에서도 FakeVehicleSignalSource 로 전체 앱이 빌드·실행됩니다.
    // implementation(libs.pleos.vehicle)
    // implementation(libs.pleos.navihelper)
    // implementation(libs.pleos.tts)
    // implementation(libs.pleos.stt)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
