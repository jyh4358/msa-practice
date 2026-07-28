# ShopSaga MSA — 세션 핸드오프 (2026-07-29, Phase 14 완료 시점)

> 새 세션에서 이 파일 + 프로젝트 메모리를 먼저 읽고 이어가세요. **가장 완전한 상태는 프로젝트 메모리**
> `~/.claude/projects/-Users-younho-IdeaProjects-msa/memory/msa-learning-project.md` 에 있습니다.
> 이 파일은 "지금 어디까지 왔고, 바로 다음에 뭘 하나"의 빠른 지도입니다.

## 이 프로젝트가 뭔가
- **ShopSaga**: Spring Cloud MSA를 **Phase별로 직접 만들며 트레이드오프를 배우는** 학습 프로젝트(제품화 아님).
- 사용자: Java/Spring 개발자(GitHub `jyh4358`, 원격 `github.com/jyh4358/msa-practice`). 한국어로 대화.
- 스택(고정): Java 21 · Spring Boot **3.5.15** · Spring Cloud **2025.0.3** · Gradle 멀티모듈 모노레포(버전 카탈로그 `gradle/libs.versions.toml`).
- 아키텍처: **헥사고날**(domain / application(port.in·out) / adapter(in.web·in.event·out.persistence·out.messaging)). 상세 `docs/HEXAGONAL.md`.
- 컨테이너 런타임: **Colima**(arm64). `DOCKER_HOST=unix://$HOME/.colima/default/docker.sock`.

## 지금까지 완료 (Phase 0~13)
0 스캐폴드 · 1 모놀리스(ACID·비관적락·QueryDSL) · 2 payment 분리(원격 REST) · 3 게이트웨이 · 4 Eureka ·
5 보안(RS256 JWT) · 6 중앙설정(Config+`{cipher}`) · 7 Docker Compose · 8 관측성(8a 트레이스+메트릭 / 8b 로그→Loki·RED·트레이스↔로그) ·
9a 비동기(Kafka) · 10 Outbox+멱등(신뢰성 척추) · 11 CQRS 읽기모델(Mongo) · 12 Saga 코레오그래피+보상 · 13 Saga 오케스트레이션 ·
**14 복원력**(Resilience4j 5종·게이트웨이 회로차단기+fallback·엣지 과부하 차단·DLQ/poison·outbox 격리·고아 결제 환불 보상).

> ⚠️ **Phase 12부터 주문 흐름은 `--profile async` 필수**(동기 결제 호출 제거 — 전부 Kafka 이벤트).
> `POST /orders` 는 **`PENDING` 즉시 반환**, 최종 상태(CONFIRMED/CANCELLED)는 **조회로 확인**(결과적 일관성).
- 각 Phase 심화문서 `docs/PHASE-*.md`(+`SERVICE-DISCOVERY.md`=P4, `SECURITY.md`=P5). 오프라인 HTML `docs/site/`(21p, 더블클릭). 커밋지도 `docs/PHASE-COMMIT-MAP.md`.

## 서비스 (15 컨테이너, `--profile async` 기준)
| 서비스 | 포트 | 프로파일 | 역할 |
|---|---|---|---|
| config-service | 8888 | base | Config Server(native+`{cipher}`) |
| discovery-service | 8761 | base | Eureka |
| auth-service | 9000 | base | RS256 JWT 발급/JWKS |
| gateway-service | 8000 | base | 단일 진입점(라우팅+엣지 JWT) |
| **order-service** | 8080 | base | 주문 접수 + **Saga 조정자**(orchestration 모드) / 상태기계 |
| payment-service | 8081 | base | 결제(합계 `.99`→거절). **커맨드/이벤트로만 동작** |
| inventory-service | 8082 | **async** | 재고 예약·해제(보상) + 예약 원장 |
| order-query-service | 8083 | **async** | CQRS 읽기모델(3토픽 투영 → Mongo) |
| order-query-mongo | 27017 | **async** | 읽기모델 저장소(`mongo:8`) |
| otel-lgtm | 3000(Grafana)/4317/4318 | base | 관측성(Tempo·Loki·Prometheus·Grafana) |
| kafka | 9092(host)/19092(내부) | **async** | `apache/kafka:4.3.1` KRaft |
| kafka-ui | 8090 | **async** | `ghcr.io/kafbat/kafka-ui` |
| order-db/payment-db/inventory-db | 5432/5433/5434(host) | base/base/async | postgres:18-alpine |

- 데모 계정: `alice/secret`(USER), `admin/admin123`(USER+ADMIN). 시드 상품 `22222222-…`/`33333333-…` 각 100개.

## Saga 두 가지 모드 (`saga.mode` 토글 — Phase 13의 학습 장치)
**상호배타**다(동시에 켜면 이중 처리). 리스너들이 `@ConditionalOnProperty` 로 게이팅돼 있다.

- **`orchestration`(기본, Phase 13)**: order가 **조정자**. `saga_instance` 테이블 + `SagaOrchestratorService` 의 switch 하나가 전체 흐름.
  토픽 `saga-commands`(지시) / `saga-replies`(결과, 단일 `SagaReply` 타입).
  `STARTED→AWAITING_INVENTORY→AWAITING_PAYMENT→COMPLETED` / 실패 시 `COMPENSATING_INVENTORY→CANCELLED`.
  **`SagaTimeoutSweeper`**(@Scheduled): 15s 무응답 → 재촉(최대 3회) → 포기(결제 단계면 **보상 전환** 후 종료).
  진행 확인 한 줄: `SELECT state FROM saga_instance WHERE order_id='…';`
- **`choreography`(Phase 12)**: 조정자 없음. 각 서비스가 남의 **사실 이벤트**를 듣고 스스로 반응.
  토픽 `order-events`/`inventory-events`/`payment-events`, `@KafkaHandler` 타입 분기.

공통 기반: **outbox**(발행 원자성·`traceparent` 복원 → Saga 한 트레이스) + **inbox**(`processed_messages` 멱등).
Phase 13은 여기에 **`processed_commands` + 결정적 커맨드 키**(`CommandKeys.of(sagaId,타입)`)를 추가 —
sweep 재전송 시 메시지 id가 바뀌므로 메시지 기반 dedup으론 이중 청구를 못 막기 때문. 중복이면 **저장된 결과로 리플라이 재전송**(무시하면 조정자가 영영 대기).

## git 상태 (중요)
- Phase 13 = `93d9a26`(미푸시). Phase 14는 **아직 커밋 전**(작업 트리에 있음) — 사용자가 요청할 때 커밋한다.
- **푸시는 사용자가 명시 요청할 때만.** 커밋도 사용자 요청 시(단계별 의미 커밋, `/commit` 스킬 사용).
- ⚠️ **`docs/PHASE-COMMIT-MAP.md` 13행은 `93d9a26` 으로 채움 완료. 14행이 placeholder** → **Phase 15 커밋 때 Phase 14 해시 기입.**
- gitignore: `deploy/compose/.env`, `docs/tools/node_modules/`, `**/build/`.

## 실행 / 검증 (재현)
```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
export ENCRYPT_KEY='shopsaga-dev-encrypt-key-0123456789ab'   # dev 전용, 운영 금지
./gradlew bootJar
docker compose -f deploy/compose/compose.yml --profile async up -d --build   # 15컨테이너
# 빌드/테스트(Docker 필요 — Testcontainers). 현재 61개 통과:
DOCKER_HOST=unix://$HOME/.colima/default/docker.sock TESTCONTAINERS_RYUK_DISABLED=true ./gradlew build
```
- 공개 포트: gateway :8000 · Eureka :8761 · Config :8888 · Grafana :3000 · kafka-ui :8090 · kafka :9092 · mongo :27017.
- **스모크**: 로그인(`POST :8000/auth/login`) → 주문(`POST :8000/orders` → **PENDING**) → 폴링(`GET :8000/orders/{id}` → CONFIRMED)
  · 읽기모델 `GET :8000/order-views?customerId=` · 재고 `GET :8000/inventory/{productId}`
  · Saga 상태 `docker exec shopsaga-order-db-1 psql -U order -d orderdb -c "SELECT * FROM saga_instance;"`
- **실패 시연**: 수량 9999(재고 부족→CANCELLED) / 합계 `*.99`(결제 거절→재고 원복+CANCELLED)
  / **payment 중단 후 주문**(타임아웃 sweep → 재촉 3회 → 보상 → CANCELLED, ~70초)
- **Phase 14 장애 주입**: `POST :8000/inventory/chaos?failRate=100` (Retry→회로 개방) / `?delayMs=3000` (TimeLimiter) / `DELETE` 로 해제
  · 회로 상태 `GET :8000/actuator/circuitbreakers`(토큰 필요) · order 쪽은 `/actuator/health` 의 `circuitBreakers`
  · poison pill: `echo 'NOT-JSON' | kafka-console-producer.sh --topic order-events` → `order-events.DLT` 로 이동, 파티션 안 막힘
  · **고아 결제 보상**: payment 정지 → 주문 → sweep 포기(~70s) → payment 재기동 → payment.status = **REFUNDED**
- ⚠️ **gateway는 config만 바뀌어도 재시작 필요**(라우트는 기동 시 로드).
- ⚠️ **Gradle 결과를 `| tail` 로 파이프하면 exit code가 tail 것** → `> /tmp/x.log 2>&1; echo $?` 로 확인할 것(실제로 오판했음).
- ⚠️ **`docker exec` 폴링 루프는 매우 느리다**(호출당 수~수십 초). 검증 루프는 호출 수를 최소화하고 한 번에 여러 값을 조회할 것.
- gradle/docker/git 명령은 `dangerouslyDisableSandbox: true`로 실행.

## 다음 단계 — Phase 15 (플랫폼 강화)
로드맵 `MSA-LEARNING-PLAN.md` §331~.
1. **Spring Cloud Bus**(Kafka 백엔드) — `spring-cloud-starter-bus-kafka` + `POST /actuator/busrefresh` 로 설정 broadcast.
2. **계약 테스트**(Spring Cloud Contract) — 프로듀서가 이벤트/API 계약 발행, 소비자가 스텁으로 검증.
3. **이벤트 스키마 진화** — JSON + tolerant reader("필드 추가만"), 깨는 변경을 일부러 실패로 시연.
   → **Phase 14 한계 #9**(깨지는 스키마 변경 = DLT 폭탄)가 여기서 해결된다.
4. (로드맵 순서: 15 강화 → 16 k8s → 17 CI/CD → 18 캡스톤.)

### Phase 14가 남긴 것(문서 §8에 전체 표)
- 엣지 한도가 **인스턴스 로컬**(분산 한도는 Redis 필요) · **타임아웃은 회로를 열지 못한다**(aspect 순서 선택의 결과)
- **DLT 재투입 도구 없음**, DLT lag 경보 없음 · outbox 격리 row 자동 복구 안 함
- `chaos` 엔드포인트는 학습 전용(`chaos.enabled`) — 운영 프로파일에서 제거해야 함

## 매 Phase 작업 흐름 (사용자 선호 — 지켜야 함)
1. 리서치(버전 특이점) → 2. 설계(큰 결정은 AskUserQuestion) → 3. 구현 →
4. **빌드/테스트/compose 실증**(트레이스 `docker exec shopsaga-otel-lgtm-1 curl localhost:3200/api/traces/<id>`, Kafka는 kafka-ui/CLI) →
5. **문서화**: `docs/PHASE-N-*.md`(구조 0요약→1왜→2개념→3구성→4코드→5흐름→6원리→7검증→8한계표→9용어→10참고)
   + README 인덱스 + **HTML 재생성**(`cd docs/tools && npm run build`, `build-docs.mjs`의 DOCS 배열에 한 줄 추가) + **PHASE-COMMIT-MAP 해시** →
6. **한계표**("이번 Phase 한계 → 해결 Phase") 제시 → 7. 커밋(요청 시).
- 초보자 친화 문서(용어 첫 등장 시 정의, why→how). 커밋 메시지: 한국어 Conventional Commits + `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.
- **발견한 결함은 감추지 말고 문서 §7/§8에 실측과 함께 남긴다**(Phase 13 고아 결제가 그 예).

## 자주 물리는 함정 (기억)
- 좀비 JVM 포트 점유: `lsof -nP -iTCP:<port> -sTCP:LISTEN -t | xargs kill`.
- bash cwd가 리셋될 수 있음 → gradle/compose는 **절대경로** 권장.
- 커스텀 빌더/템플릿은 관측 계측이 빠질 수 있음(Phase 8: RestClient에 `ObservationRegistry`, Kafka는 `observation-enabled` **template+listener 둘 다**).
- 단일노드 Kafka: 내부토픽 `replication-factor=1`, `NewTopic` `replicas(1)`, auto-create off(토픽은 각 서비스가 **자기가 발행하는 것만** 선언).
- **`@EntityScan`/`@EnableJpaRepositories` 는 Boot 기본 스캔을 대체**한다 → 공유 라이브러리를 쓰면 각 앱에서 **자기 패키지 + `com.shopsaga.outbox`** 를 **둘 다** 명시.
  또한 그 패키지의 엔티티는 **전부** 스캔되므로 안 쓰는 테이블도 마이그레이션이 필요하다(Phase 13에서 order에 `processed_commands` 누락 → 크래시 루프).
- **`spring.json.trusted.packages` 는 하위 패키지 미포함**: `com.shopsaga.events` 만 적으면 `…events.commands.*` 역직렬화가 거부돼 소비가 전부 막힌다(Phase 13에서 겪음).
- **Apple Silicon + mongo**: Docker가 `mongo:8` **amd64 변이**를 당겨오면 AVX 부재로 mongod가 안 뜬다(컨테이너는 running·포트 미개방).
  확인 `docker image inspect mongo:8 --format '{{.Architecture}}'`=arm64. 해결 `docker rmi mongo:8 && docker pull --platform linux/arm64 mongo:8`.
- **Colima 디스크 포화가 DB 오류로 위장**(Phase 13에서 실제 발생): `/var/lib/docker` 93% → Mongo `__log_fs_write: fatal log failure`,
  Postgres `Consistent recovery state has not been yet reached`(복구 루프). **먼저 `colima ssh -- df -h /var/lib/docker` 확인**.
  해결 `down -v` + `docker system prune -af --volumes`(이미지를 전부 다시 받으므로 재빌드에 수 분).
- 서비스를 `docker stop/start` 로 되살린 직후엔 Eureka 재등록 전이라 게이트웨이가 잠시 **503**(~30~40초) → 재시도하면 정상.
- **Resilience4j 버전은 Spring Cloud BOM(2025.0.3)이 관리하는 `2.2.0` 으로 고정**해야 한다. 2.3.0 으로 올리면
  BOM이 전이 의존성만 되돌려 `spring-boot3:2.3.0 + spring6:2.2.0` 혼합이 되고 `NoClassDefFoundError(RxJava3FallbackDecorator)` 로 기동 실패.
- **`resilience4j.bulkhead.bulkheadAspectOrder` 는 존재하지 않는다**(getter만 있음). 넣으면 `No setter found for property` 로 기동 실패.
  aspect order 는 **값이 작을수록 바깥**이다(문서의 "higher = higher priority" 표현에 속지 말 것). 기본값은 MAX-5(Retry)~MAX-1(Bulkhead).
- **HTTP 헤더에 한글을 넣으면 헤더가 통째로 사라진다**(ISO-8859-1). `X-Stock-Precheck` 값은 ASCII 로만.
- **`@Bean KafkaTemplate` 을 선언하면 Boot 자동설정 템플릿이 사라진다**(`@ConditionalOnMissingBean`) → 다른 곳 주입이 깨진다.
- **`List<NewTopic>` 빈은 KafkaAdmin 이 수집하지 않는다** → 여러 토픽은 `KafkaAdmin.NewTopics` 로.
- **Spring AOP 는 자기 자신 호출(self-invocation)에 적용되지 않는다** → 복원력 애너테이션이 조용히 무시된다. 빈을 분리할 것.
- outbox 격리 재현은 오래 걸린다(실패 1회당 `max.block.ms` 5초 + send 타임아웃 5초 × 상한 5회).
