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
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")

    implementation("org.springframework.boot:spring-boot-starter-web")
    // Phase 11: 읽기 모델 저장소 = MongoDB(문서형). 블로킹(서블릿) 스타터 — 리액티브 아님.
    //           Boot BOM 관리(버전 생략) → Spring Data MongoDB + mongodb-driver-sync.
    implementation("org.springframework.boot:spring-boot-starter-data-mongodb")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // 조회 API 보호(리소스 서버) — 다른 서비스와 동일한 RS256 JWT 검증.
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation(libs.springdoc.openapi.ui)

    // Eureka 클라이언트(게이트웨이 lb:// 라우팅 대상) + Config 클라이언트(:8888).
    implementation("org.springframework.cloud:spring-cloud-starter-netflix-eureka-client")
    implementation("org.springframework.cloud:spring-cloud-starter-config")
    // Phase 15: Spring Cloud Bus(Kafka 백엔드) — 한 인스턴스에 POST /actuator/busrefresh 하면
    //           springCloudBus 토픽으로 RefreshRemoteApplicationEvent 가 퍼져 전 인스턴스가 설정을 다시 읽는다.
    implementation("org.springframework.cloud:spring-cloud-starter-bus-kafka")

    // Phase 8: 관측성 — 트레이싱·메트릭·로그 OTLP.
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")
    implementation("io.micrometer:micrometer-registry-otlp")
    implementation("io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0:2.15.0-alpha")

    // Phase 11: 이벤트 구독(투영) — Kafka 소비 + 이벤트 계약 공유 모듈. (쓰기 DB는 쳐다보지 않는다.)
    implementation("org.springframework.kafka:spring-kafka")
    implementation(project(":shared:events"))
    // Phase 14: 소비 실패 → 유한 재시도 → DLQ 공유 설정(읽기 모델은 발행하지 않으므로 outbox 는 없다).
    implementation(project(":shared:messaging"))

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // 투영 결정성/조회 통합 테스트 — 실제 MongoDB 컨테이너 필요(Docker).
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:mongodb")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
