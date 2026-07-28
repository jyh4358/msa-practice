plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

group = "com.shopsaga"
version = "0.0.1-SNAPSHOT"

// 빌드는 설치된 JDK(24)로 실행하되, 컴파일/테스트 타깃은 Java 21로 고정 (재현성).
// 로컬에 JDK 21이 없으면 foojay resolver가 자동으로 내려받는다.
java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:${libs.versions.springCloud.get()}")
    }
}

dependencies {
    // Lombok (compile-time only). annotationProcessor 순서상 QueryDSL APT 보다 먼저 선언.
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // Phase 8: 관측성 — 분산 트레이싱(OTel 브릿지 + OTLP 익스포터) + 메트릭 OTLP push.
    //          전부 Boot BOM 관리(버전 생략). OTLP → grafana/otel-lgtm(:4318).
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")
    implementation("io.micrometer:micrometer-registry-otlp")
    // Phase 8b: Logback → OTel SDK 브릿지(로그를 OTLP로 Loki 전송). Boot BOM OTel SDK(1.49.0)에 맞춰 버전 직접 핀.
    implementation("io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0:2.15.0-alpha")
    // Phase 9: Kafka 발행/소비 + 이벤트 계약 공유 모듈.
    implementation("org.springframework.kafka:spring-kafka")
    implementation(project(":shared:events"))
    // Phase 12: outbox·inbox 메커니즘 공유 라이브러리(테이블은 여전히 서비스별 자기 DB).
    implementation(project(":shared:outbox"))
    // Phase 14: 소비 실패 → 유한 재시도 → DLQ 공유 설정.
    implementation(project(":shared:messaging"))
    // Phase 14: 재고 사전 확인(동기 호출)에 복원력 5종. 애너테이션 aspect 라 AOP 스타터가 반드시 필요하다.
    implementation(libs.resilience4j.spring.boot3)
    implementation("org.springframework.boot:spring-boot-starter-aop")
    // Phase 4: Eureka 클라이언트(부팅 시 자동 등록) + spring-cloud-loadbalancer(전이 포함)
    //          → @LoadBalanced RestClient 로 payment-service 를 이름으로 호출.
    implementation("org.springframework.cloud:spring-cloud-starter-netflix-eureka-client")
    // Phase 6: Config 클라이언트 — spring.config.import 로 config-service(:8888)에서 설정을 가져온다.
    //          (bootstrap.yml/starter-bootstrap 쓰지 않음 — 2025.0.x 표준 import 모델.)
    implementation("org.springframework.cloud:spring-cloud-starter-config")
    // Phase 5: 서블릿 OAuth2 리소스 서버(JWT 검증) + method security(@PreAuthorize).
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    // OpenAPI/Swagger UI — 인바운드 웹 어댑터 문서화(도메인·애플리케이션은 의존하지 않음).
    implementation(libs.springdoc.openapi.ui)

    // 스키마는 Flyway가 소유한다 (ddl-auto=validate). 계획 Phase 0 핵심 습관.
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)

    // QueryDSL (타입세이프 쿼리) — 리포지토리 JPQL/@Lock 대신 사용. jakarta classifier 필수.
    implementation(variantOf(libs.querydsl.jpa) { classifier("jakarta") })
    annotationProcessor(variantOf(libs.querydsl.apt) { classifier("jakarta") })
    annotationProcessor(libs.jakarta.persistence.api)
    annotationProcessor(libs.jakarta.annotation.api)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // 동시성(oversell) 회귀 가드용 통합 테스트 — 실제 PostgreSQL 컨테이너 필요(Docker).
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
