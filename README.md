# ShopSaga — MSA 핸즈온 학습 플랫폼

[![CI](https://github.com/jyh4358/msa-practice/actions/workflows/ci.yml/badge.svg)](https://github.com/jyh4358/msa-practice/actions/workflows/ci.yml)

Spring Cloud로 마이크로서비스 아키텍처를 **한 단계씩 직접 만들며 트레이드오프를 배우는** 학습 프로젝트.
전체 로드맵(Phase 0~19)은 **[`MSA-LEARNING-PLAN.md`](./MSA-LEARNING-PLAN.md)**,
설치·실행 상세(트러블슈팅 포함)는 **[`docs/SETUP.md`](./docs/SETUP.md)**,
서비스 내부 아키텍처(**헥사고날**) 컨벤션은 **[`docs/HEXAGONAL.md`](./docs/HEXAGONAL.md)** 참고.

- 스택: Java 21 LTS · Spring Boot 3.5.15 · Spring Cloud 2025.0.3 (Northfields) · Gradle 8.14
- 빌드: Gradle 멀티모듈 모노레포 · 실행: 로컬 Docker Compose(→ 이후 Kubernetes)
- 도메인: 전자상거래 "ShopSaga" (order → inventory → payment)

---

## 현재 상태: Phase 19 완료 — GitOps (Argo CD가 Git을 보고 스스로 배포한다)

**6개 런타임 서비스**(도메인 3: order·payment·inventory + gateway + auth + order-query)가
주문→재고→결제 **Saga**로 협력하고, 그 전체가 **로컬 Kubernetes(kind)** 위에서 **Argo CD**가 Git 상태를 보고 스스로 배포·복구합니다.
push 하면 **GitHub Actions** 가 빌드·테스트·멀티아치 이미지·**CI 안 kind 스모크 배포**까지 자동으로 검증하고,
통과한 이미지 태그를 `overlays/gitops/`에 커밋하면 Argo CD가 그 상태로 동기화합니다.

> ⚠️ **Phase 16b 에서 두 서비스가 사라졌습니다** — `discovery-service`(Eureka)와 `config-service`(Config Server).
> 그 일을 이제 **플랫폼이** 합니다: 디스커버리는 **DNS**(compose 네트워크 / k8s Service), 설정은 **파일**(ConfigMap·바인드마운트).
> Phase 4·6 문서는 *"그때는 왜 필요했나"* 의 기록으로 남아 있습니다.

| 서비스 | 포트 | 역할 | 저장소 |
|---|---|---|---|
| **gateway-service** | 8000 | 단일 진입점 라우팅 + 엣지 JWT + 라우트별 회로차단기 | — |
| **auth-service** | 9000 | RS256 JWT 발급(`/auth/login`) + JWKS(`/oauth2/jwks`) | — (키는 Secret) |
| **order-service** | 8080 | 주문 접수 + **Saga 조정자** · outbox | `orderdb`(Postgres) |
| **payment-service** | 8081 | 결제 — **커맨드/이벤트로만** 동작 | `paymentdb`(Postgres) |
| **inventory-service** | 8082 | 재고 예약 + **보상(해제)** | `inventorydb`(Postgres) |
| **order-query-service** | 8083 | **CQRS 읽기 모델**(3토픽 투영) | `orderquerydb`(Mongo) |
| *인프라* | | Kafka(KRaft) · otel-lgtm(Tempo·Loki·Prometheus·Grafana) | |

- 클라이언트는 **:8000** 만 알면 됩니다(compose 는 게이트웨이, k8s 는 Ingress → 게이트웨이 — **주소가 같습니다**).
- 서비스 간 통신: 업무 흐름은 **Kafka 이벤트**(동기 호출 없음), 주소 해석은 **플랫폼 DNS**.
- 설정: jar 안 로컬 기본값 → `shared/messaging` 공통 → `deploy/config/`(ConfigMap·바인드마운트)의 3층.
- 데모 계정: `alice/secret`(USER), `admin/admin123`(USER+ADMIN).

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
| **14** | **복원력 패턴**(Resilience4j 5종·게이트웨이 회로차단기+fallback·엣지 과부하 차단·**DLQ/poison**·outbox 격리·**고아 결제 보상**) | [PHASE-14-RESILIENCE.md](./docs/PHASE-14-RESILIENCE.md) |
| **15** | **플랫폼 강화**(Spring Cloud Bus 설정 방송·**계약 테스트**(동기 API+이벤트)·스키마 진화 tolerant reader) | [PHASE-15-CONTRACTS.md](./docs/PHASE-15-CONTRACTS.md) |
| **16a** | **로컬 Kubernetes(kind)**(같은 이미지를 k8s로 이전·ConfigMap/Secret·**liveness≠readiness probe**·NodePort·자가치유·스케일 아웃) | [PHASE-16-KUBERNETES.md](./docs/PHASE-16-KUBERNETES.md) |
| **16b** | **전체 플랫폼 on k8s**(**Eureka·Config Server 삭제** → 플랫폼 DNS+ConfigMap·Ingress·compose 동반 이전·무중단 롤링) | [PHASE-16-KUBERNETES.md](./docs/PHASE-16-KUBERNETES.md#part-16b) |
| **17** | **CI/CD**(GitHub Actions·jar 1회 빌드·**네이티브 러너 2개 멀티아치**·GHCR `:커밋SHA`·CI 안에서 kind 스모크 배포) | [PHASE-17-CICD.md](./docs/PHASE-17-CICD.md) |
| **18** | **선언적 배포**(Kustomize base/overlay·**설정 변경 → 자동 롤아웃**(해시 접미사)·CI의 `sed` 제거·ingress-nginx를 **Helm 릴리스**로) | [PHASE-18-KUSTOMIZE.md](./docs/PHASE-18-KUSTOMIZE.md) |
| **19** | **GitOps**(Argo CD가 Git을 보고 스스로 배포·**CI→Git 승격 루프**·`selfHeal`로 드리프트 복원·`prune`으로 Phase 18 한계 해결) | [PHASE-19-GITOPS.md](./docs/PHASE-19-GITOPS.md) |
| — | **남은 주제**(Phase 재번호매김으로 무효화된 "해결 Phase" 약속을 모은 단일 목록) | [BACKLOG.md](./docs/BACKLOG.md) |
| — | **감사 기록**(2026-08-02, 로드맵/구현/인프라/보안 4개 축) | [AUDIT-2026-08.md](./docs/AUDIT-2026-08.md) |

공통 아키텍처 컨벤션은 [HEXAGONAL.md](./docs/HEXAGONAL.md), 설치/실행은 [SETUP.md](./docs/SETUP.md).

> 각 문서 끝에는 **"알려진 한계 → 해결 Phase"** 표가 있어, 그 단계에서 의도적으로 남긴 문제와
> 그것이 어느 단계에서 해결되는지 볼 수 있습니다.
>
> 📖 **복습 자료** — 각 파트마다 *큰 그림 · 단계별 회고 · 개념 자가진단 · 셀프 퀴즈(접힘 답) · 재현 체크리스트 · 누적 한계표*:
> [파트 A](./docs/REVIEW-PART-A.md)(0~7 동기 플랫폼) ·
> [파트 B](./docs/REVIEW-PART-B.md)(8~11 관측성·비동기·Outbox·CQRS) ·
> [파트 C](./docs/REVIEW-PART-C.md)(12~15 Saga·복원력·계약) ·
> [파트 D](./docs/REVIEW-PART-D.md)(16~19 k8s·CI/CD·GitOps)
>
> 심화 문서를 처음부터 다시 읽는 대신 **퀴즈를 먼저 풀고 막히는 곳만 파고드는** 용도입니다.

---

## 사전 준비 (1회)

```bash
# 컨테이너 런타임 (Apple Silicon)
brew install colima docker docker-compose
colima start --arch aarch64 --cpu 6 --memory 12   # Phase 16(k8s)부터 12GB 권장 — compose 만 쓸 땐 8GB 로 충분
# Kubernetes 도구 (Phase 16~)
brew install kubectl kind helm k9s
# JDK 21 — 선택. 없으면 Gradle 툴체인이 자동으로 내려받음.
```
> `./gradlew` 빌드는 설치된 JDK로 실행되고, 컴파일/테스트 타깃만 Java 21로 고정됩니다.
> 이미 Colima 가 있다면 스펙 변경은 `colima stop && colima start --cpu 6 --memory 12`
> (디스크·이미지·볼륨은 보존됩니다).

---

## 실행 & 검증

실행 경로가 **두 가지**입니다. RAM·포트가 겹치므로 **동시에 띄우지 마세요.**

### A) Docker Compose — 가볍게 (13 컨테이너)

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
./gradlew bootJar
docker compose -f deploy/compose/compose.yml --profile async up -d --build
# 공개 포트: 게이트웨이 :8000 · Grafana :3000 · kafka-ui :8090 · kafka :9092 · mongo :27017
# 정지: docker compose -f deploy/compose/compose.yml --profile async down
```

### B) Kubernetes(kind) — Phase 16 의 결과물 (13 파드)

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
kind create cluster --config deploy/k8s/kind-cluster.yaml   # 최초 1회
./deploy/k8s/build-and-load.sh all                          # bootJar + 이미지 + kind load
./deploy/k8s/apply.sh                                       # ConfigMap·Secret·ingress·전체 배포
kubectl get pods -n shopsaga -w
```

자세한 내용·함정표는 [`deploy/k8s/README.md`](./deploy/k8s/README.md).

### 빌드 & 테스트

```bash
# Testcontainers 통합 테스트가 PostgreSQL 컨테이너를 실제로 띄웁니다 → Docker 필요.
DOCKER_HOST=unix://$HOME/.colima/default/docker.sock TESTCONTAINERS_RYUK_DISABLED=true ./gradlew build
```

### 스모크 (A·B 어느 쪽이든 주소가 같습니다)

```bash
TOKEN=$(curl -s -X POST localhost:8000/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"secret"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')

curl -s -o /dev/null -w '%{http_code}\n' localhost:8000/orders          # 토큰 없이 → 401

# 주문 → PENDING 즉시 반환(결과적 일관성). 최종 상태는 조회로 확인.
OID=$(curl -s -X POST localhost:8000/orders -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"customerId":"cccc1111-1111-1111-1111-111111111111",
       "items":[{"productId":"22222222-2222-2222-2222-222222222222","quantity":2,"unitPrice":10.00}]}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')

curl -s localhost:8000/orders/$OID -H "Authorization: Bearer $TOKEN"     # → CONFIRMED
curl -s "localhost:8000/order-views?customerId=cccc1111-1111-1111-1111-111111111111" -H "Authorization: Bearer $TOKEN"
curl -s localhost:8000/inventory/22222222-2222-2222-2222-222222222222 -H "Authorization: Bearer $TOKEN"
```

> **실패 시연**: 수량 9999(재고 부족 → CANCELLED) · 합계 `*.99`(결제 거절 → 재고 원복 + CANCELLED).
> 각 서비스 Swagger: `http://localhost:<port>/swagger-ui/index.html`.

---

## 다음은?

**Phase 0~19 완료.** 남은 주제는 필수가 아니라 고르는 확장 과제입니다 —
목록·우선순위·"왜 지금 안 했나"는 **[`docs/BACKLOG.md`](./docs/BACKLOG.md)** 참고.

> ⚠️ **Phase 12부터 주문 흐름은 Kafka 가 필수**입니다(compose 는 `--profile async`). 또한 `POST /orders` 는
> `PENDING` 을 즉시 반환하며, 최종 결과(CONFIRMED/CANCELLED)는 **조회로 확인**합니다(결과적 일관성).
