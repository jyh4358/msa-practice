# ShopSaga MSA — 세션 핸드오프 (2026-07-18)

> 새 세션에서 이 파일 + 프로젝트 메모리를 먼저 읽고 이어가세요. **가장 완전한 상태는 프로젝트 메모리**
> `~/.claude/projects/-Users-younho-IdeaProjects-msa/memory/msa-learning-project.md` 에 있습니다.
> 이 파일은 "지금 어디까지 왔고, 바로 다음에 뭘 하나"의 빠른 지도입니다.

## 이 프로젝트가 뭔가
- **ShopSaga**: Spring Cloud MSA를 **Phase별로 직접 만들며 트레이드오프를 배우는** 학습 프로젝트(제품화 아님).
- 사용자: Java/Spring 개발자(GitHub `jyh4358`, 원격 `github.com/jyh4358/msa-practice`). 한국어.
- 스택(고정): Java 21 · Spring Boot **3.5.15** · Spring Cloud **2025.0.3** · Gradle 멀티모듈 모노레포(버전 카탈로그 `gradle/libs.versions.toml`).
- 아키텍처: **헥사고날**(domain/application(port.in·out)/adapter(in.web·out.persistence·out.messaging)). 상세 `docs/HEXAGONAL.md`.
- 컨테이너 런타임: **Colima**(arm64). `DOCKER_HOST=unix://$HOME/.colima/default/docker.sock`.

## 지금까지 완료 (Phase 0~9a)
0 스캐폴드 · 1 모놀리스(ACID·비관적락·QueryDSL) · 2 payment 분리(원격 REST) · 3 게이트웨이 · 4 Eureka ·
5 보안(RS256 JWT) · 6 중앙설정(Config+`{cipher}`) · 7 Docker Compose · 8 관측성(8a 트레이스+메트릭 / 8b 로그→Loki·RED 대시보드·트레이스↔로그) · 9a 비동기(Kafka) · **10 Outbox+멱등(신뢰성 척추)**.
- 각 Phase 심화문서: `docs/PHASE-*.md`, `docs/SERVICE-DISCOVERY.md`(P4), `docs/SECURITY.md`(P5). 오프라인 HTML: `docs/site/`(더블클릭). 커밋지도: `docs/PHASE-COMMIT-MAP.md`.

## 서비스 (13 컨테이너, `--profile async` 기준)
| 서비스 | 포트 | 프로파일 | 역할 |
|---|---|---|---|
| config-service | 8888 | base | Config Server(native+`{cipher}`) |
| discovery-service | 8761 | base | Eureka |
| auth-service | 9000 | base | RS256 JWT 발급/JWKS |
| gateway-service | 8000 | base | 단일 진입점(라우팅+엣지 JWT) |
| order-service | 8080 | base | 주문+결제(동기). **재고 예약 제거**, `OrderPlaced` 발행 |
| payment-service | 8081 | base | 결제 캡처(합계 `.99`→거절 402) |
| **inventory-service** | 8082 | **async** | 재고 소유. `@KafkaListener(order-placed)`→비동기 예약 |
| otel-lgtm | 3000(Grafana)/4317/4318 | base | 관측성(Tempo·Loki·Prometheus·Grafana) |
| kafka | 9092(host)/19092(내부) | **async** | `apache/kafka:4.3.1` KRaft |
| kafka-ui | 8090 | **async** | `ghcr.io/kafbat/kafka-ui` |
| order-db/payment-db/inventory-db | 5432/5433/5434(host) | base/base/async | postgres:18-alpine |

- 데모 계정: `alice/secret`(USER), `admin/admin123`(USER+ADMIN).
- 이벤트 흐름(9a): 주문 → order가 `OrderPlaced`(topic `order-placed`) 발행 → inventory 소비→재고 예약. **HTTP→Kafka 한 트레이스**(producer↔consumer 스팬, `observation-enabled` template+listener 둘 다 필수).

## git 상태 (중요)
- HEAD **`a0d04f6`**(Phase 9a). **origin/main보다 5 커밋 앞섬, 전부 미푸시**: `8df41ab`(docs)·`cbb96f8`(HTML사이트)·`7e352d8`(8a)·`a0f0b1f`(8b)·`a0d04f6`(9a).
- **Phase 10(Outbox+멱등)은 구현·실증 완료했으나 아직 미커밋**(working tree). 커밋 시 order `adapter/out/outbox/**`·`V5__outbox`·`OrderServiceApplication`·(삭제)`OrderEventKafkaAdapter`, inventory `processed_messages`·`V2`·`StockService`·`OrderPlacedListener`·`ReserveStockUseCase`·`ProcessedMessage*`, `config-repo/application.yml`, `docs/PHASE-10-OUTBOX.md`·`README`·`PHASE-COMMIT-MAP`·`build-docs.mjs`·`docs/site/**`, 신규 테스트 3종.
- **푸시는 사용자가 명시 요청할 때만.** 커밋도 사용자 요청 시(단계별 의미 커밋).
- ⚠️ **`docs/PHASE-COMMIT-MAP.md`의 9a 행은 `a0d04f6`로 채움 완료. 10 행이 placeholder** `_(이 커밋 — 다음 갱신 시 기입)_` → **Phase 10 커밋 시 실제 해시로 채울 것.**
- gitignore: `deploy/compose/.env`, `docs/tools/node_modules/`, `**/build/`.

## 실행 / 검증 (재현)
```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
export ENCRYPT_KEY='shopsaga-dev-encrypt-key-0123456789ab'   # dev 전용, 운영 금지
# 전체(비동기 포함):
./gradlew bootJar
docker compose -f deploy/compose/compose.yml --profile async up -d --build
# 빌드/테스트(Docker 필요 — Testcontainers):
DOCKER_HOST=unix://$HOME/.colima/default/docker.sock TESTCONTAINERS_RYUK_DISABLED=true ./gradlew build
```
- 공개 포트: gateway :8000 · Eureka :8761 · Config :8888 · Grafana :3000 · kafka-ui :8090 · kafka :9092.
- 스모크: 로그인(`:8000/auth/login`)→주문(`:8000/orders`)→kafka-ui/Grafana에서 이벤트·트레이스 확인. 재고 `:8000/inventory/{productId}`.
- ⚠️ **gateway는 config만 바뀌면 재시작 필요**(라우트는 기동 시 로드). 9a에서 `/inventory` 라우트 반영 위해 `docker restart shopsaga-gateway-service-1` 했음.
- gradle/docker/git 명령은 `dangerouslyDisableSandbox: true`로 실행.

## 다음 단계 — Phase 11 (CQRS 읽기 모델)
Phase 10(Outbox+멱등)까지 완료. 다음:
1. **CQRS**: `order-query-service`가 `OrderPlaced`/(후속)이벤트를 구독해 **비정규화 읽기모델**(`order_view`, Mongo) 유지. `GET /api/orders/views?customerId=`. 읽기 측은 **outbox→Kafka를 그대로 소비**하므로 Phase 10 위에 안전. 검증 포인트: 읽기 DB 삭제 후 offset 0부터 리플레이 → 동일 상태(투영 결정성).
2. ⚠️ **Phase 12 Saga 때 outbox 트레이스 복원**: `traceparent`를 outbox row에 저장했다 발행 시 Kafka 헤더로 재주입(현재 릴레이 구간 트레이스 끊김 — Phase 10의 의도된 한계). `outbox.traceparent` 컬럼은 이미 만들어 둠.
3. (로드맵 순서: 11 CQRS → 12/13 Saga(보상) → 14 복원력(Resilience4j·DLQ) → 15 강화 → 16 k8s → 17 CI/CD → 18 캡스톤.)
- 로드맵 상세: `MSA-LEARNING-PLAN.md`(Phase 11은 §302~, Saga 부록 ~§482). Phase 10 심화: `docs/PHASE-10-OUTBOX.md`.

## 매 Phase 작업 흐름 (사용자 선호 — 지켜야 함)
1. 리서치(버전 특이점, Workflow로 병렬) → 2. 설계(큰 결정은 AskUserQuestion으로 확인) → 3. 구현 →
4. **빌드/테스트/compose 실증**(트레이스는 `docker exec otel-lgtm curl localhost:3200/api/search`, Kafka는 kafka-ui/CLI) →
5. **문서화**: `docs/PHASE-N-*.md`(구조 0요약→1왜→2개념→3구성→4코드→5흐름→6원리→7검증→8한계표→9용어→10참고) + README 인덱스 + **HTML 재생성**(`cd docs/tools && npm run build`, `build-docs.mjs`의 DOCS 배열에 한 줄 추가) + **PHASE-COMMIT-MAP 해시 추가** →
6. **한계표**("이번 Phase 한계 → 해결 Phase") 제시 → 7. 커밋(요청 시).
- 초보자 친화 문서(용어 첫 등장 정의, why→how). 커밋 메시지: 한국어 Conventional Commits + `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.

## 자주 물리는 함정 (기억)
- 좀비 JVM 포트 점유: `lsof -nP -iTCP:<port> -sTCP:LISTEN -t | xargs kill`.
- bash cwd가 리셋될 수 있음 → gradle/compose는 **절대경로**(`/Users/younho/IdeaProjects/msa/gradlew -p ...`) 권장.
- 커스텀 빌더/템플릿은 관측 계측이 빠질 수 있음(Phase 8: RestClient에 `ObservationRegistry`, Kafka는 `observation-enabled`).
- 단일노드 Kafka: 내부토픽 `replication-factor=1`, `NewTopic` `replicas(1)`, auto-create off.
