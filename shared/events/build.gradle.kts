// Phase 9: 이벤트/DTO 계약 전용 공유 모듈.
// ⚠️ 여기에는 이벤트 스키마(POJO/record)만 둔다. JPA @Entity·리포지토리·Spring 의존을 넣는 순간
//    "분산 모놀리스"가 되어 db-per-service 원칙이 무너진다(계획 §부록).
plugins {
    `java-library`
}

group = "com.shopsaga"
version = "0.0.1-SNAPSHOT"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}
