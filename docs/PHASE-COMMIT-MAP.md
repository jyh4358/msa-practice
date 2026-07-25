# Phase ↔ 커밋 매핑 (git 이력으로 단계별 코드 변화 보기)

> 이 문서는 **"Phase 순서대로 코드가 어떻게 바뀌었나"를 git으로 되짚기 위한 지도**입니다.
> 각 Phase가 어느 커밋(들)에 있는지, 무엇을 봐야 하는지, 어떤 git 명령을 쓰는지 정리합니다.
> **새 Phase를 커밋할 때마다 이 표에 한 줄 추가**합니다(맨 아래 유지보수 규칙 참고).

원격: `github.com/jyh4358/msa-practice` · 기본 브랜치 `main`.
커밋 해시는 히스토리를 재작성하지 않는 한 **불변**이므로 이 지도는 안정적입니다.

---

## 매핑 표 (오래된 → 최신)

| Phase | 커밋(들) | 한 줄 | 핵심 변경(무엇을 볼까) | 심화 문서 |
|---|---|---|---|---|
| **0** 스캐폴드 | `fcaa736` | 모노레포 최초 커밋 | `settings.gradle.kts`·`gradle/libs.versions.toml`·`gradlew`·`deploy/compose/compose.infra.yml`·`docs/{HEXAGONAL,SETUP}.md`·`MSA-LEARNING-PLAN.md` | [PHASE-0-SCAFFOLD](PHASE-0-SCAFFOLD.md) |
| **1** 모놀리스(ACID) | `fcaa736` → `bb2ee23` → `db42211` → `361e05f` | 주문+재고+결제 단일 트랜잭션, 헥사고날·비관적 락·QueryDSL·출력모델·Lombok | order-service `domain/`·`application/`·`adapter/`(특히 `db42211`이 비관적 락·QueryDSL·`*Query*Repository`·`QuerydslConfig`·출력모델 리팩터). `bb2ee23`은 gitignore `out/` 버그로 누락됐던 `adapter/out`·`port/out` 추적 | [PHASE-1-MONOLITH](PHASE-1-MONOLITH.md) |
| **2-1** 결제 분리 | `fb27790` | payment-service 신설(아직 미연결) | `services/payment-service/**` 전체 신규, `compose.infra.yml`에 payment-db 추가 | [PHASE-2-SPLIT-PAYMENT](PHASE-2-SPLIT-PAYMENT.md) |
| **2-2** 원격 결제 | `a9a1cda` | order→payment 동기 REST, 로컬 결제 제거 | order-service `adapter/out/payment/*`(RestClient) 추가, `domain/Payment.java`·`PaymentJpaEntity` 삭제, `V3__remove_local_payment.sql`, `OrderService` | 〃 |
| **3** 게이트웨이 | `41392a2` | Spring Cloud Gateway 단일 진입점 | `services/gateway-service/**` 신규, `settings.gradle.kts` | [PHASE-3-GATEWAY](PHASE-3-GATEWAY.md) |
| **4** 디스커버리 | `91e27d6` | Eureka, 이름 기반 라우팅·호출 | `services/discovery-service/**` 신규, gateway 라우트 `lb://`, order `PaymentClientConfig`(@LoadBalanced), 각 `application.yml`에 eureka | [SERVICE-DISCOVERY](SERVICE-DISCOVERY.md) |
| **5** 보안 | `98003e7` | RS256 JWT 인증·역할 인가·토큰 전파 | `services/auth-service/**` 신규, gateway·order·payment `SecurityConfig`, order `BearerTokenRelayInterceptor`, `OrderController` @PreAuthorize | [SECURITY](SECURITY.md) |
| _(docs)_ | `f38b23e` | Phase 0~3 심화 문서 백필 + 초보자 개선 + README | `docs/PHASE-0~3-*.md` 신규, `SERVICE-DISCOVERY`·`SECURITY` 개선, `README` | — |
| **6** 중앙 설정 | `de61515` | Config Server(native) + 시크릿 암호화 | `services/config-service/**` 신규, `config-repo/*.yml`({cipher} 포함), 각 클라이언트 `application.yml` 스트립 + `spring.config.import` | [PHASE-6-CONFIG](PHASE-6-CONFIG.md) |
| **7** compose | `68249f6` | Docker Compose 전체 스택 | `deploy/docker/Dockerfile.service`, `deploy/compose/compose.yml`·`.env.example`, `config-repo/*-docker.yml` | [PHASE-7-COMPOSE](PHASE-7-COMPOSE.md) |
| _(docs)_ | `8df41ab` | Phase↔커밋 지도 + 파트 A 복습 | `PHASE-COMMIT-MAP`·`REVIEW-PART-A` 신규, README 링크 | — |
| _(docs)_ | `cbb96f8` | 오프라인 HTML 문서 사이트 | `docs/tools/build-docs.mjs`·`docs/site/**` 신규, 원본 마크다운 렌더 결함 6수정 | — |
| **8a** 관측성 | `7e352d8` | 분산 트레이싱·메트릭(OTLP→otel-lgtm) | 4서비스 tracing 의존성, `config-repo` tracing/otlp, `compose` otel-lgtm, `PaymentClientConfig` ObservationRegistry | [PHASE-8](PHASE-8-OBSERVABILITY.md) |
| **8b** 관측성 심화 | `a0f0b1f` | 로그→Loki·RED 대시보드·트레이스↔로그 점프 | logback appender×4 + `OpenTelemetryAppenderInstaller`×4 + `logback-spring.xml`×4, `config-repo` otlp.logging/percentiles-histogram, `deploy/grafana/**` 대시보드, `OrderService` @Slf4j 로그 | 〃 |
| **9a** 비동기(Kafka) | `a0d04f6` | 재고 분리·OrderPlaced 이벤트·HTTP→Kafka 트레이스·리플레이 | `services/inventory-service/**` 신규, `shared/events/**`(OrderPlacedEvent), order `OrderService`(재고 제거+발행)·`PublishOrderEventPort`·Kafka 어댑터·`V4__drop_local_stock`, `config-repo` kafka, compose kafka·kafka-ui·inventory-db·inventory-service(`--profile async`) | [PHASE-9](PHASE-9-ASYNC-KAFKA.md) |
| **10** Outbox+멱등 | `4c7ac02` | 트랜잭셔널 Outbox·@Scheduled 릴레이·멱등 소비자(effectively-once)·이중 쓰기 제거 | order `adapter/out/outbox/**`(엔티티·리포·발행어댑터·릴레이)·`V5__outbox`·`@EnableScheduling`, `OrderEventKafkaAdapter` 삭제, inventory `processed_messages`(엔티티·리포·`ProcessedMessagePort`·어댑터)·`V2__processed_messages`·`StockService` 멱등 가드·리스너 `messageId` 헤더, `config-repo` outbox.relay | [PHASE-10](PHASE-10-OUTBOX.md) |
| **11** CQRS 읽기모델 | _(이 커밋 — 다음 갱신 시 기입)_ | 이벤트 투영→MongoDB 비정규화 읽기 모델·조회 전용 서비스·투영 결정성·리플레이 재구축 | `services/order-query-service/**` 신규(투영기·Mongo 어댑터·조회 API·`MongoConfig` Decimal128), `shared/events` `OrderPlacedEvent`에 `totalAmount`·`occurredAt` 추가, order `OrderService` 발행부 확장, `config-repo` order-query-service(+docker)·gateway `/order-views` 라우트, compose `mongo:8`·order-query-service(`--profile async`) | [PHASE-11](PHASE-11-CQRS.md) |

> _(11 커밋 해시는 자기 자신을 담을 수 없어 다음 커밋에서 기입.)_

---

## 알아둘 애매 지점 (git만 보면 헷갈리는 곳)

1. **Phase 0과 1은 최초 커밋 `fcaa736`에 함께** 들어 있다(프로젝트 시작 시 스캐폴드+모놀리스를 한 번에 커밋). "0=빌드·설정·인프라 파일, 1=order-service 코드"로 구분해서 보면 된다.
2. **Phase 1은 4개 커밋에 걸쳐** 성숙했다: 최초(`fcaa736`) → 누락 패키지 추적(`bb2ee23`, `.gitignore`의 `out/`가 헥사고날 `adapter/out`을 잘못 무시한 버그 수정) → **리팩터(`db42211`)**(여기서 비관적 락·QueryDSL·출력모델·Lombok) → 문서(`361e05f`).
3. **Phase 2는 2-1(`fb27790`)·2-2(`a9a1cda`) 두 커밋**으로 나뉜다.
4. **docs 커밋 `f38b23e`가 Phase 5와 6 사이에 끼어** 있다(Phase 7 작업 중 0~3 문서를 몰아서 백필했기 때문). Phase 순서 ≠ 커밋 시간순인 유일한 지점.
5. Phase 3~7은 **1커밋 = 1Phase**로 깔끔하다.

---

## 새 세션에서 이렇게 보면 됩니다 (git 명령)

```bash
# 0) 전체 흐름
git log --oneline --reverse

# 1) 특정 Phase가 무엇을 바꿨나 (해당 커밋의 전체 diff)
git show 41392a2                 # Phase 3
git show a9a1cda --stat          # Phase 2-2 변경 파일 목록만

# 2) Phase A → B 사이의 누적 변화 (경계 커밋 사용)
git diff a9a1cda..41392a2        # Phase 2-2 → Phase 3
git diff 91e27d6..98003e7        # Phase 4 → Phase 5

# 3) 특정 파일이 Phase마다 어떻게 바뀌었나
git log --oneline -- services/order-service/src/main/resources/application.yml
git log -p  -- services/gateway-service/src/main/resources/application.yml

# 4) 한 Phase에서 추가/삭제/수정된 파일 분류
git show 98003e7 --name-status   # A/D/M
```

> 팁: 각 커밋 메시지는 **`(Phase N)` 태그**가 있어(2~7) `git log --oneline | grep Phase` 로도 빠르게 짚을 수 있다.

---

## 유지보수 규칙 (중요)

**새 Phase를 커밋할 때마다** 이 문서를 갱신한다:
1. 위 매핑 표에 `| Phase N | <새 커밋 해시> | 한 줄 | 핵심 변경 파일 | 심화 문서 |` 한 줄 추가.
2. 1커밋=1Phase가 아니면(분할/끼어든 docs 커밋 등) "애매 지점"에 주석.
3. 커밋 해시는 `git log --oneline`으로 확인해 **정확히** 기입(오타 주의).

*관련: [README](../README.md), [REVIEW-PART-A](REVIEW-PART-A.md), 각 `PHASE-*` 문서. 로드맵: `MSA-LEARNING-PLAN.md`.*
