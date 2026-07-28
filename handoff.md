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
5 보안(RS256 JWT) · 6 중앙설정(Config+`{cipher}`) · 7 Docker Compose · 8 관측성(8a 트레이스+메트릭 / 8b 로그→Loki·RED 대시보드·트레이스↔로그) · 9a 비동기(Kafka) · 10 Outbox+멱등(신뢰성 척추) · 11 CQRS 읽기모델(Mongo) · **12 Saga(코레오그래피+보상)**.

> ⚠️ **Phase 12부터 주문 흐름은 `--profile async` 필수**(동기 결제 호출 제거 — 전부 Kafka 이벤트).
> `POST /orders` 는 `PENDING` 즉시 반환, 최종 상태는 조회로 확인. 15컨테이너(+order-query-service·mongo).
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
- **Saga 흐름(12)**: `POST /orders`→order **PENDING** 저장+outbox(`OrderPlaced`) 한 커밋 → 릴레이(traceparent 복원) → `order-events`
  → inventory 예약+원장기록 → `InventoryReserved` → **order는 상태만 전이**(INVENTORY_RESERVED), **payment는 같은 이벤트로 청구**
  → `PaymentCharged` → order **CONFIRMED**+`OrderConfirmed`. 실패: `InventoryFailed`→취소(짧은 보상) / `PaymentDeclined`→**inventory 재고 해제**+order 취소(긴 보상).
  토픽 3개(order/inventory/payment-events), 소비는 `@KafkaHandler` 타입 분기.

## git 상태 (중요)
- HEAD **`1dfb81c`**(Phase 11 CQRS). **origin/main과 동기(Phase 11까지 푸시 완료)**.
- **Phase 12(Saga)는 구현·실증 완료했으나 아직 미커밋**(working tree): `shared/outbox/**` 신규, `shared/events` 이벤트 5종+`Topics`,
  order(상태기계·`OrderSagaService`·리스너 2종·`V6`·**동기 결제 어댑터 삭제**), inventory(`StockSagaTransactions`·보상·`V3` 원장·리스너 2종),
  payment(`PaymentSagaService`·리스너·`V2`), order-query(단조 전이·3토픽), compose·config-repo, docs(PHASE-12·README·map·HTML 20p), 테스트 35개.
- **푸시는 사용자가 명시 요청할 때만.** 커밋도 사용자 요청 시(단계별 의미 커밋).
- ⚠️ **`docs/PHASE-COMMIT-MAP.md`의 11 행은 `1dfb81c`로 채움 완료. 12 행이 placeholder** → **Phase 12 커밋 후 다음 커밋 때 실제 해시 기입.**
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
- 공개 포트: gateway :8000 · Eureka :8761 · Config :8888 · Grafana :3000 · kafka-ui :8090 · kafka :9092 · mongo :27017.
- 스모크(Phase 12): 로그인(`:8000/auth/login`) → 주문(`:8000/orders` → **PENDING**) → **폴링**(`:8000/orders/{id}` → CONFIRMED) ·
  읽기모델(`:8000/order-views?customerId=`) · 재고(`:8000/inventory/{productId}`) · kafka-ui/Grafana에서 이벤트·트레이스.
  실패 시연: 수량 9999(재고 부족→CANCELLED) / 합계 `*.99`(결제 거절→재고 원복+CANCELLED).
- ⚠️ **gateway는 config만 바뀌면 재시작 필요**(라우트는 기동 시 로드).
- ⚠️ **Gradle 결과를 `| tail` 로 파이프하면 exit code가 tail 것**이 된다 → `> /tmp/x.log 2>&1; echo $?` 로 확인.
- gradle/docker/git 명령은 `dangerouslyDisableSandbox: true`로 실행.

## 다음 단계 — Phase 13 (Saga: 오케스트레이션)
Phase 12(코레오그래피 Saga)까지 완료. 다음:
1. **오케스트레이션**: 같은 Saga를 **중앙 조정자**로 재구현 — `@Service` 오케스트레이터 + `saga_instance` 상태 테이블
   (`STARTED→AWAITING_INVENTORY→AWAITING_PAYMENT→COMPLETED` / `COMPENSATING_INVENTORY→CANCELLED`) + reply 이벤트 switch.
   참여 서비스는 "멍청한" 커맨드 핸들러가 된다. 교훈 = `SELECT state FROM saga_instance WHERE order_id=…` **한 줄로** 진행 파악
   (Phase 12의 최대 단점인 "흐름이 흩어짐" 해결).
2. ⚠️ **타임아웃 sweep을 크래시 복구 테스트보다 먼저** 만들 것(`@Scheduled` 로 정체된 saga 재전송/보상) — Phase 12는 정체 Saga를 못 깨운다.
3. (로드맵 순서: 13 오케스트레이션 → 14 복원력(Resilience4j·DLQ) → 15 강화 → 16 k8s → 17 CI/CD → 18 캡스톤.)
- 로드맵 상세: `MSA-LEARNING-PLAN.md`(Phase 13은 §318~, Saga 부록 §402~). Phase 12 심화: `docs/PHASE-12-SAGA.md`.

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
- **Apple Silicon + mongo(Phase 11)**: Docker가 `mongo:8`의 **amd64 변이**를 당겨오면 에뮬레이션에서 AVX가 없어 mongod가 안 뜬다(컨테이너는 running인데 포트 미개방 → Testcontainers "Timed out waiting for log output"). 확인 `docker image inspect mongo:8 --format '{{.Architecture}}'`(arm64여야 함)·컨테이너 `uname -m`(aarch64). 해결 `docker rmi mongo:8 && docker pull --platform linux/arm64 mongo:8`.
- **Colima 디스크 포화(Phase 13에서 실제 발생)**: 반복 `up --build`로 이미지가 쌓여 `/var/lib/docker` 가 93% 차면 **DB 오류로 위장**한다 — Mongo `__log_fs_write: fatal log failure`, Postgres `Consistent recovery state has not been yet reached`(크래시 복구 루프). 코드 버그로 오인하기 쉬우니 **먼저 `colima ssh -- df -h /var/lib/docker` 확인**. 해결: `down -v` + `docker system prune -af --volumes`(단, 이미지를 전부 다시 받으므로 재빌드에 수 분 소요).
- **`spring.json.trusted.packages` 는 하위 패키지 미포함**: `com.shopsaga.events` 만 적으면 `com.shopsaga.events.commands.*` 역직렬화가 거부돼 소비가 전부 막힌다(Phase 13에서 겪음). 패키지마다 명시할 것.
