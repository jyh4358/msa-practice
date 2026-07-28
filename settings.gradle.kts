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

include(":services:discovery-service")
include(":services:config-service")
include(":services:order-service")
include(":services:payment-service")
include(":services:gateway-service")
include(":services:auth-service")
// Phase 9: 비동기 이벤트(Kafka) — 재고를 자기 서비스로 분리 + 이벤트 계약 전용 공유 모듈.
include(":services:inventory-service")
include(":shared:events")
// Phase 11: CQRS — 이벤트를 구독해 비정규화 읽기 모델(MongoDB)을 유지하는 조회 전용 서비스.
include(":services:order-query-service")
// Phase 12: Saga — outbox 메커니즘(테이블 아님, 코드)을 3개 발행 서비스가 공유하는 라이브러리.
include(":shared:outbox")
// Phase 14: 소비 실패 처리(유한 백오프 재시도 → DLQ) 메커니즘을 소비 서비스 4곳이 공유하는 라이브러리.
include(":shared:messaging")
