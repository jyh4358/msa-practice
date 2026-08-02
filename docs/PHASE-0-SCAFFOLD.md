# 프로젝트 스캐폴드 & 툴체인 (Phase 0)

> **이 문서는 Phase 0 작업을 설명합니다.** 아무것도 없는 빈 폴더에서 "빌드되고, 부팅되고, DB에
> 스키마를 스스로 만드는 서비스 1개"까지 어떻게 도달했는지를, 처음 보는 사람이 끝까지 이해하도록
> 개념 → 구성 → 실제 파일/설정 → 동작 흐름 → 원리/트레이드오프 → 검증 → 한계 순으로 정리했습니다.
>
> **이 문서의 성격(꼭 읽으세요):** 이 문서는 이미 완성된 Phase 0 결과물을 되짚어 설명하는 **회고형**
> 문서입니다. 아래 §4의 코드 스니펫은 핵심만 **발췌**한 것이며(`// ...`·`...` 로 생략된 부분이 있음),
> 전체 소스는 저장소의 실제 파일을 참고하세요. 처음부터 직접 셋업/실행해 보려면 **[SETUP.md](SETUP.md)** 를
> 따르세요.

---

## 0. 한 줄 요약

> **"빈 폴더 → `git clone` 후 `./gradlew`만 치면 똑같이 빌드/실행되는 재현 가능한 서비스"의 토대를 깔았다.**
> 빌드 도구(Gradle 멀티모듈 모노레포), 버전 고정(버전 카탈로그), 언어 고정(Java 21 툴체인 자동
> 프로비저닝), DB 스키마 소유권(Flyway가 소유, Hibernate는 `validate`만), 컨테이너 런타임(Colima +
> per-service Postgres)까지 — 이후 모든 Phase가 올라탈 **바닥**을 만드는 단계다.

---

## 1. 왜 이 단계인가? (직전까지의 문제)

직전이랄 게 없다. **빈 디렉터리**가 출발점이다. 하지만 "일단 되게" 아무렇게나 시작하면 곧
아래 문제들이 터진다.

- **"내 컴퓨터에선 되는데요."** JDK 버전, Gradle 버전, 라이브러리 버전이 사람/머신마다 달라
  빌드가 재현되지 않는다.
- **버전 지옥.** Spring Boot와 Spring Cloud를 짝이 안 맞게 섞으면 클래스패스 오류가 난다.
  버전을 파일마다 흩뿌려두면 올릴 때 하나만 빠뜨려 깨진다.
- **스키마 표류(drift).** 앱이 실행될 때마다 ORM이 테이블을 제멋대로 만들거나 바꾸면, 개발/운영
  DB가 서로 달라지고 "어떤 컬럼이 언제 왜 생겼는지"를 아무도 모른다.
- **서비스가 늘어날 것을 안다.** 이 학습 로드맵은 최종적으로 여러 서비스로 자란다. 처음부터
  "새 서비스 = include 한 줄"이 되도록 구조를 잡아둬야 나중이 편하다.

Phase 0은 코드를 많이 짜는 단계가 아니라 **이 함정들을 미리 막는 규칙과 뼈대**를 세우는 단계다.
그래서 뒤의 모든 Phase(디스커버리·보안·Saga···)가 "바닥이 흔들리는" 걱정 없이 올라탈 수 있다.

> 헥사고날(Ports & Adapters) 아키텍처를 서비스 내부 구조로 채택한 것도 이 단계다. 다만 그 내용은
> 중복 서술하지 않고 **[HEXAGONAL.md](HEXAGONAL.md)** 로 넘긴다(이 문서는 "서비스 바깥의 뼈대"에 집중).

---

## 2. 핵심 개념 (초심자용)

### 2.1 모노레포(monorepo) + Gradle 멀티모듈
여러 서비스를 **하나의 git 저장소** 안에서 관리하는 방식이 모노레포다. Gradle에서는 이를
**멀티모듈**로 표현한다 — 루트 프로젝트 하나 아래에 `:services:order-service` 같은 서브프로젝트를
매단다. 새 서비스는 `settings.gradle.kts`에 `include(...)` **한 줄**로 추가된다.

### 2.2 버전 카탈로그(version catalog) = 단일 진실 공급원
라이브러리/플러그인 버전을 **한 파일(`gradle/libs.versions.toml`)** 에만 적고, 각 모듈은 이름으로
참조한다(`libs.springdoc.openapi.ui`). 버전을 올릴 때 **한 곳만** 고치면 되므로 "여기저기 흩어진
버전이 어긋나서 깨지는" 사고를 막는다.

### 2.3 BOM(Bill of Materials)
"이 라이브러리 묶음은 이 버전들로 서로 호환됨"을 보증하는 **버전 목록표**다. Spring Boot/Spring
Cloud는 BOM을 제공하므로, 우리는 **개별 라이브러리 버전을 적지 않고** "BOM 하나만 고정"하면
Flyway·Postgres 드라이버 등의 버전은 BOM이 알아서 맞춰준다.

### 2.4 Gradle 툴체인(toolchain) + foojay 자동 프로비저닝
"빌드를 **어떤 JDK로 실행**하느냐"와 "코드를 **어떤 Java 버전으로 컴파일**하느냐"는 별개다.
(예: 개발자 머신의 **실행 JDK는 24**여도 **컴파일 타깃은 21**로 고정 — 자세한 건 §6.3.)
툴체인은 컴파일/테스트 타깃을 **Java 21**로 고정한다(재현성). 로컬에 JDK 21이 없으면 **foojay
resolver** 플러그인이 부팅 시 알아서 21을 내려받는다 — "아무것도 설치 안 해도 똑같이 21로 빌드".

### 2.5 스키마는 코드다 — Flyway 소유 + `ddl-auto=validate`
DB 테이블을 만드는 주체를 **Flyway**(마이그레이션 도구)로 못박는다. `db/migration/V1__init.sql`
같은 **버전이 매겨진 SQL 파일**이 스키마의 진실이다. Hibernate(JPA 명세의 대표 구현체 — JPA는
"이렇게 하라"는 표준 명세이고 Hibernate는 그걸 실제로 구현한 라이브러리다)는 자바 **엔티티**
클래스(=DB 테이블에 매핑되는 객체)와 실제 테이블 구조를 **만들지 않고** `validate`로 "일치하는지
검증만" 한다. 불일치면 **부팅 실패**로 즉시 알려준다.

### 2.6 database-per-service
각 서비스가 **자기 DB만** 소유한다. 남의 DB를 직접 만지지 않는 것이 MSA의 대원칙. Phase 0에선
`order-service`가 `orderdb` 하나를 갖는 것으로 그 습관을 시작한다.

### 2.7 Colima = 로컬 컨테이너 런타임
Docker Desktop 대신 쓰는 경량 CLI 런타임(Apple Silicon·저메모리 친화). DB(Postgres)는 컨테이너로
띄우고, **앱은 IDE/CLI에서** 실행하는 "하이브리드 개발 루프"가 표준이다.

> Docker 기본 어휘(이 문서 전반에서 씀): **컨테이너**=격리된 실행 단위, **이미지**=컨테이너를 찍어내는
> 템플릿, **볼륨**=컨테이너가 죽어도 남는 데이터 저장소(여기선 DB 데이터), **docker compose**=이들을
> YAML 한 파일로 정의·기동하는 도구. (더 자세한 Docker 개념은 [SETUP.md](SETUP.md) 참고.)

---

## 3. 이 단계의 구성

```
msa/  (git 루트 = 모노레포)
├── settings.gradle.kts          ← 모듈 등록 + repositories(mavenCentral) + foojay 플러그인
├── build.gradle.kts             ← 루트: Phase 0에선 (거의) 비어 있음
├── gradle.properties            ← caching/parallel ON, configuration-cache OFF
├── gradle/
│   ├── libs.versions.toml       ← 버전 핀 단일 출처 (Boot/Cloud/springdoc…)
│   └── wrapper/                 ← gradle-wrapper.jar(커밋함) + 8.14 지정
├── gradlew / gradlew.bat        ← 래퍼 스크립트 (clone 후 바로 ./gradlew)
├── services/
│   └── order-service/           ← 첫 서비스 (헥사고날) · orderdb
│       ├── build.gradle.kts
│       └── src/main/resources/db/migration/  ← V1__init.sql / V2__…
└── deploy/compose/compose.infra.yml   ← order-db (postgres:18-alpine, :5432)


개발 루프(하이브리드):
   [Colima VM] ── docker compose ──▶ (order-db 컨테이너, :5432)
                                          ▲ jdbc
   [IntelliJ 또는 ./gradlew bootRun] ── OrderServiceApplication (:8080)
                                          │ 부팅 시 Flyway가 스키마 적용
```

---

## 4. 코드/설정 — 한 부분씩 해설

### 4.1 모듈 등록 — `settings.gradle.kts`
```kotlin
plugins {
    // Java 21 툴체인을 자동으로 내려받게 해 주는 resolver.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}
dependencyResolutionManagement {
    repositories {
        mavenCentral()          // ← 모든 모듈이 공유하는 단일 저장소 선언
    }
}
rootProject.name = "msa-platform"
include(":services:order-service")   // ← 새 서비스는 여기 한 줄로 추가
```

- `foojay-resolver-convention`이 **여기(settings)** 에 있는 이유: 툴체인 프로비저닝은 빌드 초기에
  결정돼야 하므로 settings 플러그인으로 둔다.
- `mavenCentral()`을 **여기서 중앙 선언**한다. 이게 빠지면 의존성을 못 받아 `no repositories are
  defined`로 빌드가 실패한다(§7의 실제 함정).

### 4.2 루트 빌드 — `build.gradle.kts` (의도적으로 비움)
```kotlin
// 루트 빌드 — Phase 0에서는 비워 둔다.
// 서비스가 1개뿐이라 공통 설정 중복이 아직 없다.
// 2번째 서비스가 생기는 Phase 2에서 공통 설정을 build-logic/ 컨벤션 플러그인으로 추출한다.
```
"중복이 아플 때 추출" 원칙이다. 지금 미리 컨벤션 플러그인을 만들면 과설계다.

> ℹ️ **후속 변화:** 실제로 두 번째 서비스(payment-service)는 **Phase 2**에서 추가됐지만, 공통 설정의
> `build-logic/` 추출은 이 문서 작성 시점 기준 아직 이뤄지지 않았다(각 서비스 `build.gradle.kts`가
> 여전히 개별적으로 플러그인/BOM을 선언). 계획상 예고된 리팩터로, 필요할 때 진행할 항목이다.

### 4.3 버전 핀 — `gradle/libs.versions.toml`
```toml
[versions]
springBoot   = "3.5.15"      # 3.x 마지막 라인 (학습 친화)
springDepMgmt = "1.1.7"
springCloud  = "2025.0.3"    # Northfields — Boot 3.5.x 공식 짝
springdoc    = "2.8.17"      # OpenAPI/Swagger UI — Boot 3.5 호환

[libraries]
spring-cloud-dependencies = { module = "org.springframework.cloud:spring-cloud-dependencies", version.ref = "springCloud" }
flyway-core       = { module = "org.flywaydb:flyway-core" }               # 버전은 Boot BOM이 관리
flyway-postgresql = { module = "org.flywaydb:flyway-database-postgresql" }
postgresql        = { module = "org.postgresql:postgresql" }
springdoc-openapi-ui = { module = "org.springdoc:springdoc-openapi-starter-webmvc-ui", version.ref = "springdoc" }

[plugins]
spring-boot = { id = "org.springframework.boot", version.ref = "springBoot" }
spring-dependency-management = { id = "io.spring.dependency-management", version.ref = "springDepMgmt" }
```
- **Boot와 Cloud는 짝**이다(3.5.15 ↔ 2025.0.3 = Northfields). 독립적으로 핀하면 안 된다.
  (Northfields는 Spring Cloud 릴리스에 붙는 **코드네임**일 뿐 — Spring Cloud는 릴리스마다 지명 이름을
  붙이는 관행이 있고, 우리는 버전 번호 `2025.0.3`으로 핀한다.)
- Flyway·Postgres 드라이버는 **버전을 안 적었다** — Boot BOM이 맞춰준다(§2.3).
- `springdoc`만 버전을 명시하는 건 Boot BOM 밖의 라이브러리라서.

### 4.4 서비스 빌드 — `services/order-service/build.gradle.kts`
```kotlin
plugins {
    java
    alias(libs.plugins.spring.boot)                     // 카탈로그 참조
    alias(libs.plugins.spring.dependency.management)
}
java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }  // JDK로 실행, 21로 컴파일
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
    implementation(libs.springdoc.openapi.ui)           // 웹 어댑터 문서화 전용

    // 스키마는 Flyway가 소유한다 (ddl-auto=validate). Phase 0 핵심 습관.
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
```
- Spring Cloud BOM은 이 시점에 "**버전 관리만**" 한다(실제 Cloud 스타터 의존성은 Phase 3~4부터).
- `flyway-database-postgresql`이 별도 아티팩트인 것은 Flyway 10부터 DB별 모듈이 분리됐기 때문.

### 4.5 앱 설정 — `application.yml` (스키마 소유권의 핵심)
```yaml
spring:
  application:
    name: order-service
  datasource:
    url: jdbc:postgresql://localhost:5432/orderdb
    username: order
    password: orderpw
  jpa:
    hibernate:
      ddl-auto: validate     # ← Hibernate는 만들지 않고 "검증만". 불일치 시 부팅 실패
    open-in-view: false      # ← LAZY 로딩이 뷰까지 새지 않게(§7.4 함정과 연결)
  flyway:
    enabled: true            # 기본 위치 classpath:db/migration
server:
  port: 8080
  shutdown: graceful         # 진행 중 요청을 끝내고 종료
management:
  endpoints:
    web:
      exposure:
        include: health,info,flyway   # /actuator/flyway 로 마이그레이션 이력 확인 가능
```
> `ddl-auto: validate` + `flyway.enabled: true`의 조합이 **"스키마는 코드다"** 를 강제하는 장치다.
> 앱은 절대 테이블을 만들지 않고, 엔티티가 스키마와 안 맞으면 뜨지도 않는다(빠른 실패).

- **`ddl-auto`**: 앱이 뜰 때 Hibernate가 스키마를 어떻게 다룰지 정하는 Spring 설정이다. 선택지는
  `create`(매번 새로 만듦)·`update`(차이만 반영)·`create-drop`(종료 시 삭제)·`validate`(대조만)·
  `none`(아무것도 안 함). 우리는 표류를 막으려고 `validate`를 쓴다(선택지 비교는 §6.2).
- **`open-in-view`(OSIV, Open Session In View)**: 기본값(true)은 HTTP 응답을 만드는 동안에도 DB 세션을
  열어둔다. `false`로 끄면 트랜잭션이 끝난 뒤엔 **LAZY(지연) 로딩** 연관을 못 읽는다 — 그래서 엔티티→DTO
  변환은 반드시 `@Transactional` 메서드 안에서 끝내야 한다(안 그러면 §7.4의 500 함정 발생).
- **actuator `exposure.include`**: 스프링 부트 Actuator는 앱 상태를 들여다보는 관리용 엔드포인트
  (`/actuator/*`)를 제공하는데, 보안상 기본은 대부분 숨김이다. 여기선 `health,info,flyway`만 골라 노출한다.

### 4.6 마이그레이션 — `db/migration/V1__init.sql`, `V2__…`
```sql
-- V1__init.sql : order-service가 소유하는 테이블 (database-per-service)
CREATE TABLE orders (
    id UUID PRIMARY KEY, customer_id UUID NOT NULL,
    status VARCHAR(30) NOT NULL, total_amount NUMERIC(12,2) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE order_items (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    product_id UUID NOT NULL, quantity INTEGER NOT NULL, unit_price NUMERIC(12,2) NOT NULL
);
```
- **파일명 규약:** `V<버전>__<설명>.sql`. Flyway가 순서대로 적용하고 `flyway_schema_history`에 이력을 남긴다.
- `V2__inventory_and_payment.sql`은 `stock_items`/`payments` + 데모 시드 재고를 넣는다. 이 테이블들은
  Phase 0 스캐폴드가 아니라 **Phase 1(모놀리스)** 에서 order-service가 재고·결제를 직접 소유하며 생긴 것이고,
  주석에도 "Phase 2+에서 payment-service로 이동"이라 예고돼 있다. (실제로 결제는 **Phase 2-2에서
  원격 호출로 전환**됐다.)

### 4.7 인프라 — `deploy/compose/compose.infra.yml`
```yaml
name: shopsaga-infra
services:
  order-db:
    image: postgres:18-alpine
    restart: unless-stopped
    environment: { POSTGRES_DB: orderdb, POSTGRES_USER: order, POSTGRES_PASSWORD: orderpw }
    ports: ["5432:5432"]
    volumes:
      # PostgreSQL 18+ 는 데이터를 버전별 하위 디렉터리에 저장 →
      # 마운트는 옛 /var/lib/postgresql/data 가 아니라 /var/lib/postgresql 에 한다.
      - order-db-data:/var/lib/postgresql
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U order -d orderdb"]
      ...
```
- **한 서비스 = 한 DB 컨테이너**. 이후 Phase에서 서비스마다 DB를 추가하며 호스트 포트를 5433,
  5434…로 분리한다(실제로 payment-db가 뒤 Phase에서 추가됨).
- `healthcheck`로 DB가 "정말 준비됐는지"를 판단하게 해, 의존 서비스가 성급히 붙어 실패하는 걸 막는다.

---

## 5. 동작 흐름 (부팅 → 스키마 생성 → 요청)

```
1) colima start                       → 컨테이너용 리눅스 VM 기동
2) docker compose ... up -d order-db  → Postgres 18 컨테이너(healthy 대기), :5432 노출
3) ./gradlew :services:order-service:bootRun (또는 IntelliJ main 실행)
     ├─ (JDK 21 없으면) foojay resolver가 21 자동 프로비저닝 → 21로 컴파일
     ├─ Spring Boot 기동
     ├─ Flyway 실행: flyway_schema_history 확인 → 미적용 V1,V2 SQL을 순서대로 적용
     └─ Hibernate: ddl-auto=validate → 엔티티 vs 실제 스키마 대조 (불일치면 부팅 실패)
4) :8080 준비 완료
5) curl -X POST :8080/orders  → 서비스가 orderdb에 INSERT → 201 + 응답 DTO(전송용 객체)
6) docker compose down -v (-v = 볼륨=DB 데이터까지 삭제) 후 재기동 → Flyway가 V1,V2를 다시 적용 → 스키마 재생성
```
6번이 **"스키마는 코드다"의 증명**이다. 볼륨(=DB 데이터 저장소)을 통째로 날려도 SQL 파일만 있으면
스키마가 똑같이 부활한다.

---

## 6. 동작 원리 더 깊게 / 트레이드오프

### 6.1 왜 Flyway를 "0단계"부터?
이 로드맵은 뒤로 갈수록 이벤트·비동기 메시징용 테이블이 여러 개 추가된다(`outbox`·`processed_messages`·
`saga_instance`·읽기모델 등 — **지금은 이름만 알아두면 되고, 각 개념은 해당 Phase 문서에서 다룬다**).
마이그레이션이 없으면 `down -v` 한 번에 전부 사라지고, "데이터를 지웠다가 이력으로 되살리는" 검증이
거짓말이 된다. 그래서 테이블이 2개뿐인 지금부터 습관을 박아둔다.

### 6.2 `ddl-auto`의 선택지와 트레이드오프
`create`/`update`는 편하지만 스키마 표류의 원흉이다(운영에서 데이터 유실·예측 불가 변경). `validate`는
"내가 아는 스키마와 실제가 같은가?"만 확인해 **표류를 컴파일 타임처럼 잡아준다**. 대가는 "SQL을
직접 써야 함"인데, 이게 곧 문서이자 이력이 되므로 학습·운영 모두에 이득이다.

### 6.3 Java 21 타깃인데 빌드는 JDK 24로 실행
(§2.4에서 본 대로) **빌드를 실행하는 JDK**와 **컴파일 타깃 JDK**는 별개다. 예컨대 개발자 머신에
JDK 24가 깔려 있어 Gradle이 24로 돌아가도(Gradle 8.14가 24 실행을 지원), 툴체인이 컴파일/테스트를
21로 고정하므로 산출물은 **항상 21**이다(재현성). 즉 "21이라며 24는 뭐지?"가 아니라, 24는 그저
빌드를 돌리는 실행기이고 결과물은 21로 못박힌다. JDK 21이 없으면 foojay가 받아온다.

### 6.4 gradle-wrapper.jar를 커밋한다
`.gitignore`가 빌드 산출물을 무시하지만 `!gradle/wrapper/gradle-wrapper.jar`로 **예외**를 둔다.
이게 있어야 `clone` 직후 아무 설치 없이 `./gradlew`가 바로 동작한다(래퍼가 8.14 배포본을 받아옴).

### 6.5 configuration-cache는 끈다
Gradle에는 성격이 다른 두 캐시가 있다: **빌드 캐시**(`org.gradle.caching` — 태스크의 *산출물*을
재사용해 다시 안 만들게 함)와 **configuration-cache**(빌드 *설정 단계* 자체의 결과를 재사용해 기동을
빠르게 함). 이 둘 중 `io.spring.dependency-management` 플러그인이 configuration-cache와 아직
비호환이라(legacy configuration 직렬화 에러) **configuration-cache만** 끈다. 빌드 캐시·`parallel`은 유지.

### 6.6 하이브리드 개발 루프
DB만 컨테이너로 띄우고 앱은 IDE에서 돌린다. 이유: 18GB 머신의 RAM 예산 절약 + 핫 리로드/디버거
편의. `colima stop`으로 안 쓸 때 VM 메모리를 통째로 회수하는 습관이 프로젝트 내내 중요하다.

---

## 7. 검증 (그 당시 실제로 확인한 것 — 실제 함정 포함)

> **전제:** 아래 명령은 저장소를 `clone`해 **코드(엔티티·컨트롤러 등)가 이미 있는 상태**를 가정한다.
> 즉 여기서 새로 타이핑하는 건 없고, 있는 코드를 빌드·기동·호출해 "진짜 동작하는지"만 확인한다.
> 처음부터 셋업하는 절차(colima 설치 등)는 [SETUP.md](SETUP.md)를 따르라.

**빌드 & 단위 테스트 (Docker 불필요)**
```bash
./gradlew :services:order-service:test        # BUILD SUCCESSFUL + OrderTest 통과
```
**DB 기동 & 앱 실행**
```bash
docker compose -f deploy/compose/compose.infra.yml up -d      # order-db (healthy)
./gradlew :services:order-service:bootRun                     # :8080
curl -s localhost:8080/actuator/health                        # {"status":"UP"}
```
**API "동작 증명"**
```bash
curl -s -X POST localhost:8080/orders -H 'Content-Type: application/json' -d '{
  "customerId":"11111111-1111-1111-1111-111111111111",
  "items":[{"productId":"22222222-2222-2222-2222-222222222222","quantity":2,"unitPrice":10.00}]}'
# → 201 + {id, status, totalAmount, items[...]}
curl -s localhost:8080/orders/<UUID>                          # 200 단건
curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/orders/000...000   # 404
curl -s localhost:8080/actuator/flyway | python3 -m json.tool # V1,V2 적용 이력
```
**스키마 재현성** — `down -v` 후 재기동 → Flyway가 V1 재적용해 스키마 재생성.

**그때 실제로 겪은 함정 4가지(→ 상세는 [SETUP.md](SETUP.md) §8):**

| 증상 | 원인 | 해결 |
|---|---|---|
| `no repositories are defined` | 저장소 미선언 | `settings.gradle.kts`에 `mavenCentral()` 중앙 선언 (§4.1) |
| `Configuration cache state could not be cached … DefaultLegacyConfiguration` | dependency-management 플러그인이 config-cache와 비호환 | `gradle.properties`에서 configuration-cache만 끔 (§6.5) |
| postgres:18 컨테이너가 시작 직후 Exit(1) | PG18의 데이터 디렉터리 경로 변경(`/var/lib/postgresql/<버전>/docker`), 옛 `/data` 마운트 거부 | 볼륨을 `/var/lib/postgresql`(상위)에 마운트 (§4.7) |
| `GET /orders`가 500 (`LazyInitializationException`) | `open-in-view:false`라 트랜잭션 종료 후 LAZY 직렬화 실패 | 엔티티→DTO 변환을 `@Transactional` 서비스 메서드 안에서 수행 |

> **아웃바운드 어댑터 누락 사건(커밋 `3c76c63`).** 최초 커밋 직후, `.gitignore`의 `out/` 패턴(앵커 없음)이
> 헥사고날 `adapter/out`·`application/port/out` 디렉터리까지 무시해 영속 어댑터·아웃바운드 포트 13개가
> 커밋에서 빠졌다. `out/` → **`/out/`**(루트 앵커)로 한정해 해결. IDE 산출물 디렉터리(`/out/`)만 무시하도록
> 좁힌 것.

---

## 8. 알려진 한계 → 해결 Phase

| 한계 / 트레이드오프 | 성격 | 해결 Phase |
|---|---|---|
| 서비스가 1개뿐 — 공통 빌드 설정 `build-logic/` 미추출(각 모듈이 개별 선언) | 빌드 중복 | **Phase 2**(계획상 컨벤션 플러그인 추출) |
| 재고 차감에 락 없음 → 동시 주문 시 oversell 가능 | 동시성 | **db42211**(비관적 락 리팩터) |
| 결제가 order-service 안(모놀리스) — 단일 트랜잭션 ACID | 경계 | **Phase 2-1/2-2**(payment-service 분리·원격 호출) |
| DB 비밀번호가 설정에 평문 하드코딩 | 시크릿 | **Phase 6**(Config) / **Phase 7**(compose) |
| 서비스 주소가 하드코딩(단일 서비스라 아직 문제 안 됨) | 위치 결합 | **Phase 4**(Eureka 디스커버리) |
| 인증/인가 없음(누구나 호출) | 보안 | **Phase 5**(JWT 리소스 서버) |
| `eureka.instance.hostname=localhost` 등 컨테이너 미대응 값 없음(아직 순수 로컬) | 배포 | **Phase 7**(full compose) |
| 분산추적 없음 | 관측성 | **Phase 8**(LGTM) |

---

## 9. 용어 사전

- **모노레포**: 여러 서비스를 한 git 저장소에서 관리.
- **Gradle 멀티모듈**: 루트 아래 서브프로젝트(`:services:order-service`)를 매다는 구조.
- **버전 카탈로그**: `libs.versions.toml` — 버전 핀의 단일 진실 공급원.
- **BOM**: 서로 호환되는 라이브러리 버전 묶음표(Boot/Cloud가 제공).
- **툴체인**: 컴파일/테스트 대상 JDK 버전 고정(여기선 21).
- **foojay resolver**: 없는 JDK를 자동으로 내려받는 Gradle 플러그인.
- **Flyway**: 버전 매겨진 SQL로 DB 스키마를 관리하는 마이그레이션 도구.
- **JPA / Hibernate**: JPA는 자바의 ORM 표준 **명세**(인터페이스), Hibernate는 그 명세의 **대표 구현체**(실제 라이브러리).
- **엔티티(entity)**: DB 테이블에 매핑되는 자바 객체. `validate`가 대조하는 "코드 쪽" 대상.
- **DTO(Data Transfer Object)**: 계층/네트워크 사이로 데이터를 실어 나르는 전송용 객체(응답 DTO 등). 엔티티와 분리한다.
- **`ddl-auto`**: 앱 기동 시 Hibernate의 스키마 처리 방식 설정 — `create`/`update`/`create-drop`/`validate`/`none`.
- **`ddl-auto=validate`**: Hibernate가 스키마를 만들지 않고 일치 여부만 검증(불일치 시 부팅 실패).
- **LAZY(지연) 로딩**: 연관 데이터를 실제로 쓸 때까지 DB에서 안 읽어오는 JPA 전략(반대는 EAGER).
- **open-in-view(OSIV)**: HTTP 응답 생성 동안 DB 세션을 열어두는 옵션. `false`면 트랜잭션 밖에서 LAZY 연관을 못 읽는다.
- **Actuator**: 스프링 부트가 제공하는 앱 상태 확인용 관리 엔드포인트(`/actuator/*`, 노출은 `exposure.include`로 선택).
- **database-per-service**: 각 서비스가 자기 DB만 소유하는 MSA 원칙.
- **컨테이너 / 이미지 / 볼륨 / docker compose**: 격리 실행 단위 / 그 템플릿 / 죽어도 남는 데이터 저장소 / 이들을 YAML로 정의·기동하는 도구.
- **Colima**: 경량 CLI 컨테이너 런타임(Docker Desktop 대체).
- **빌드 캐시 vs configuration-cache**: 전자는 태스크 *산출물* 재사용, 후자는 빌드 *설정 단계* 결과 재사용. Phase 0에선 후자만 끈다.
- **graceful shutdown**: 진행 중 요청을 끝내고 종료.
- **wrapper**: `./gradlew` — 지정된 Gradle 버전을 자동으로 받아 실행하는 스크립트.

---

## 10. 더 알아보기 (공식 문서)

- Gradle 멀티프로젝트 빌드: https://docs.gradle.org/current/userguide/multi_project_builds.html
- Gradle 버전 카탈로그: https://docs.gradle.org/current/userguide/version_catalogs.html
- Gradle 툴체인 / foojay: https://docs.gradle.org/current/userguide/toolchains.html
- Spring Boot with Flyway: https://docs.spring.io/spring-boot/reference/data/sql.html#data.sql.migration
- Flyway 문서: https://documentation.red-gate.com/flyway
- Spring Cloud 2025.0 릴리스 노트: https://github.com/spring-cloud/spring-cloud-release/wiki

---

*관련 문서: [HEXAGONAL.md](HEXAGONAL.md)(서비스 내부 아키텍처), [SETUP.md](SETUP.md)(설치·실행·트러블슈팅), 이후 [SERVICE-DISCOVERY.md](SERVICE-DISCOVERY.md)(Phase 4), [SECURITY.md](SECURITY.md)(Phase 5). 전체 로드맵: 루트 `MSA-LEARNING-PLAN.md`.*
