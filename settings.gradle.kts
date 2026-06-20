// ShopSaga MSA 학습 플랫폼 — Gradle 멀티모듈 모노레포 (Phase 0)
// 새 서비스는 여기에 include 한 줄로 추가한다.

plugins {
    // Java 21 툴체인을 자동으로 내려받게 해 주는 resolver.
    // → 로컬에 JDK 21이 없어도 Gradle이 알아서 프로비저닝한다.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

// 의존성 저장소를 한 곳에서 선언(모든 모듈 공통). 버전 카탈로그(gradle/libs.versions.toml)와 함께
// 모노레포의 단일 진실 공급원 역할.
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "msa-platform"

include(":services:order-service")
