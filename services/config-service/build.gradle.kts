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
    // Config Server — @EnableConfigServer + GET /{app}/{profile} API + /encrypt·/decrypt.
    // (이름에 -starter- 없음: 이 아티팩트가 곧 스타터이자 본체. 버전은 BOM이 관리.)
    // 대칭키(AES) 암호화는 추가 의존성 불필요 — spring-security crypto 가 전이 포함, Java 21은 무제한 JCE 기본.
    implementation("org.springframework.cloud:spring-cloud-config-server")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
