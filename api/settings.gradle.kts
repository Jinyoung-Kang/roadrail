plugins {
    // JDK 21 이 없는 머신에서도 ./gradlew 가 툴체인을 자동으로 내려받습니다.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "roadrail-api"
