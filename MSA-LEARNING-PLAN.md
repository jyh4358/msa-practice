# MSA 핸즈온 학습 로드맵 — "ShopSaga"

> Java + Spring Cloud로 마이크로서비스 아키텍처를 **직접 만들어 보며** 배우는 장기 학습 플랫폼.
> 로컬 Docker Compose에서 시작해 로컬 Kubernetes + CI/CD까지.
> 대상 환경: macOS / **Apple Silicon (arm64)** / 18GB RAM / Java 24·Node 24 설치됨(이 계획은 **Java 21 LTS** 타깃 — §1·§2) / Docker 미설치.

이 문서는 6개 전문가 관점(도메인 분해 · 기술스택 · 학습순서 · Saga · 관측성/복원력 · 인프라)으로 병렬 설계한 뒤,
버전 호환성(웹 검증)·완성도·순서 타당성을 교차 검증하고 **하나의 모순 없는 로드맵**으로 합친 결과입니다.

---

## 0. 확정된 결정 (한눈에)

| 항목 | 결정 |
|---|---|
| 언어/프레임워크 | Java 21 LTS + Spring Boot 3.5.x + Spring Cloud 2025.0.x (Northfields) — 학습 친화(튜토리얼 풍부) 선택 |
| 빌드 | Gradle 멀티모듈 **모노레포** (`gradle/libs.versions.toml` 단일 버전 카탈로그) |
| 도메인 | 전자상거래 주문 플랫폼 **"ShopSaga"** (Saga·CQRS가 자연스럽게 나오는 도메인) |
| 코어 빌드 경로 | **3개 서비스**(order, payment, inventory) + 인프라로 시작 → 점진적으로 **6개 서비스 목표**로 성장 |
| 메시지 브로커 | **Apache Kafka** (공식 `apache/kafka` 이미지, KRaft 모드, ZooKeeper 없음) |
| DB | **서비스당 1 DB** (`postgres:18-alpine`), CQRS 읽기 모델만 `mongo:8` |
| 실행 | 개발 = 인프라만 compose + 앱은 IntelliJ에서 / 통합 = full compose → 이후 **kind** (로컬 k8s) |
| 관측성 | Grafana **LGTM** 스택 (Loki·Grafana·Tempo·Prometheus) + Micrometer Tracing + OTel |
| 복원력 | Resilience4j (Circuit Breaker · Retry · Bulkhead · RateLimiter · TimeLimiter) |
| 총 공수 | **현실적으로 약 18~22 풀데이** (저녁·주말 기준 6~10주). 아래 "공수" 절 참고 |

> **핵심 교육 원칙 4가지** (이 로드맵의 뼈대):
> 1. **동작하는 모놀리스를 먼저** 만들고, 그걸 일부러 쪼개며 "분산의 비용"을 몸으로 느낀다.
> 2. **관측성을 Saga보다 먼저** 세운다 — 분산 흐름이 안 보이면 디버깅이 지옥이다.
> 3. 매 Phase마다 **"일부러 고장 내기"** 단계가 있다. 장애 처리는 사고로 배우는 게 아니라 실험으로 배운다.
> 4. **신뢰성 척추(outbox + 멱등성)를 Saga/CQRS보다 먼저** 깐다 — 이게 없으면 Saga 보상도 CQRS 투영도 전부 틀린다.

---

## 1. 사전 준비 (Phase 0 이전에 반드시)

현재 머신엔 **Docker가 없습니다.** Compose 기반 Phase는 컨테이너 런타임 없이는 한 발도 못 나갑니다.

Apple Silicon에서 둘 중 하나 (학습용은 **Colima 추천** — 무료·경량·CLI):

```bash
# 추천: Colima (CLI 전용, arm64 네이티브, 끄면 RAM 즉시 회수)
brew install colima docker docker-compose
colima start --arch aarch64 --cpu 4 --memory 8 --disk 60

# Java 21 LTS — 이 계획의 타깃 (현재 24 설치됨; Gradle 툴체인 자동 프로비저닝에 맡겨도 됨)
brew install --cask temurin@21          # 또는: sdk install java 21-tem  (SDKMAN)

# k8s 단계용 도구 (지금 또는 나중에)
brew install kubectl kind helm k9s

# 검증 — 반드시 aarch64가 찍혀야 함
docker run --rm --platform linux/arm64 alpine uname -m   # -> aarch64
```

> Docker Desktop을 써도 됩니다(GUI 필요 시). 단 18GB 머신에선 Colima가 가볍습니다.
> **`colima stop`** 으로 안 쓸 때 8GB를 통째로 회수하세요 — 18GB의 RAM 예산 관리가 이 프로젝트 내내 중요합니다.

---

## 2. 버전 표 (단일 진실 공급원 — 어디서든 이걸 쓴다)

> 2026-06-20 기준 **웹으로 호환성 검증 완료.** Boot 3.5.x ↔ Spring Cloud 2025.0.x(Northfields)는 공식 호환 쌍입니다. (안정화된 최신은 Boot 4.1/Oakwood지만, **튜토리얼·SO 답변 대다수가 Boot 3.x** 기준이라 학습 마찰을 줄이려 3.5를 선택. Boot 4 이전은 캡스톤 Phase 18.)
> ⚠️ 버전을 독립적으로 핀하지 마세요. Boot와 Spring Cloud를 짝이 안 맞게 섞으면 클래스패스 오류가 납니다.

| 영역 | 선택 | 핀 버전 | arm64 | 비고 |
|---|---|---|---|---|
| 언어/툴체인 | Java (Temurin) | **21 LTS** (Gradle toolchain) | ✅ | 빌드는 설치된 JDK 24로 **실행**, 컴파일 타깃 21. 툴체인 자동 프로비저닝 가능 |
| 빌드 | Gradle (wrapper) | **8.14.x** | ✅ | `./gradlew` 사용 |
| 프레임워크 | Spring Boot | **3.5.15** | ✅ | 3.x 마지막 라인(Java 17~25 지원). 튜토리얼 가장 풍부 |
| 클라우드 BOM | Spring Cloud | **2025.0.3** (Northfields) | ✅ | Boot 3.5.x 공식 짝 |
| API Gateway | `spring-cloud-starter-gateway-server-webflux` | (BOM) | ✅ | ⚠️ 2025.0에서 **이름 변경**(옛 `-gateway` deprecated). prefix `spring.cloud.gateway.server.webflux.*` |
| 디스커버리 | `spring-cloud-starter-netflix-eureka-server`/`-client` | (BOM) | ✅ | 대시보드 :8761 |
| 로드밸런싱 | `spring-cloud-starter-loadbalancer` | (BOM) | ✅ | Eureka와 클라이언트 LB |
| 설정 | `spring-cloud-config-server`/`-starter-config` | (BOM) | ✅ | Git 백엔드 |
| REST 클라이언트 | `spring-cloud-starter-openfeign` (+ 비-LB엔 `RestClient`) | (BOM) | ✅ | Feign 주력 |
| 브로커 | **`apache/kafka`** (KRaft, ZooKeeper 없음) | **`apache/kafka:3.9.x`** | ✅ | ❌ confluentinc/cp-kafka (arm64 마찰). spring-kafka(3.5 BOM)와 세대 일치 |
| Kafka 클라이언트 | `spring-kafka` | (Boot BOM) | ✅ | 먼저 plain으로, Cloud Stream은 나중에 |
| 쓰기 DB | postgres | **`postgres:18-alpine`** | ✅ | **서비스당 1개** 컨테이너·볼륨·계정 |
| 읽기 DB(CQRS) | mongo | **`mongo:8`** | ✅ | 쿼리 측 전용 |
| DB 마이그레이션 | Flyway | (Boot BOM) | ✅ | 서비스마다 `db/migration/V1__init.sql` |
| 복원력 | `spring-cloud-starter-circuitbreaker-resilience4j` | (BOM) | ✅ | CB/Retry/Bulkhead/RateLimiter/TimeLimiter |
| 메트릭 | `micrometer-registry-prometheus` | (Boot BOM) | ✅ | `/actuator/prometheus` |
| 트레이싱 | `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp` | (Boot BOM) | ✅ | OTLP → Tempo. **Java agent와 동시 사용 금지** |
| 관측성 백엔드 | `grafana/otel-lgtm` (올인원) → 이후 분리 | latest | ✅ | Tempo+Loki+Prometheus+Grafana 한 컨테이너 |
| 보안(엣지) | `spring-boot-starter-oauth2-resource-server` + Keycloak/Spring Authorization Server | (BOM) | ✅ | JWT 검증을 게이트웨이에서 |
| 로컬 k8s | **kind** + kubectl | latest, `kindest/node:v1.31.x` | ✅ | Colima 엔진 재사용 |
| 런타임 베이스 이미지 | `eclipse-temurin:21-jre` | — | ✅ | 툴체인과 일치(21) |

**Java 결정 (한 번만):** **Java 21 LTS**를 타깃. 빌드는 설치된 **JDK 24로 실행**, Gradle 툴체인이 컴파일/테스트를 **21**로 고정(재현성·튜토리얼 일치). 런타임 이미지·`BP_JVM_VERSION` 모두 **21**. JDK 21은 직접 설치(`brew install --cask temurin@21`)하거나 **Gradle 툴체인 자동 프로비저닝**(foojay resolver)에 맡기면 됩니다.

```kotlin
// 각 서비스 build.gradle.kts
plugins {
  java
  id("org.springframework.boot") version "3.5.15"
  id("io.spring.dependency-management") version "1.1.7"
}
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }  // JDK24로 실행, 21로 컴파일
dependencyManagement {
  imports { mavenBom("org.springframework.cloud:spring-cloud-dependencies:2025.0.3") }
}
```

> **버전 선택 메모:** 학습 마찰을 줄이려 **Boot 3.5 라인**을 선택했습니다(튜토리얼·SO 답변 대다수가 Boot 3.x 기준이라 1:1로 따라 하기 쉬움). Boot 3.5 / Spring Cloud 2025.0의 OSS 지원은 2026-06-30경 종료되지만 **학습용으론 무방**(API 안정). 안정화된 최신인 **Boot 4.1 + Spring Cloud 2025.1(Oakwood)** 이전은 Jackson 3(`com.fasterxml.jackson`→`tools.jackson`)·Jakarta EE 11·JSpecify·Spring Framework 7을 동반하며 **캡스톤(Phase 18)**에서 실습합니다.

---

## 3. 목표 아키텍처 — ShopSaga

도메인은 전자상거래 주문 플랫폼. **주문 → 재고 예약 → 결제 → 배송 확정** 흐름이 그 자체로 다단계 분산 트랜잭션(=Saga + 보상)이라 학습에 이상적입니다.

### 서비스 구성 (목표 6 + 인프라 2 + 읽기모델 1)

| # | 서비스 | 책임 | 코어 경로? |
|---|---|---|---|
| 1 | `order-service` | 주문 애그리거트·주문 생명주기·Saga 오케스트레이터 거처 | ✅ 처음부터 |
| 2 | `inventory-service` | 재고 수량·예약 | ✅ Phase 9에서 분리 |
| 3 | `payment-service` | 결제 승인·캡처·환불 (일부러 실패시킬 수 있는 가짜 PG) | ✅ Phase 2에서 분리 |
| 4 | `shipping-service` | 배송 (Saga 마지막·보상 없는 단계 — "비가역 단계는 맨 뒤로"의 교보재) | ⬜ 확장 |
| 5 | `catalog-service` | 상품 마스터·가격 (주문 시 동기 검증 대상) | ⬜ 확장 |
| 6 | `customer-service` | 고객 프로필·주소 (+ 신원/인증 관심사) | ⬜ 확장 |
| I1 | `api-gateway` | Spring Cloud Gateway — 단일 진입점·라우팅·레이트리밋·JWT | ✅ |
| I2 | `discovery-server` | Eureka — 서비스 레지스트리 (k8s 단계에서 **삭제**) | ✅ |
| Q | `order-query-service` | CQRS "내 주문" 읽기 투영 (Mongo) | Phase 11에서 등장 |

> **3서비스 코어 vs 6서비스 목표 (중요):** Phase 0~14는 **order·payment·inventory 3개**로만 진행합니다. 이게 18GB 노트북에서 현실적이고, 매 Phase가 단독 실행 가능하도록 만드는 길입니다. shipping·catalog·customer는 패턴을 다 익힌 뒤 **확장 과제**로 추가하세요(Saga에 참여자를 늘리는 좋은 연습). 6서비스를 처음부터 다 지으면 RAM 예산이 무너지고 공수가 두 배가 됩니다.

### 정규 Saga 흐름 + 보상 맵

```
        POST /api/orders
              v
[order-service] 주문 생성(PENDING)
        | (1) ReserveStock
        v
[inventory-service] --실패?--> StockReservationFailed --> 주문 CANCELLED (보상 짧음: 결제 안 함)
        | ok
        | (2) CapturePayment
        v
[payment-service] --실패?--> PaymentDeclined --> 보상: 재고 해제 + 주문 CANCELLED
        | ok
        | (3) CreateShipment
        v
[shipping-service] (종단·보상 없음)
        v
[order-service] CONFIRMED
```

| 정방향 단계 | 소유 서비스 | 보상 액션 |
|---|---|---|
| 재고 예약 | inventory | 예약 해제 (`DELETE /reservations/{orderId}`) |
| 결제 캡처 | payment | 환불 (`POST /payments/{orderId}/refund`) |
| 배송 생성 | shipping | (거의 도달 안 함; 도달 시 배송 취소) |
| — | order | 주문 → `CANCELLED`, `OrderCancelled` 발행 |

### 컨텍스트 맵

```
                           ┌─────────────────┐
        client ───────────▶│   api-gateway   │ (라우팅·레이트리밋·JWT 검증)
                           └────────┬────────┘
                                    │ lb:// (Eureka로 해소)
        ┌───────────────────────────┼───────────────────────────┐
        ▼                           ▼                             ▼
┌──────────────┐          ┌──────────────┐               ┌──────────────┐
│order-service │          │catalog-service│ (확장)        │customer-service│ (확장)
│  (orderdb)   │          │  (catalogdb) │               │  (customerdb) │
└──────┬───────┘          └──────▲───────┘               └──────▲───────┘
       │ SYNC: 주문 시 가격/고객 검증 ───────────────────────────┘
       │ SAGA (오케스트레이션=커맨드 / 코레오그래피=이벤트)
       ▼
┌──────────────┐  ┌──────────────┐  ┌──────────────┐
│inventory-svc │  │ payment-svc  │  │ shipping-svc │ (확장)
│(inventorydb) │  │ (paymentdb)  │  │ (shippingdb) │
└──────┬───────┘  └──────┬───────┘  └──────┬───────┘
       └──────── ASYNC events on Kafka ─────┘
                         ▼
              ┌────────────────────┐
              │ order-query-service│ (CQRS 읽기모델, Mongo) ◀── 이벤트 구독
              └────────────────────┘
discovery-server (Eureka) ◀── 모든 서비스 등록 (k8s 단계에서 삭제)

──▶ 동기 REST     ···▶ 비동기 이벤트(Kafka)
:common 모듈 = 이벤트/DTO **계약 전용** (엔티티·리포지토리 절대 공유 금지)
```

---

## 4. 모노레포 레이아웃

```
msa-platform/
├── settings.gradle.kts            # 모든 모듈 include
├── build.gradle.kts               # 루트: 공통 설정
├── gradle/libs.versions.toml      # 버전 카탈로그 — 핀의 단일 출처
├── gradlew, gradle/wrapper/        # Gradle 8.14 wrapper
├── build-logic/ (또는 buildSrc/)   # 컨벤션 플러그인(Boot+Cloud BOM, Java21 toolchain)
├── infra/
│   ├── discovery-server/          # Eureka
│   ├── config-server/             # Spring Cloud Config
│   └── api-gateway/               # Spring Cloud Gateway
├── services/
│   ├── order-service/             # orderdb (Saga 오케스트레이터 거처)
│   ├── payment-service/           # paymentdb
│   ├── inventory-service/         # inventorydb
│   └── order-query-service/       # mongo (CQRS 읽기측)
├── shared/
│   └── events/                    # 이벤트/DTO 계약 전용 (아껴서!)
└── deploy/
    ├── compose/                   # compose.infra.yml / compose.apps.yml / .env / Dockerfile
    ├── observability/             # otel-collector·tempo·loki·prometheus·grafana 설정
    └── k8s/                       # Phase 16: manifests → kustomize/helm
```

> `shared/events`는 **이벤트 스키마/DTO POJO만** 담습니다. JPA `@Entity`나 리포지토리를 넣는 순간 "분산 모놀리스"가 되어 db-per-service 원칙이 정신적으로 무너집니다.

---

## 5. 단일 정규 Phase 척추

각 Phase는 **단독 실행 가능**하고 **검증 가능**(끝에 "동작 증명" 단계)합니다. 공수는 코어 3서비스 기준 추정치이며, 처음 접하는 기술은 +30~50% 버퍼를 두세요.

### 🟢 파트 A — 동기 플랫폼 (Phase 0~7)

#### Phase 0 — 사전 준비 + 첫 서비스 + 마이그레이션 습관 ⏱️ ~1.5d
- **목표:** 개발 환경 + 부팅되는 Spring Boot 서비스 1개, **첫 줄부터 Flyway**.
- **빌드:** ① Colima 설치(§1) ② Gradle 멀티모듈 모노레포 스캐폴드(버전 카탈로그·컨벤션 플러그인·Java21 툴체인) ③ `order-service` = REST `GET/POST /orders` + `postgres:18-alpine`(지금은 `docker run`으로 단독) ④ **Flyway** `src/main/resources/db/migration/V1__init.sql`로 `orders`/`order_items` 생성.
- **새 개념:** 프로젝트 스켈레톤, 버전 관리, "서비스 = 자기 DB와 대화하는 부팅 가능한 jar", **스키마 마이그레이션은 코드다**(`ddl-auto` 금지).
- **검증:** `./gradlew :order-service:bootRun` → `curl -X POST localhost:8080/orders` → Postgres에 row. `docker compose down -v` 후에도 Flyway가 스키마를 **재생성**.

> 💡 **왜 Flyway를 0단계에?** db-per-service가 핵심 규칙인데, 나중에 outbox·processed_messages·saga_instance·읽기모델 테이블이 줄줄이 추가됩니다. 마이그레이션 없으면 `down -v` 한 번에 다 날아가고 CQRS "삭제 후 재생성·리플레이" 검증이 거짓말이 됩니다.

#### Phase 1 — 모놀리스 (쪼갤 대상을 먼저 만든다) ⏱️ ~0.5d
- **목표:** order+payment+inventory 로직을 **한 서비스·한 DB**에 (의도적 모놀리스).
- **빌드:** 한 `@Transactional` 경계 안에서 "주문 처리"가 결제+재고를 in-process 호출.
- **새 개념:** 단일 트랜잭션 ACID의 단순함 — **앞으로 잃게 될 기준선**.
- **검증:** `POST /orders/place`가 재고 차감+결제를 원자적으로. 커밋 직전 예외 던져 **아무것도 저장 안 됨**(진짜 롤백) 확인. **이 느낌을 기억** — Phase 12가 이걸 가져갑니다.

#### Phase 2 — 2번째 서비스 분리 + 동기 REST (네트워크를 느껴라) ⏱️ ~1d
- **목표:** `payment-service`를 자기 모듈·자기 DB·자기 프로세스로 추출, HTTP 호출.
- **빌드:** Spring 6 선언적 `RestClient`/HTTP Interface(`@HttpExchange`). 일단 URL 하드코딩(`http://localhost:8081`). **여기서 트레이싱 계측을 켠다**(Phase 8에서 백엔드를 세울 뿐, 계측은 지금부터).
- **새 개념:** 프로세스·데이터 소유 경계, 원격 호출 지연/실패가 메서드 호출을 대체, **단일 트랜잭션 소멸**(이제 DB 2개).
- **검증:** 둘 다 띄우고 end-to-end 성공. **일부러 고장:** payment-service 중단 → order-service의 커넥션 에러를 적어두기(Phase 14 복원력의 동기).

#### Phase 3 — API Gateway ⏱️ ~0.5d
- **목표:** 두 서비스 앞 단일 진입점.
- **빌드:** `spring-cloud-starter-gateway-server-webflux`. `/api/orders/**` → order, `/api/payments/**` → payment.
- **검증:** 모든 호출이 게이트웨이(:8080) 통과. 백엔드 포트가 달라도 동작.

#### Phase 4 — 서비스 디스커버리 (하드코딩 URL 제거) ⏱️ ~1d
- **목표:** 서비스가 **이름**으로 서로를 찾는다.
- **빌드:** Eureka Server. order/payment/gateway를 클라이언트로 등록. 라우트 `uri: lb://payment-service`, 클라이언트는 `@LoadBalanced`.
- **검증:** Eureka 대시보드(:8761) 인스턴스 UP. payment-service 2번째 인스턴스 띄워 **라운드로빈** 확인(포트 로깅).

#### Phase 5 — 엣지 보안 (JWT) ⏱️ ~1.5d  〔검증에서 high로 추가됨〕
- **목표:** 공개 엔드포인트가 더 늘기 전에 신원을 엣지에 확립.
- **빌드:** 로컬 **Keycloak**(또는 Spring Authorization Server) 컨테이너. 게이트웨이를 OAuth2 resource server로 → JWT 검증. 다운스트림에 신원 전파(bearer 토큰 포워딩 또는 서명된 내부 헤더). 관리자 엔드포인트(`POST /api/products`)에 메서드 레벨 인가. 서비스 간 신뢰(mTLS vs 공유 시크릿 vs 토큰 전파) 토론.
- **새 개념:** 엣지 인증, 신원 전파, 서비스 간 인가.
- **검증:** 토큰 없이 `POST /api/orders` → 401. 유효 토큰 → 통과하고 다운스트림이 사용자 식별.

> 보안을 빼고 싶다면 **명시적으로 "범위 외"라고 선언**하세요. customer-service를 "신원 서비스"라 부르면서 아무도 인증 안 하면 모순입니다. 게이트웨이 직후가 보안의 자연스러운 자리입니다.

#### Phase 6 — 중앙 설정 (Config Server) ⏱️ ~0.5d
- **목표:** 설정을 jar 밖으로.
- **빌드:** `config-server`(Git 백엔드 `*.yml`). 클라이언트는 `spring.config.import=optional:configserver:http://localhost:8888` (⚠️ Boot 2.4+는 옛 `bootstrap.yml`이 기본 비활성 — 이걸 안 쓰면 **조용히 무시됨**). `@RefreshScope` + `POST /actuator/refresh`.
- **새 개념:** 관리·버전관리되는 중앙 설정, 재시작 없는 리프레시.
- **검증:** Git의 속성 변경 → `/actuator/refresh` → 재시작 없이 새 값.

> Spring Cloud **Bus**(Kafka로 전 인스턴스 broadcast 리프레시)는 Kafka가 없으니 **Phase 15로 미룹니다**. 또한 "k8s 단계(16)에서 이걸 ConfigMap으로 대체한다"는 점을 지금 메모해두세요.

#### Phase 7 — 동기 플랫폼 전체를 Docker Compose로 (통합 체크포인트) ⏱️ ~1d
- **목표:** 한 명령으로 동기 시스템 전체 기동.
- **빌드:** 서비스별 멀티스테이지 `Dockerfile`(레이어드 jar, `eclipse-temurin:21-jre`). `compose.infra.yml`(DB들·Keycloak) + `compose.apps.yml`(discovery·config·gateway·order·payment). **healthcheck + `depends_on: condition: service_healthy`** (`sleep` 금지). 서비스에 `server.shutdown=graceful` 추가.
- **새 개념:** 재현 가능한 멀티서비스 런타임, 컨테이너 네트워킹(서비스명으로 통신), 이미지 빌드, **graceful shutdown**.
- **검증:** 깨끗한 상태에서 `docker compose up` → 게이트웨이 통한 해피패스. `down && up` 반복 가능.

### 🔵 파트 B — 관측성 먼저, 그 다음 비동기 (Phase 8~11)

#### Phase 8 — 관측성 스택 (앞으로 당김 — 이 로드맵의 등뼈) ⏱️ ~2d
- **목표:** 더 어려운 흐름을 만들기 **전에** 모든 분산 흐름을 본다.
- **빌드:** ① **8a:** `grafana/otel-lgtm` 올인원 컨테이너로 10분 만에 트레이스 한 개 보기(동기 부여) ② **8b:** Collector·Tempo·Loki·Promtail·Prometheus·Grafana로 분리(각 조각을 학습 단위로). Phase 2부터 넣어둔 `micrometer-tracing-bridge-otel`+OTLP가 여기서 빛을 발함. 구조화 JSON 로그(traceId 포함) → Loki. Grafana 대시보드(RED + 리소스).
- **새 개념:** 3대 기둥(트레이스·로그·메트릭), **HTTP와 Kafka를 가로지르는 trace context 전파**, 단일 요청 상관관계, OTel Collector(가장 이식성 높은 프로덕션 개념).
- **검증:** 주문 1건 → Tempo에서 gateway→order→payment를 잇는 **하나의 트레이스**. 스팬 클릭 → 같은 traceId의 Loki 로그로 점프.

> ⚠️ **2대 함정**(안 지키면 트레이스가 조용히 끊김): (1) HTTP 클라이언트는 **주입받은 auto-config 빌더**로 만들 것(`new RestTemplate()`/`RestClient.create()`는 계측 안 됨). (2) Kafka는 `spring.kafka.template.observation-enabled=true` + `...listener.observation-enabled=true` 필수.

#### Phase 9 — 비동기 이벤트 with Kafka ⏱️ ~1.5d (9a + 9b로 분리)
- **9a — Kafka 기동 + 이벤트 1개 end-to-end:** compose에 `apache/kafka`(KRaft, `--profile async`). `inventory-service`를 자기 모듈로 승격. order가 `OrderPlaced` 발행(plain spring-kafka·JSON), inventory가 소비해 재고 예약. `provectuslabs/kafka-ui`로 토픽 관찰. **토픽은 `KafkaAdmin`+`NewTopic`으로 명시 생성, 단일 노드라 `replication-factor=1`**(흔한 3-replica 튜토리얼 따라하면 토픽 생성 실패), auto-create 비활성화.
- **새 개념:** fire-and-forget, 결과적 일관성, 시간적 디커플링, **재생 가능한 로그**, consumer group/offset/partition.
- **검증:** 주문 → 잠시 뒤 재고 예약 로그+DB row. **일부러 고장:** inventory 중단 → 주문 3건 → 재시작 → 보관된 Kafka 메시지 **리플레이로 따라잡기**(내구성·디커플링 증명).
- **9b는 Phase 10로 독립** (아래) — "한 Phase 한 개념" 유지.

#### Phase 10 — 신뢰성 척추: 트랜잭셔널 Outbox + 멱등 소비자 ⏱️ ~1d  〔CQRS/Saga보다 **먼저** — 가장 중요한 삽입〕
- **목표:** "이중 쓰기(dual-write)" 버그를 만들어 보고, 고친다.
- **빌드:** ① 먼저 `orderRepo.save()` + `kafkaTemplate.send()`를 한 메서드에 넣는 **이중 쓰기**를 일부러 만들고 깨지는 걸 관찰(커밋 후 크래시 → 이벤트 유실 / 발행 후 롤백 → 유령 이벤트). ② 각 서비스 DB에 `outbox` 테이블 + 같은 트랜잭션에 business row와 outbox row를 함께 기록 → `@Scheduled` 폴링 릴레이가 발행. ③ 각 소비 서비스에 `processed_messages(message_id PK)` 멱등 테이블 — dedup 체크와 부수효과를 한 트랜잭션에 커밋.
- **새 개념:** 이중 쓰기는 원자적일 수 없다, **at-least-once 배달 + 멱등 처리 = 효과적 1회(effectively-once)**, 상관 ID(messageId/sagaId).
- **검증:** 발행 직후 앱 kill → 재시작 시 미발행 row 재전송. 같은 메시지 2번 → 부수효과 정확히 1회. `SELECT * FROM outbox WHERE published_at IS NULL`이 ~1초 내 비워짐.

> 💡 이게 없으면 **CQRS 투영도 Saga 보상도 전부 비결정적**(유실·유령·중복)입니다. 그래서 Kafka(9) 직후, CQRS(11)·Saga(12) **이전**에 깝니다.

#### Phase 11 — CQRS 읽기 모델 ⏱️ ~1.5d
- **목표:** 쓰기 모델과 분리된, 이벤트로 채워지는 읽기 모델.
- **빌드:** `order-query-service`가 `OrderPlaced`/`InventoryReserved`/`PaymentCharged` 등을 구독해 **비정규화** `order_view`(Mongo)를 유지. `GET /api/orders/views?customerId=`. (읽기 측은 outbox→Kafka를 그대로 소비하므로 Phase 10 위에 안전.)
- **새 개념:** 커맨드/쿼리 분리, 이벤트 스트림 투영, 읽기/쓰기 독립 확장, **결과적 일관성을 눈으로**.
- **검증:** 쓰기 후 읽기 측이 잠깐 lag 후 수렴. **읽기 DB 삭제 후 offset 0부터 리플레이 → 동일 상태**(투영의 결정성 확인; `System.now()` 같은 숨은 비결정성 적발).

### 🟣 파트 C — Saga & 복원력 (Phase 12~14)

#### Phase 12 — Saga: 코레오그래피 + 보상 ⏱️ ~2d
- **목표:** 분산 트랜잭션 없이 여러 서비스 간 일관성을 **중앙 조정자 없이**.
- **빌드:** 순수 이벤트 흐름. `OrderPlaced`→inventory 예약→`InventoryReserved`→payment 청구→`PaymentCharged`→order CONFIRMED. 실패 경로: `PaymentDeclined` → inventory가 듣고 **보상** `InventoryReleased` → order CANCELLED. 모든 이벤트는 **Phase 10의 outbox로** 발행.
- **새 개념:** Saga 패턴, **보상(semantic undo ≠ rollback)**, 글로벌 ACID의 부재(Phase 1/2 회상).
- **⚠️ Outbox 트레이스 전파:** outbox 릴레이는 **다른 스레드·다른 시점**에 발행하므로 원 요청의 trace context가 사라집니다. **W3C `traceparent`를 outbox row에 저장**했다가 발행 시 Kafka 헤더로 재주입해야 "Saga 전체 한 트레이스"가 유지됩니다(안 하면 끊긴 트레이스 두 개를 보고 계측이 망가진 줄 오해).
- **검증:** 해피패스 → CONFIRMED. **일부러 고장:** 결제 거절(금액 `*.99` 등) → Phase 8 트레이스로 보상 흐름을 **보며** 재고 해제 + 주문 CANCELLED 확인. **멱등성 검증:** 같은 이벤트 2번 → 예약 1건.
- **하위 순서 팁:** (1) 해피패스만 → (2) 짧은 보상(품절: 결제 전이라 주문 취소만) → (3) 긴 보상(결제 거절: 재고 해제 체인). 짧은 걸 먼저.

#### Phase 13 — Saga: 오케스트레이션 ⏱️ ~2d
- **목표:** 같은 Saga를 중앙 조정자로 재구현하고 차이를 느낀다.
- **빌드:** 손으로 만든 오케스트레이터(`@Service` + `saga_instance` 상태 테이블 + reply 이벤트에 대한 switch). 상태: `STARTED→AWAITING_INVENTORY→AWAITING_PAYMENT→COMPLETED`, 보상: `COMPENSATING_INVENTORY→CANCELLED`. 서비스는 "멍청한" 커맨드 핸들러가 됨. **타임아웃 sweep**(`@Scheduled`로 정체된 saga_instance 재전송/보상)을 **크래시 복구 테스트보다 먼저** 만들 것.
- **새 개념:** 오케스트레이션 vs 코레오그래피 트레이드오프(중앙 가시성·명시적 제어 vs 조정자 결합), 언제 무엇을.
- **검증:** 동일 해피/실패 경로. 이제 `SELECT state FROM saga_instance WHERE order_id=...` **한 줄로** 어디 있는지 안다 — 그게 교훈. **크래시 복구:** `InventoryReserved` 직후 payment-service kill → 데드라인 후 재전송 또는 보상, 멈춘 saga 없음.
- **프레임워크 사이드바(읽기만):** Axon / Spring Statemachine / Temporal·Camunda — 프로덕션에선 이것들. 지금은 손으로 만들어 메커니즘을 배운다.

#### Phase 14 — 복원력 패턴 (일부러 장애 주입) ⏱️ ~2d
- **목표:** 부분 장애에서 살아남기.
- **빌드:** **Resilience4j** — 남은 동기 호출과 게이트웨이에 Circuit Breaker·Retry·Bulkhead·RateLimiter·TimeLimiter. **+ DLQ/poison 메시지 처리**(`DefaultErrorHandler` + `DeadLetterPublishingRecoverer` + 유한 백오프 → N회 후 `*.DLT`로; outbox 릴레이엔 `max_attempts` 컬럼으로 영구 실패 row 격리). 패턴 적용 위치는 §관측성-부록 표 참고. 적용 순서: `RateLimiter → TimeLimiter → CircuitBreaker → Bulkhead → Retry`.
- **새 개념:** 각 패턴이 막는 장애(breaker=죽은 의존성 그만 때리기, retry=일시 결함, bulkhead=스레드 격리, ratelimit=과부하 차단, timeout=무한 대기 방지), **DLQ=영원히 못 처리하는 메시지가 파티션 막는 것 방지**.
- **검증(패턴마다 1개 일부러 고장):**
  - Breaker: payment kill → Grafana에서 `resilience4j_circuitbreaker_state` OPEN, fast-fail → 재시작 시 HALF_OPEN→CLOSED.
  - Retry: 50% 실패 엔드포인트 → 재시도 성공.
  - Timeout: 인위적 `Thread.sleep` → 호출자가 타임아웃(`@TimeLimiter`는 `CompletableFuture` 반환 필요).
  - Bulkhead/RateLimit: `hey`/`k6`로 폭주 → 초과분 차단, 캐스케이드 없음.
  - DLQ: 역직렬화 불가 이벤트 주입 → N회 후 `.DLT`로, 파티션 안 막힘.

### 🟠 파트 D — 강화 · 플랫폼 (Phase 15~17, 캡스톤 18)

#### Phase 15 — 플랫폼 강화 ⏱️ ~2d
- **빌드:** ① **Spring Cloud Bus**(Kafka 백엔드)로 설정 broadcast 리프레시 — `spring-cloud-starter-bus-kafka` + `POST /actuator/busrefresh`(이제 Kafka가 있음). ② **계약 테스트**(Spring Cloud Contract): 프로듀서가 `OrderPlaced` 등 이벤트와 동기 API 계약 발행, 소비자가 스텁으로 검증 → ":common을 계약으로만" 강제 + 누락된 테스트 레이어 보강. ③ **이벤트 스키마 진화:** JSON + tolerant-reader("필드 추가만, 제거/개명/타입변경 금지") → 깨지는 변경을 실패로 시연 → (선택) **Apicurio** 스키마 레지스트리(arm64 네이티브, Confluent SR보다 깔끔)로 Avro/JSON Schema. 동기 API는 `/api/v1/...` 버저닝.
- **검증:** Git 속성 변경 + 한 인스턴스에 `busrefresh` → 전 인스턴스 반영. 프로듀서가 계약 깨면 소비자 빌드 실패.

#### Phase 16 — 로컬 Kubernetes로 이전 (kind) ⏱️ ~3d (16a + 16b)
> 이전 = 재작성 아님. **같은 이미지**를 k8s로. RAM이 가장 빡빡한 순간이니 **compose 인프라를 먼저 `colima stop`** 또는 인프라를 in-cluster로 — 둘 다 동시에 돌리지 말 것.
- **16a — kind + 서비스 1개:** `kind create cluster` → `kind load docker-image`(레지스트리 없이; 안 하면 `ImagePullBackOff`). order-service의 Deployment/Service/ConfigMap/Secret + **liveness/readiness probe**(`depends_on` 대체; `management.endpoint.health.probes.enabled` 필요). NodePort로 도달.
- **16b — 전체 플랫폼 on k8s:** **Eureka 삭제** → k8s Service DNS(`http://order-service:8080`)로 호출(디스커버리가 앱→플랫폼으로 이동 = 핵심 교훈). Config Server → **ConfigMap/Secret**(또는 둘 다 두고 중복 관찰). 게이트웨이는 **유지**하고 앞에 **Ingress**(ingress-nginx) 하나. `replicas` 스케일 + `kubectl delete pod`로 **자가치유** 시연. 클러스터에서 Saga end-to-end.
- **검증:** `kubectl get pods` 전부 Ready. 게이트웨이로 도달. pod 삭제 → 재생성되는 동안 Phase 14 breaker가 커버.

#### Phase 17 — CI/CD ⏱️ ~2d
- **빌드:** **GitHub Actions** — push 시 `./gradlew build test`(모노레포 한 방), 이미지 빌드. ⚠️ 러너는 amd64, 노트북 kind는 arm64 → **멀티아치 매니페스트**(buildx/QEMU `linux/amd64,linux/arm64`) 또는 학습용이면 `linux/arm64`만. GHCR push. (선택) `helm/kind-action`으로 CI 내 스모크 배포. Testcontainers 통합테스트는 러너의 Docker 데몬에 의존함을 명시.
- **검증:** 사소한 변경 push → build→test→image→(deploy)→smoke 초록불. 실행 중 이미지 태그 확인.

#### Phase 18 — 캡스톤 (선택·확장) ⏱️ 가변
- shipping·catalog·customer 서비스 추가(6서비스 목표 완성, Saga 참여자 확장) / **Boot 4.1 + Spring Cloud 2025.1(Oakwood) 이전**(Jackson 3·Jakarta EE 11·JSpecify·Spring Framework 7) / **Helm** 리팩터 + Bitnami 차트로 in-cluster 인프라 / **Debezium CDC**로 폴링 릴레이 대체 / order↔inventory **gRPC** 엣지 / Grafana Alertmanager 경보.

---

## 6. Phase 의존 순서 & 왜 이 순서인가

```
0 사전준비/Flyway ─ 1 모놀리스 ─ 2 분리/동기REST ─ 3 게이트웨이 ─ 4 디스커버리 ─ 5 보안
                                                                              │
   ┌──────────────────────────────────────────────────────────────────────────┘
   └ 6 설정 ─ 7 동기 Compose ─ 8 관측성(당김!) ─ 9 Kafka ─ 10 Outbox/멱등(척추!) ─ 11 CQRS
                                                                              │
   ┌──────────────────────────────────────────────────────────────────────────┘
   └ 12 Saga 코레오 ─ 13 Saga 오케스트레이션 ─ 14 복원력/DLQ ─ 15 강화 ─ 16 k8s ─ 17 CI/CD ─ (18 캡스톤)
```

1. **동작하는 것(모놀리스)을 먼저 만들고 부순다** — 항상 동작하는 기준점이 있고, 분산의 비용을 몸으로 느낀다.
2. **동기 플랫폼을 완성·컨테이너화(2~7)한 뒤 비동기로** — 한 통신 방식을 마스터한 뒤 어려운 걸 더한다.
3. **관측성을 Phase 8에, Saga/CQRS 디버깅 전에** — 단 하나 가장 중요한 순서 결정. 분산 버그는 기본적으로 안 보인다. X-ray를 먼저 손에 쥔다.
4. **Outbox/멱등(10)을 CQRS/Saga 전에** — 신뢰성 척추 없이는 투영도 보상도 거짓.
5. **코레오그래피 → 오케스트레이션** — 신선한 Kafka 기술 재사용 + 코레오그래피의 고통이 오케스트레이션의 가치를 설득.
6. **복원력은 Saga 다음** — Phase 2·9·12에서 이미 캐스케이드 장애를 느꼈으니 각 패턴이 "기억나는 고통의 해법"으로 안착.
7. **k8s는 같은 이미지 이전** — 재작성이 아니라 "이제 플랫폼이 디스커버리/설정/치유를 해준다".

---

## 7. 항상 지키는 교차 규칙 (Cross-Cutting Rules)

- **서비스 내부 아키텍처 = 헥사고날(Ports & Adapters):** 모든 서비스를 `domain`(순수) / `application`(포트·유스케이스) / `adapter`(web·persistence·messaging)로 구성한다. 의존성은 안쪽(도메인)을 향한다. 컨벤션·영속 함정·메시징 슬롯 규칙은 **[`docs/HEXAGONAL.md`](docs/HEXAGONAL.md)** 참고(`order-service`가 레퍼런스).
- **DB-per-service:** 서비스마다 독립 Postgres 컨테이너·볼륨·계정. 한 노트북이라 아까워도 공유 금지(공유하면 Saga 교훈 전체가 증발).
- **이중 쓰기 금지:** DB row와 Kafka 메시지를 한 메서드에서 둘 다 쓰지 않는다. 항상 **outbox**(Phase 10).
- **멱등성:** at-least-once 배달 전제. 모든 커맨드/이벤트는 `messageId`로 키잉, 핸들러는 멱등(예약 해제를 두 번 해도 no-op).
- **보상은 rollback이 아니라 semantic undo** — 보낸 확정 메일은 "취소 메일"로, DB 롤백이 아니다.
- **비가역 단계는 맨 뒤로** — 배송(shipping)을 Saga 마지막에. 결제를 배송 뒤에 캡처하면 발송된 택배를 보상할 수 없다.
- **트레이싱은 Phase 2부터 계측, 백엔드는 Phase 8** — 계측 코드는 거의 config뿐. outbox 홉엔 `traceparent` 보존(Phase 12).
- **18GB 예산:** 컨테이너 예산 ~10~12GB로 간주. **전 Phase를 동시에 띄우지 않는다.** compose `--profile`로 개념별 on/off. 개발 시 앱 JVM은 **IntelliJ에서**(Docker 아님) `-XX:MaxRAMPercentage=70`, `mem_limit: 512m`. 세션 끝나면 `colima stop`.

### 18GB RAM 운용 표

| 작업 중 | 띄우는 것 | 대략 RAM |
|---|---|---|
| Phase 1~7 동기 | Postgres 2~3 + (앱은 IDE) | 인프라 ~1.5GB |
| Phase 9~11 비동기/CQRS | + `--profile async` (Kafka + kafka-ui) + Mongo | +~1.5GB |
| Phase 12~13 Saga | 동일 + saga 로직 | +~0.5GB |
| Phase 8·14 관측성 | + `--profile obs` (Prom+Grafana+Tempo+Loki+OTel) | +~2.5GB |
| Phase 16 kind | **compose 인프라 stop** 후 kind에 인프라 in-cluster | VM 8GB 통째 |

---

## 8. 부록 A — Saga 모듈 상세 (Phase 10·12·13의 구현 참조)

### Outbox 테이블 + 릴레이 + 멱등 소비자

```sql
-- 각 서비스 DB (Flyway 마이그레이션으로)
CREATE TABLE outbox (
  id           UUID PRIMARY KEY,
  aggregate_id UUID NOT NULL,
  event_type   VARCHAR(100) NOT NULL,
  topic        VARCHAR(100) NOT NULL,
  payload      JSONB NOT NULL,
  traceparent  VARCHAR(64),              -- ★ Phase 12: 트레이스 전파용
  attempts     INT NOT NULL DEFAULT 0,   -- ★ Phase 14: poison row 격리용
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  published_at TIMESTAMPTZ               -- NULL = 미발행
);
CREATE TABLE processed_messages (
  message_id UUID PRIMARY KEY,
  consumer   VARCHAR(100) NOT NULL,
  handled_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

```java
// 비즈니스 row + outbox row를 한 트랜잭션에 (Kafka 호출 없음 → 원자적)
@Transactional
public void placeOrder(...) {
    orderRepository.save(order);
    outboxRepository.save(new OutboxRecord(order.getId(), "OrderPlaced",
        "order-events", toJson(event)));   // dual-write 아님
}

// 별도 릴레이가 발행 (at-least-once: 크래시 후 재발행 → 다운스트림 중복 → 멱등으로 흡수)
@Scheduled(fixedDelay = 500) @Transactional
public void relay() {
  outboxRepository.findUnpublished().forEach(rec -> {
      kafkaTemplate.send(rec.getTopic(), rec.getAggregateId().toString(), rec.getPayload());
      rec.setPublishedAt(Instant.now());
  });
}

// 멱등 소비자: dedup INSERT와 부수효과를 한 트랜잭션에
@KafkaListener(topics = "order-events", groupId = "inventory-service")
@Transactional
public void onOrderPlaced(OrderPlaced ev, @Header("messageId") UUID messageId) {
    if (processedRepo.existsById(messageId)) return;        // 이미 처리 → skip
    inventory.reserve(ev.orderId(), ev.lines());            // 진짜 부수효과
    processedRepo.save(new ProcessedMessage(messageId, "inventory-service"));
}
```

### 코레오그래피 이벤트 카탈로그 (sagaId가 모든 이벤트를 관통)

```
order-events:     OrderPlaced / OrderConfirmed / OrderCancelled
inventory-events: InventoryReserved / InventoryFailed / InventoryReleased(보상)
payment-events:   PaymentCharged / PaymentDeclined / PaymentRefunded(보상)
```
- inventory가 `OrderPlaced` 구독 → `InventoryReserved`|`InventoryFailed`
- payment가 `InventoryReserved` 구독 → `PaymentCharged`|`PaymentDeclined`
- order가 `PaymentCharged` 구독 → CONFIRMED
- **보상:** inventory가 `PaymentDeclined` 구독 → `release()`(멱등) → `InventoryReleased`

### 오케스트레이션 — saga_instance 상태 테이블 + switch

```sql
CREATE TABLE saga_instance (
  saga_id UUID PRIMARY KEY, order_id UUID NOT NULL,
  state VARCHAR(40) NOT NULL,   -- STARTED→AWAITING_INVENTORY→AWAITING_PAYMENT→COMPLETED / COMPENSATING_INVENTORY→CANCELLED
  payload JSONB NOT NULL, updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```
오케스트레이터는 `saga-replies`를 듣고 상태에 따라 다음 커맨드/보상을 **outbox로** 발행(상태변경+커맨드 row가 원자적). 정체 saga는 `@Scheduled` sweep으로 타임아웃 처리.

### 실패 주입 & 검증
- **결제 거절:** 금액 `> 5000` 또는 `POST /payment/_fail-next` → 재고 해제 관찰.
- **품절:** 재고 초과 주문 → 결제 이벤트 없이 주문 CANCELLED(짧은 보상 체인).
- **크래시:** `InventoryReserved` 직후 payment kill → 재시작 시 재개(타임아웃/리플레이).
- **중복:** 릴레이의 `setPublishedAt` 잠시 주석 → 부수효과 정확히 1회(멱등 증명).
- **테스트:** 오케스트레이터 transition을 순수 switch로 단위테스트(Kafka 불필요) + Testcontainers(arm64 OK: `org.testcontainers:kafka`/`:postgresql`)로 통합 + Awaitility로 종단 상태 대기.

---

## 9. 부록 B — 관측성/복원력 상세

### application.yml (트레이싱·메트릭·샘플링)
```yaml
spring:
  application: { name: order-service }
  kafka:
    template:  { observation-enabled: true }   # ★ 없으면 Kafka 트레이스 끊김
    listener:  { observation-enabled: true }
management:
  tracing: { sampling: { probability: 1.0 } }   # 학습용 100% (prod는 ~0.1)
  otlp:    { tracing: { endpoint: http://localhost:4318/v1/traces } }  # compose: otel-collector:4318
  endpoints: { web: { exposure: { include: health,info,prometheus,metrics } } }
  metrics:   { distribution: { percentiles-histogram: { http.server.requests: true } } }  # p95용 _bucket
```

### Resilience4j — 이 도메인에서 각 패턴의 자리
| 패턴 | 위치 | 막는 장애 |
|---|---|---|
| TimeLimiter | order→payment 동기 호출 | 무한 대기로 스레드 점유 |
| CircuitBreaker | order→payment, order→inventory | 반복 실패 → 캐스케이드 붕괴 |
| Retry | Kafka 소비자 투영, 멱등 GET | 일시적 결함(단, 멱등할 때만) |
| Bulkhead | 느린 CQRS 투영/리포트 | 한 느린 작업이 전 스레드 고갈 |
| RateLimiter | 게이트웨이 공개 EP, Saga 시작 EP | 폭주/남용 |

```yaml
resilience4j:
  circuitbreaker.instances.paymentService:
    sliding-window-size: 10
    failure-rate-threshold: 50
    wait-duration-in-open-state: 10s
    permitted-number-of-calls-in-half-open-state: 3
    register-health-indicator: true
  timelimiter.instances.paymentService: { timeout-duration: 2s }
  retry.instances.inventoryProjection: { max-attempts: 3, wait-duration: 500ms }
  bulkhead.instances.reportProjection: { max-concurrent-calls: 5 }
  ratelimiter.instances.startSaga: { limit-for-period: 20, limit-refresh-period: 1s }
```

### 분리형 관측성 compose (Phase 8b) — 포트 치트시트
**3000** Grafana · **4317/4318** OTLP in · **3200** Tempo · **9090** Prometheus · **3100** Loki · **8889** Collector.
이미지: `grafana/tempo`, `grafana/loki`, `grafana/promtail`, `prom/prometheus`, `grafana/grafana`, `otel/opentelemetry-collector-contrib` — 전부 arm64 네이티브.

### 카오스 실습 6종 (주입 → 관찰 → 증명)
1. 지연 주입 → 트레이스 워터폴 + TimeLimiter 2s 컷
2. 캐스케이드 실패 → CircuitBreaker OPEN(`resilience4j_circuitbreaker_state`)
3. 일시 결함 → Retry 복구
4. 스레드 기아 → Bulkhead 격리(A/B)
5. 폭주 → RateLimiter 429
6. Saga 보상 → **한 트레이스**에서 보상 스팬 분기 (로그 라인 → traceId 클릭 → 그 트레이스)

---

## 10. 부록 C — 인프라/런타임 핵심

### 레이어드 compose
```
deploy/compose/
  compose.infra.yml   # DB들·Kafka·Keycloak·관측성  (일상 드라이버, --profile로 게이트)
  compose.apps.yml    # Spring 서비스들             (통합 모드, --profile full)
  .env                # 이미지 태그·시크릿
```
핵심 디렉티브: **healthcheck + `depends_on: condition: service_healthy`**(sleep 금지), **`profiles`**(개념별 on/off), **서비스별 `mem_limit`**, **DB별 고유 호스트 포트**(5433/5434…로 IDE가 각 DB 접속).

### compose → k8s 매핑
| compose | k8s |
|---|---|
| `services`(앱) | Deployment + Service(ClusterIP) |
| 포트 매핑 | Service + **Ingress**(게이트웨이만) |
| `environment` 평문 | ConfigMap |
| `environment` 시크릿 | Secret |
| `volumes`(Postgres) | PVC + StatefulSet(또는 Bitnami 차트) |
| `healthcheck` | liveness/readiness Probe |
| Eureka discovery | **삭제** → k8s Service DNS |
| Spring Cloud Gateway | **유지**(Deployment) + Ingress 한 개 |

### 이미지 빌드
일상은 `bootBuildImage`(Cloud Native Buildpacks, `BP_JVM_VERSION=21`). 단, **레이어드 Dockerfile 한 개**를 Phase 7에 직접 써보며 레이어 캐싱 원리를 익힌다(앱 코드를 마지막·최소 레이어로 → 컨트롤러 수정 시 KB만 재빌드).

---

## 11. 통합 함정 모음 (자주 밟는 지뢰)

- Boot/Cloud 버전 짝 안 맞추기 → 클래스패스 오류. **3.5.x ↔ 2025.0.x(Northfields)만.** (Boot 4.x는 2025.1.x Oakwood 필요)
- 옛 `spring-cloud-starter-gateway` 사용(2025.0에서 deprecated) → `-server-webflux` + `spring.cloud.gateway.server.webflux.*`.
- **postgres:18+ 볼륨 마운트**: 데이터가 `/var/lib/postgresql/<버전>/docker`로 이동. 옛 `/var/lib/postgresql/data`에 마운트하면 컨테이너 즉시 종료 → `/var/lib/postgresql`(상위)에 마운트. (모든 서비스 DB 공통)
- **읽기 엔드포인트 500 (LazyInitializationException)**: `open-in-view: false`에서 엔티티의 LAZY 컬렉션을 컨트롤러에서 직렬화하면 터진다 → DTO 변환을 `@Transactional` 안에서 하거나 `@EntityGraph`로 즉시 로딩.
- `confluentinc/cp-kafka`를 Apple Silicon에서 → arm64 마찰. **`apache/kafka` KRaft.**
- 단일 노드 Kafka에 `replication-factor=3` 튜토리얼 복붙 → 토픽 생성 실패. **1로.**
- `ddl-auto`에 의존 → `down -v` 한 번에 스키마 증발. **Flyway.**
- 이중 쓰기(`save()`+`send()`) → 유실/유령 이벤트. **outbox.**
- 비계측 HTTP 클라이언트(`new RestTemplate()`) → 트레이스 끊김. **주입받은 빌더.**
- Kafka observation 비활성 → 비동기 트레이스 사라짐. `observation-enabled: true`.
- `@TimeLimiter`를 동기 메서드에 → 무효. `CompletableFuture` 반환 필요.
- 비멱등 작업에 `@Retry` → 이중 청구. 멱등 작업·dedup 보호된 소비자에만.
- 보상을 rollback으로 착각, 보상을 비멱등으로(이중 환불), 안 일어난 단계 보상(유령 환불).
- kind에서 `kind load docker-image` 잊음 → `ImagePullBackOff`.
- k8s에서 `management.endpoint.health.probes.enabled` 없이 readiness 404 → pod 영원히 NotReady.
- 18GB에서 전체 동시 기동 → 스왑·thrash. profiles 필수.

---

## 12. 공수 & 사용법

- **현실적 총량: 약 18~22 풀데이** (코어 3서비스 기준). 저녁·주말이면 **6~10주**. 6서비스 목표까지 가면 여러 Phase가 거의 2배.
- 가장 무거운 Phase: 8(관측성)·13(오케스트레이션)·14(복원력)·16(k8s). 첫 도전에 시간 잡아먹는 톱: **Kafka 트레이스 전파**, otel-collector 설정, kind 이미지 로딩, BOM 버전 드리프트. +30~50% 버퍼 권장.
- **사용법:** Phase를 순서대로. 각 Phase의 "검증/일부러 고장" 단계를 **반드시** 거쳐야 다음으로. 매 Phase 끝에서 동작하는 상태로 커밋.
- **막히면:** 그 Phase의 부록(A/B/C)에 코드·설정 스니펫이 있습니다.

---

## 13. 학습자에게 남은 결정 (진행하며 골라도 됨)

1. **CQRS 읽기 저장소:** Mongo(읽기/쓰기 분리가 더 체감) vs Postgres(컨테이너 1개 절약). 기본 권장 Mongo.
2. **Saga 오케스트레이터 위치:** order-service 안(컨테이너 적음) vs 별도 orchestration-service(현실적·k8s 이전 용이).
3. **컨테이너 런타임:** Colima(권장) vs Docker Desktop(GUI).
4. **gRPC:** order↔inventory 엣지에 도입할지(Phase 18 확장).
5. **보안 깊이:** Keycloak 풀 OIDC vs 단순 JWT 검증만.
6. **확장 서비스(shipping/catalog/customer):** 언제 추가할지(패턴 익힌 뒤 Phase 18 권장).

---

*이 문서는 6개 병렬 설계 + 3개 교차 검증(버전 웹 검증 포함)의 종합 결과입니다. 버전·이미지 태그는 빌드 시점에 재확인하세요(패치 숫자는 드리프트하지만 선택과 호환 쌍은 안정적입니다).*
