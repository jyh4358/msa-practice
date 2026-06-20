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
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // OpenAPI/Swagger UI — 인바운드 웹 어댑터 문서화(도메인·애플리케이션은 의존하지 않음).
    implementation(libs.springdoc.openapi.ui)

    // 스키마는 Flyway가 소유한다 (ddl-auto=validate). 계획 Phase 0 핵심 습관.
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
