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

    // 서블릿 웹(Tomcat) — POST /auth/login, GET /oauth2/jwks 엔드포인트.
    implementation("org.springframework.boot:spring-boot-starter-web")
    // 인메모리 사용자 + BCrypt(UserDetailsService, PasswordEncoder) + SecurityFilterChain.
    implementation("org.springframework.boot:spring-boot-starter-security")
    // NimbusJwtEncoder / JwtClaimsSet / JwsHeader / SignatureAlgorithm + nimbus-jose-jwt 전이 포함(RS256 서명).
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // Phase 8: 관측성 — 분산 트레이싱(OTel 브릿지 + OTLP 익스포터) + 메트릭 OTLP push.
    //          전부 Boot BOM 관리(버전 생략). OTLP → grafana/otel-lgtm(:4318).
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")
    implementation("io.micrometer:micrometer-registry-otlp")
    // Phase 8b: Logback → OTel SDK 브릿지(로그를 OTLP로 Loki 전송). Boot BOM OTel SDK(1.49.0)에 맞춰 버전 직접 핀.
    implementation("io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0:2.15.0-alpha")
    // Eureka 클라이언트 — auth-service 이름으로 등록(게이트웨이 lb://auth-service 라우팅).
    implementation("org.springframework.cloud:spring-cloud-starter-netflix-eureka-client")
    // Phase 6: Config 클라이언트 — spring.config.import 로 config-service(:8888)에서 설정을 가져온다.
    implementation("org.springframework.cloud:spring-cloud-starter-config")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
