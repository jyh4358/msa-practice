// Phase 12: 트랜잭셔널 Outbox <b>메커니즘</b>을 담은 공유 라이브러리.
//
// ⚠️ 중요한 구분: 이건 **공유 라이브러리(코드 재사용)** 이지 **공유 데이터베이스가 아니다**.
//    각 서비스는 여전히 자기 DB에 자기 `outbox` 테이블을 갖는다(Flyway 마이그레이션도 서비스별).
//    여기 있는 건 "그 테이블을 어떻게 쓰고 어떻게 릴레이하는가"라는 기술 메커니즘뿐 —
//    도메인 데이터나 업무 규칙은 절대 넣지 않는다(넣는 순간 분산 모놀리스가 된다).
//
// bootJar 가 필요 없으므로 spring-boot 플러그인을 적용하지 않고, BOM 만 임포트해 버전을 맞춘다.
plugins {
    `java-library`
    alias(libs.plugins.spring.dependency.management)
}

group = "com.shopsaga"
version = "0.0.1-SNAPSHOT"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}")
    }
}

dependencies {
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // api = 이 모듈을 쓰는 서비스에도 노출(엔티티/리포지토리 타입이 시그니처에 등장).
    api("org.springframework.boot:spring-boot-starter-data-jpa")
    api("org.springframework.kafka:spring-kafka")
    api(project(":shared:events"))
    // 트레이스 컨텍스트 저장·복원(traceparent) — Saga 전체를 한 트레이스로 잇기 위해 필요.
    api("io.micrometer:micrometer-tracing")
    implementation("org.springframework.boot:spring-boot-starter-json")
}
