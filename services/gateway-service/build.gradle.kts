plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

group = "com.shopsaga"
version = "0.0.1-SNAPSHOT"

// 다른 서비스와 동일하게 컴파일/테스트 타깃은 Java 21 고정.
java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:${libs.versions.springCloud.get()}")
    }
}

dependencies {
    // Spring Cloud Gateway (리액티브/WebFlux, Netty 런타임).
    // 주의 1) 2025.0.x(Northfields)에서 스타터 이름이 바뀜:
    //         spring-cloud-starter-gateway → spring-cloud-starter-gateway-server-webflux
    //         (구 이름은 deprecated, 부팅 시 경고. 2025.1/Oakwood에서 제거 예정)
    // 주의 2) 게이트웨이는 Netty 기반이므로 spring-boot-starter-web(Tomcat)을 절대 넣지 않는다(충돌).
    //         spring-boot-starter-webflux 는 이 스타터가 전이 의존성으로 가져온다.
    implementation("org.springframework.cloud:spring-cloud-starter-gateway-server-webflux")
    // /actuator/gateway/routes 로 라우팅 상태를 들여다보기 위함.
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // Phase 8: 관측성 — 리액티브 게이트웨이에도 트레이싱을 켜 다운스트림(lb://)으로 traceparent 전파.
    //          전부 Boot BOM 관리(버전 생략). OTLP → grafana/otel-lgtm(:4318).
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")
    implementation("io.micrometer:micrometer-registry-otlp")
    // Phase 8b: Logback → OTel SDK 브릿지(로그를 OTLP로 Loki 전송). Boot BOM OTel SDK(1.49.0)에 맞춰 버전 직접 핀.
    implementation("io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0:2.15.0-alpha")
    // Phase 5: 게이트웨이를 리액티브 OAuth2 리소스 서버로(엣지 JWT 검증). jose(RS256) 전이 포함.
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    // Phase 14: 엣지 복원력.
    //  · spring-cloud-circuitbreaker(reactor) → 라우트에 CircuitBreaker 필터 + fallbackUri 사용.
    //    (이 스타터가 resilience4j-spring-boot3 를 전이로 끌고 온다 — 게이트웨이에도 resilience4j.* 설정이 바인딩된다.)
    //    다만 회로 설정은 yml 이 아니라 EdgeCircuitBreakerConfig 의 Customizer 한 곳에서만 한다(출처 이원화 방지).
    //  · reactor 연산자 + micrometer 는 RateLimiter/Bulkhead 를 직접 붙이고 관측하기 위해 명시 추가.
    implementation("org.springframework.cloud:spring-cloud-starter-circuitbreaker-reactor-resilience4j")
    implementation(libs.resilience4j.reactor)
    // bulkhead/ratelimiter 코어는 spring-cloud 스타터에 전이되지 않는다(circuitbreaker·timelimiter만) → 명시.
    implementation(libs.resilience4j.bulkhead)
    implementation(libs.resilience4j.ratelimiter)
    implementation(libs.resilience4j.micrometer)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
