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

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
