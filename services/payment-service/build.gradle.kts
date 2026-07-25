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
    // Phase 4: Eureka 클라이언트 — 부팅 시 payment-service 이름으로 레지스트리에 자동 등록.
    implementation("org.springframework.cloud:spring-cloud-starter-netflix-eureka-client")
    // Phase 6: Config 클라이언트 — spring.config.import 로 config-service(:8888)에서 설정을 가져온다.
    implementation("org.springframework.cloud:spring-cloud-starter-config")
    // Phase 5: 서블릿 OAuth2 리소스 서버(JWT 검증).
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation(libs.springdoc.openapi.ui)

    // Phase 12(Saga): 결제가 이벤트를 소비·발행한다 → Kafka + 이벤트 계약 + outbox/inbox 메커니즘.
    implementation("org.springframework.kafka:spring-kafka")
    implementation(project(":shared:events"))
    implementation(project(":shared:outbox"))

    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
