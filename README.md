# ShopSaga — MSA 핸즈온 학습 플랫폼

Spring Cloud로 마이크로서비스 아키텍처를 **한 단계씩 직접 만들며 트레이드오프를 배우는** 학습 프로젝트.
전체 로드맵(18단계)은 **[`MSA-LEARNING-PLAN.md`](./MSA-LEARNING-PLAN.md)**,
설치·실행 상세(트러블슈팅 포함)는 **[`docs/SETUP.md`](./docs/SETUP.md)**,
서비스 내부 아키텍처(**헥사고날**) 컨벤션은 **[`docs/HEXAGONAL.md`](./docs/HEXAGONAL.md)** 참고.

- 스택: Java 21 LTS · Spring Boot 3.5.15 · Spring Cloud 2025.0.3 (Northfields) · Gradle 8.14
- 빌드: Gradle 멀티모듈 모노레포 · 실행: 로컬 Docker Compose(→ 이후 Kubernetes)
- 도메인: 전자상거래 "ShopSaga" (order → inventory → payment)

---

## 현재 상태: Phase 5 — JWT 인증/인가까지 완료

5개 서비스가 **서비스 디스커버리(Eureka)** 로 서로를 이름으로 찾고, **API 게이트웨이**가 단일 진입점이며,
**JWT(RS256)** 로 인증/인가가 걸려 있습니다.

| 서비스 | 포트 | 역할 | DB |
|---|---|---|---|
| **discovery-service** | 8761 | Eureka 서버(서비스 레지스트리) | — |
| **auth-service** | 9000 | RS256 JWT 발급(`/auth/login`) + JWKS(`/oauth2/jwks`) | — |
| **gateway-service** | 8000 | 단일 진입점 라우팅 + 엣지 인증(리소스 서버) | — |
| **order-service** | 8080 | 주문 + 재고, 결제는 payment 원격 호출 | `orderdb`@5432 |
| **payment-service** | 8081 | 결제 캡처 | `paymentdb`@5433 |

- 클라이언트는 **게이트웨이(8000)** 만 알면 됩니다. 서비스 위치는 Eureka가, 인증은 JWT가 처리.
- 서비스 간 통신은 **이름 기반**(게이트웨이 `lb://order-service`, order→payment `http://payment-service` @LoadBalanced).
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

공통 아키텍처 컨벤션은 [HEXAGONAL.md](./docs/HEXAGONAL.md), 설치/실행은 [SETUP.md](./docs/SETUP.md).

> 각 문서 끝에는 **"알려진 한계 → 해결 Phase"** 표가 있어, 그 단계에서 의도적으로 남긴 문제와
> 그것이 어느 단계에서 해결되는지 볼 수 있습니다.

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

## 실행 & 검증 (현재: Phase 5)

### 1) 빌드 & 테스트
```bash
# 동시성 통합 테스트(StockConcurrencyTest)는 PostgreSQL 컨테이너 필요 → Docker 실행 중이어야 함.
DOCKER_HOST=unix://$HOME/.colima/default/docker.sock TESTCONTAINERS_RYUK_DISABLED=true ./gradlew build
```

### 2) 인프라 + 서비스 기동 (순서 중요: discovery 먼저)
```bash
# (a) DB
docker compose -f deploy/compose/compose.infra.yml up -d order-db payment-db
# (b) 레지스트리 먼저 → 인증 → 나머지
./gradlew :services:discovery-service:bootRun   # 8761 (먼저)
./gradlew :services:auth-service:bootRun        # 9000
./gradlew :services:order-service:bootRun       # 8080
./gradlew :services:payment-service:bootRun     # 8081
./gradlew :services:gateway-service:bootRun     # 8000
# 등록 확인: curl -H 'Accept: application/json' localhost:8761/eureka/apps
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

## 다음: Phase 6 — 중앙 설정 (Spring Cloud Config)

흩어진 설정(특히 시크릿: DB 비밀번호·`jwk-set-uri` 등)을 Config Server로 외부화합니다.
이후 7 compose → 8 관측성 → 9 Kafka → 10 outbox → 11 CQRS → 12·13 Saga → 14 복원력 → 15 강화 → 16 k8s → 17 CI/CD → 18 캡스톤.
자세한 단계는 [`MSA-LEARNING-PLAN.md`](./MSA-LEARNING-PLAN.md) 참고.
