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
    // Eureka 클라이언트 — auth-service 이름으로 등록(게이트웨이 lb://auth-service 라우팅).
    implementation("org.springframework.cloud:spring-cloud-starter-netflix-eureka-client")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
