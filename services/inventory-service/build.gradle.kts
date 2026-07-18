plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
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
    // 서블릿 OAuth2 리소스 서버(JWT 검증) — GET /inventory 보호.
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation(libs.springdoc.openapi.ui)

    // Phase 8: 관측성 — 트레이싱·메트릭·로그 OTLP.
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")
    implementation("io.micrometer:micrometer-registry-otlp")
    implementation("io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0:2.15.0-alpha")

    // Phase 9: Kafka 소비(OrderPlaced) + 이벤트 계약 공유 모듈.
    implementation("org.springframework.kafka:spring-kafka")
    implementation(project(":shared:events"))

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
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
