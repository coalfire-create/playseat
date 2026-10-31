pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // TODO(Pleos): Pleos Connect SDK Maven 저장소를 여기에 추가합니다.
        //   공식 안내: https://document.pleos.ai/docs/connect/guide/getting-started/
        //              application-development/setup-connect-sdk-emulator
        //   저장소 URL과 좌표를 확인한 뒤 gradle/libs.versions.toml 의 pleos-* 항목과 함께 활성화하세요.
        // maven { url = uri("<Pleos Maven 저장소 URL>") }
    }
}
rootProject.name = "playseat-car"
include(":app")
