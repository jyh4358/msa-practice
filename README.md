# ShopSaga — MSA 핸즈온 학습 플랫폼

Spring Cloud로 마이크로서비스 아키텍처를 **한 단계씩 직접 만들며 트레이드오프를 배우는** 학습 프로젝트.
전체 로드맵(18단계)은 **[`MSA-LEARNING-PLAN.md`](./MSA-LEARNING-PLAN.md)**,
설치·실행 상세(트러블슈팅 포함)는 **[`docs/SETUP.md`](./docs/SETUP.md)**,
서비스 내부 아키텍처(**헥사고날**) 컨벤션은 **[`docs/HEXAGONAL.md`](./docs/HEXAGONAL.md)** 참고.

- 스택: Java 21 LTS · Spring Boot 3.5.15 · Spring Cloud 2025.0.3 (Northfields) · Gradle 8.14
- 빌드: Gradle 멀티모듈 모노레포 · 실행: 로컬 Docker Compose(→ 이후 Kubernetes)
- 도메인: 전자상거래 "ShopSaga" (order → inventory → payment)

---

## 현재 상태: Phase 13 — Saga 오케스트레이션(중앙 조정자 + 타임아웃)

6개 서비스가 **중앙 설정(Config Server)** 에서 설정을 받고, **서비스 디스커버리(Eureka)** 로 서로를
이름으로 찾으며, **API 게이트웨이**가 단일 진입점이고, **JWT(RS256)** 로 인증/인가가 걸려 있습니다.
DB 비밀번호 등 시크릿은 **암호화(`{cipher}`)** 되어 중앙 관리됩니다.
이제 이 전체 스택(6 서비스 + 2 DB)을 **`docker compose up` 한 번으로** 컨테이너로 띄울 수 있습니다.
**Phase 8**부터는 한 요청이 게이트웨이→주문→결제를 거치는 과정을 **하나의 분산 트레이스**로 추적하고 지표(메트릭)를 모아,
`grafana/otel-lgtm` 올인원 백엔드의 **Grafana(:3000)** 에서 눈으로 봅니다.
**Phase 9**부터는 order가 재고를 직접 예약하지 않고 **`OrderPlaced` 이벤트를 Kafka로 발행**하면
분리된 **inventory-service**가 **비동기로** 소비해 예약합니다(결과적 일관성). Kafka·inventory는 **`--profile async`** 로 기동합니다.
**Phase 10**은 그 발행을 **트랜잭셔널 Outbox**로 바꿉니다: 주문 저장과 이벤트 기록을 **한 트랜잭션**에 담고(이중 쓰기 제거),
별도 **릴레이**가 Kafka로 내보내며, inventory는 **`messageId`로 중복을 걸러**(멱등) 같은 메시지를 여러 번 받아도 재고를 **정확히 한 번** 예약합니다.
**Phase 11**은 **읽기와 쓰기를 분리(CQRS)** 합니다: 새 **order-query-service**가 같은 이벤트를 **다른 컨슈머 그룹**으로 구독해
**비정규화 읽기 모델**(MongoDB `order_views`)을 유지하고 `GET /order-views` 로 **조인 없이** 조회합니다. 읽기 모델은 파생물이라
**지우고 offset 0부터 재생하면 동일하게 복원**됩니다(투영의 결정성).
**Phase 12**부터는 **동기 결제 호출이 사라지고** 주문·재고·결제가 **이벤트로만 협력하는 Saga**가 됩니다(중앙 조정자 없는 코레오그래피):
주문은 `PENDING → INVENTORY_RESERVED → CONFIRMED | CANCELLED` 상태 기계가 되고, 실패하면 **보상**으로 되돌립니다
(결제 거절 → 재고 자동 해제 + 주문 취소). outbox에 `traceparent`를 실어 **Saga 전체가 하나의 트레이스**로 보입니다.
**Phase 13**은 **같은 업무를 중앙 조정자로 다시** 만듭니다: `saga_instance` 테이블로 진행 상황을 **한 줄로 조회**하고,
**타임아웃 sweep**이 응답 없는 Saga를 재촉·종료합니다(Phase 12가 못 하던 일). `saga.mode` 로 **두 방식을 갈아 끼우며 비교**할 수 있습니다.

| 서비스 | 포트 | 역할 | DB |
|---|---|---|---|
| **config-service** | 8888 | Config Server(중앙 설정, native 백엔드 + 시크릿 암호화) | — |
| **discovery-service** | 8761 | Eureka 서버(서비스 레지스트리) | — |
| **auth-service** | 9000 | RS256 JWT 발급(`/auth/login`) + JWKS(`/oauth2/jwks`) | — |
| **gateway-service** | 8000 | 단일 진입점 라우팅 + 엣지 인증(리소스 서버) | — |
| **order-service** | 8080 | 주문 접수 + **Saga 상태기계**(이벤트 듣고 확정/취소) | `orderdb`@5432 |
| **payment-service** | 8081 | 결제 캡처 — **`InventoryReserved` 구독**(호출자 없음) | `paymentdb`@5433 |
| **inventory-service** | 8082 | 재고 예약 + **보상(해제)** — `OrderPlaced`·`PaymentDeclined` 구독 | `inventorydb`@5434 |
| **order-query-service** | 8083 | **CQRS 읽기 모델**(3토픽 투영 → 조회 전용) | `orderquerydb`(Mongo)@27017 |

> Phase 12부터 order/payment/inventory 는 **서로 호출하지 않고** Kafka 이벤트로만 협력합니다(`--profile async` 필수).

- 클라이언트는 **게이트웨이(8000)** 만 알면 됩니다. 서비스 위치는 Eureka가, 인증은 JWT가 처리.
- 서비스 간 통신: 게이트웨이는 **이름 기반**(`lb://order-service`), 업무 흐름은 **Kafka 이벤트**(Phase 12 — 동기 호출 제거).
- 설정은 **config-service(8888)** 에서 받습니다(로컬엔 이름·import·포트만). DB 비번은 `{cipher}` 암호문으로 중앙 저장.
- 데모 계정: `alice/secret`(USER), `admin/admin123`(USER+ADMIN).

---

## 단계별 진행 & 문서

각 단계에서 **무엇을·왜 했는지**와 트레이드오프를 문서로 정리했습니다(초심자 친화).

| Phase | 내용 | 문서 |
|---|---|---|
| **0** | 스캐폴드·툴체인(모노레포·버전 카탈로그·Flyway·Colima·동작 증명) | [PHASE-0-SCAFFOLD.md](./docs/PHASE-0-SCAFFOLD.md) |
| **1** | 모놀리스: 주문+재고+결제 **단일 트랜잭션 ACID**, 비관적 락·QueryDSL·출력모델·Lombok | [PHASE-1-MONOLITH.md](./docs/PHASE-1-MONOLITH.md) |
| **2** | payment-service **분리**(2-1) + **원격 결제 전환**(2-2, 단일 트랜잭션 소멸) | [PHASE-2-SPLIT-PAYMENT.md](./docs/PHASE-2-SPLIT-PAYMENT.md) |
| **3** | **API Gateway**(단일 진입점, Spring Cloud Gateway) | [PHASE-3-GATEWAY.md](./docs/PHASE-3-GATEWAY.md) |
| **4** | **서비스 디스커버리**(Eureka, 이름 기반 라우팅·호출) | [SERVICE-DISCOVERY.md](./docs/SERVICE-DISCOVERY.md) |
| **5** | **보안**(RS256 JWT 인증 + 역할 인가 + 토큰 전파) | [SECURITY.md](./docs/SECURITY.md) |
| **6** | **중앙 설정**(Spring Cloud Config, native 백엔드 + 시크릿 암호화) | [PHASE-6-CONFIG.md](./docs/PHASE-6-CONFIG.md) |
| **7** | **로컬 오케스트레이션**(Docker Compose — 이미지 빌드·서비스명 DNS·기동순서) | [PHASE-7-COMPOSE.md](./docs/PHASE-7-COMPOSE.md) |
| **8** | **관측성**(트레이싱·메트릭·**로그→Loki**·**RED 대시보드**·트레이스↔로그 점프 — OTLP → `grafana/otel-lgtm`) | [PHASE-8-OBSERVABILITY.md](./docs/PHASE-8-OBSERVABILITY.md) |
| **9** | **비동기 이벤트**(Kafka — 재고 분리, `OrderPlaced` 발행/소비, HTTP→Kafka 트레이스, 리플레이) | [PHASE-9-ASYNC-KAFKA.md](./docs/PHASE-9-ASYNC-KAFKA.md) |
| **10** | **신뢰성 척추**(트랜잭셔널 Outbox·@Scheduled 릴레이·멱등 소비자 — 이중 쓰기 제거, effectively-once) | [PHASE-10-OUTBOX.md](./docs/PHASE-10-OUTBOX.md) |
| **11** | **CQRS 읽기 모델**(이벤트 투영 → MongoDB 비정규화 조회, 투영 결정성·리플레이 재구축) | [PHASE-11-CQRS.md](./docs/PHASE-11-CQRS.md) |
| **12** | **Saga: 코레오그래피 + 보상**(동기 결제 제거·주문 상태기계·재고 해제 보상·Saga 한 트레이스) | [PHASE-12-SAGA.md](./docs/PHASE-12-SAGA.md) |
| **13** | **Saga: 오케스트레이션**(중앙 조정자·`saga_instance`·타임아웃 sweep·커맨드 멱등·모드 토글) | [PHASE-13-SAGA-ORCHESTRATION.md](./docs/PHASE-13-SAGA-ORCHESTRATION.md) |

공통 아키텍처 컨벤션은 [HEXAGONAL.md](./docs/HEXAGONAL.md), 설치/실행은 [SETUP.md](./docs/SETUP.md).

> 각 문서 끝에는 **"알려진 한계 → 해결 Phase"** 표가 있어, 그 단계에서 의도적으로 남긴 문제와
> 그것이 어느 단계에서 해결되는지 볼 수 있습니다.
>
> 📖 **파트 A(Phase 0~7) 1차 복습**: [REVIEW-PART-A.md](./docs/REVIEW-PART-A.md) — 큰 그림·개념 자가진단·셀프 퀴즈·재현 체크리스트.

---

## 사전 준비 (1회)

```bash
# 컨테이너 런타임 (Apple Silicon)
brew install colima docker docker-compose
colima start --arch aarch64 --cpu 4 --memory 8
# JDK 21 — 선택. 없으면 Gradle 툴체인이 자동으로 내려받음.
```
> `./gradlew` 빌드는 설치된 JDK로 실행되고, 컴파일/테스트 타깃만 Java 21로 고정됩니다.

---

## 실행 & 검증 (현재: Phase 7)

### 0) 한 번에 전체 스택 (Docker Compose — 권장)
```bash
./gradlew bootJar
cp deploy/compose/.env.example deploy/compose/.env            # ENCRYPT_KEY 준비
docker compose -f deploy/compose/compose.yml up -d --build    # 6 서비스 + 2 DB + 관측성(otel-lgtm)
# 호스트 공개 포트: 게이트웨이 :8000, Eureka :8761, Config :8888, Grafana :3000 (나머지 내부 전용)
# 상태: docker compose -f deploy/compose/compose.yml ps   |   정지: ... down (볼륨까지: down -v)
```
아래 1)~3)은 컨테이너 없이 호스트에서 개별 실행하는 개발 루프입니다(둘 중 하나만 — 포트 충돌).

### 1) 빌드 & 테스트
```bash
# 동시성 통합 테스트(StockConcurrencyTest)는 PostgreSQL 컨테이너 필요 → Docker 실행 중이어야 함.
DOCKER_HOST=unix://$HOME/.colima/default/docker.sock TESTCONTAINERS_RYUK_DISABLED=true ./gradlew build
```

### 2) 인프라 + 서비스 기동 (순서 중요: config·discovery 먼저)
```bash
# (a) DB
docker compose -f deploy/compose/compose.infra.yml up -d order-db payment-db
# (b) 설정 서버 먼저(시크릿 복호화 키를 env로 주입) → 레지스트리 → 인증 → 나머지
export ENCRYPT_KEY='shopsaga-dev-encrypt-key-0123456789ab'   # dev 키(운영은 절대 커밋/공유 X)
./gradlew --no-daemon :services:config-service:bootRun   # 8888 (먼저, ENCRYPT_KEY 필요)
./gradlew :services:discovery-service:bootRun   # 8761
./gradlew :services:auth-service:bootRun        # 9000
./gradlew :services:order-service:bootRun       # 8080
./gradlew :services:payment-service:bootRun     # 8081
./gradlew :services:gateway-service:bootRun     # 8000
# 설정 확인: curl localhost:8888/order-service/default   |   등록 확인: curl -H 'Accept: application/json' localhost:8761/eureka/apps
```

### 3) 인증 → 호출 (게이트웨이 8000 통과)
```bash
# 로그인 → JWT 획득
TOKEN=$(curl -s -X POST localhost:8000/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"secret"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')

# 토큰 없이 → 401 (엣지 차단)
curl -s -o /dev/null -w '%{http_code}\n' localhost:8000/orders

# 토큰으로 주문 생성 → 201 (order가 payment까지 토큰 전파)
P2=22222222-2222-2222-2222-222222222222
curl -s -X POST localhost:8000/orders -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"customerId\":\"11111111-1111-1111-1111-111111111111\",\"items\":[{\"productId\":\"$P2\",\"quantity\":1,\"unitPrice\":10.00}]}"

# 역할 인가: 주문 목록은 ADMIN 전용 → alice(USER) 는 403
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOKEN" localhost:8000/orders
```

> Phase 1의 단일 트랜잭션 ACID 시연(해피/결제거절/재고부족)은 [PHASE-1-MONOLITH.md](./docs/PHASE-1-MONOLITH.md) 참고.
> 각 서비스 Swagger: `http://localhost:<port>/swagger-ui/index.html` (order/payment).

---

## 다음: Phase 13 — Saga: 오케스트레이션

Phase **12**(코레오그래피 Saga: 동기 결제 제거·주문 상태기계·보상·Saga 한 트레이스)를 마쳤습니다.
Phase 12의 최대 단점은 **흐름이 여러 서비스에 흩어져 한눈에 안 보인다**는 것입니다.
다음 **13 오케스트레이션**은 같은 Saga를 **중앙 조정자**(`saga_instance` 상태 테이블 + reply 처리)로 다시 구현해
`SELECT state FROM saga_instance WHERE order_id=…` **한 줄로** 진행 상황을 알 수 있게 하고,
**타임아웃 sweep**으로 정체된 Saga를 깨웁니다(Phase 12가 못 하는 것).
이후 **14 복원력**(Resilience4j·DLQ) → 15 강화 → 16 k8s → 17 CI/CD → 18 캡스톤.
자세한 단계는 [`MSA-LEARNING-PLAN.md`](./MSA-LEARNING-PLAN.md) 참고.

> ⚠️ **Phase 12부터 주문 흐름은 `--profile async`(Kafka)가 필수**입니다. 또한 `POST /orders` 는
> 이제 `PENDING` 을 즉시 반환하며, 최종 결과(CONFIRMED/CANCELLED)는 **조회로 확인**합니다(결과적 일관성).
