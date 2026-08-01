plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    // Phase 15: 이 서비스는 재고 조회 API의 <프로듀서>다.
    //  contracts/ 의 계약 파일 → ① 프로듀서 검증 테스트 자동 생성 ② 소비자용 stub jar 생성.
    alias(libs.plugins.spring.cloud.contract)
    `maven-publish`   // stub jar 를 로컬 Maven 저장소에 올려 소비자(order-service)가 가져가게 한다
}

group = "com.shopsaga"
version = "0.0.1-SNAPSHOT"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:${libs.versions.springCloud.get()}")
    }
}

dependencies {
    // Lombok (annotationProcessor 순서상 QueryDSL APT 보다 먼저).
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // Phase 9: Eureka 클라이언트 — inventory-service 이름으로 등록(게이트웨이 /inventory 라우팅).
    implementation("org.springframework.cloud:spring-cloud-starter-netflix-eureka-client")
    // Config 클라이언트 — config-service(:8888)에서 설정 수신.
    implementation("org.springframework.cloud:spring-cloud-starter-config")
    // Phase 15: Spring Cloud Bus(Kafka 백엔드) — 한 인스턴스에 POST /actuator/busrefresh 하면
    //           springCloudBus 토픽으로 RefreshRemoteApplicationEvent 가 퍼져 전 인스턴스가 설정을 다시 읽는다.
    implementation("org.springframework.cloud:spring-cloud-starter-bus-kafka")
    // 서블릿 OAuth2 리소스 서버(JWT 검증) — GET /inventory 보호.
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation(libs.springdoc.openapi.ui)

    // Phase 8: 관측성 — 트레이싱·메트릭·로그 OTLP.
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")
    implementation("io.micrometer:micrometer-registry-otlp")
    implementation("io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0:2.15.0-alpha")

    // Phase 9: Kafka 소비/발행 + 이벤트 계약 공유 모듈.
    implementation("org.springframework.kafka:spring-kafka")
    implementation(project(":shared:events"))
    // Phase 12: outbox·inbox 메커니즘 공유 라이브러리(테이블은 여전히 서비스별 자기 DB).
    implementation(project(":shared:outbox"))
    // Phase 14: 소비 실패 → 유한 재시도 → DLQ 공유 설정.
    implementation(project(":shared:messaging"))

    // 스키마는 Flyway 소유(ddl-auto=validate).
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)

    // QueryDSL — 재고 비관적 락 조회(SELECT … FOR UPDATE). jakarta classifier 필수.
    implementation(variantOf(libs.querydsl.jpa) { classifier("jakarta") })
    annotationProcessor(variantOf(libs.querydsl.apt) { classifier("jakarta") })
    annotationProcessor(libs.jakarta.persistence.api)
    annotationProcessor(libs.jakarta.annotation.api)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // 재고 동시성(oversell) 회귀 가드 — 실제 PostgreSQL 컨테이너 필요.
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Phase 15: 계약에서 생성된 테스트가 쓰는 런타임(RestAssured MockMvc 등).
    contractTestImplementation("org.springframework.cloud:spring-cloud-starter-contract-verifier")
    contractTestImplementation("org.springframework.boot:spring-boot-starter-test")
}

// ── Phase 15: 계약 테스트 설정 ──────────────────────────────────────────────
// 계약 위치는 src/contractTest/resources/contracts (3.0+ 기본값).
// packageWithBaseClasses: contracts/rest/*.yml → 베이스 클래스 <이 패키지>.RestBase 를 찾는다(규칙 기반).
contracts {
    testFramework = org.springframework.cloud.contract.verifier.config.TestFramework.JUNIT5
    testMode = org.springframework.cloud.contract.verifier.config.TestMode.MOCKMVC
    packageWithBaseClasses = "com.shopsaga.inventory.adapter.in.web"
}

// 소비자가 `com.shopsaga:inventory-service:+:stubs` 로 가져갈 수 있도록 stub jar 만 발행한다.
// (bootJar 는 발행하지 않는다 — 계약 소비에 필요한 것은 stub 뿐이다.)
publishing {
    publications {
        create<MavenPublication>("stubs") {
            artifact(tasks.named("verifierStubsJar"))
        }
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
