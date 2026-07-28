// Phase 14: Kafka <b>소비 실패 처리</b>(재시도 백오프 + DLQ) 메커니즘을 담은 공유 라이브러리.
//
// shared:outbox 가 "발행 쪽 신뢰성"이라면, 이 모듈은 "소비 쪽 신뢰성"이다.
// 소비 중 예외가 나면 Kafka 는 같은 레코드를 무한히 재배달한다 → 그 파티션이 영영 막힌다(head-of-line blocking).
// 유한 백오프로 몇 번 재시도한 뒤 `<토픽>.DLT` 로 치워서 뒤에 줄 선 정상 메시지가 흐르게 하는 것이 목적이다.
//
// ⚠️ shared:outbox 와 마찬가지로 **공유 라이브러리(코드)** 일 뿐 공유 인프라가 아니다.
//    DLT 토픽은 원본 토픽 소유자의 네임스페이스에 그대로 붙는다(`order-events.DLT` 등).
plugins {
    `java-library`
    alias(libs.plugins.spring.dependency.management)
}

group = "com.shopsaga"
version = "0.0.1-SNAPSHOT"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}")
    }
}

dependencies {
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // api = 이 모듈을 쓰는 서비스에도 노출(DefaultErrorHandler·KafkaTemplate 타입이 시그니처에 등장).
    api("org.springframework.kafka:spring-kafka")
    api(project(":shared:events"))
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.slf4j:slf4j-api")
}
