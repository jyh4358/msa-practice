# Phase ↔ 커밋 매핑 (git 이력으로 단계별 코드 변화 보기)

> 이 문서는 **"Phase 순서대로 코드가 어떻게 바뀌었나"를 git으로 되짚기 위한 지도**입니다.
> 각 Phase가 어느 커밋(들)에 있는지, 무엇을 봐야 하는지, 어떤 git 명령을 쓰는지 정리합니다.
> **새 Phase를 커밋할 때마다 이 표에 한 줄 추가**합니다(맨 아래 유지보수 규칙 참고).

원격: `github.com/jyh4358/msa-practice` · 기본 브랜치 `main`.
커밋 해시는 히스토리를 재작성하지 않는 한 **불변**이므로 이 지도는 안정적입니다.

> ⚠️ **2026-08-02, 이력을 한 번 재작성했습니다.** 커밋 author/committer 이메일에 오타(`gamil.com`)가
> 있어 GitHub 이 커밋을 계정에 연결하지 못했고(기여 그래프 미집계), `git filter-repo --mailmap` 으로
> 31개 커밋의 이메일을 고쳤습니다. **그 결과 이 표의 해시가 전부 새 값으로 바뀌었습니다**
> (내용·날짜·메시지는 그대로). 이전 해시로 적힌 외부 메모가 있다면 더 이상 찾을 수 없습니다.
> 재작성 직전 이력은 로컬 번들로 백업해 두었다가, CI 통과와 기여 그래프 연결(`✓ jyh4358`)을
> 확인한 뒤 삭제했습니다. **옛 해시로는 아무것도 복원할 수 없습니다.**

---

## 매핑 표 (오래된 → 최신)

| Phase | 커밋(들) | 한 줄 | 핵심 변경(무엇을 볼까) | 심화 문서 |
|---|---|---|---|---|
| **0** 스캐폴드 | `374dc47` | 모노레포 최초 커밋 | `settings.gradle.kts`·`gradle/libs.versions.toml`·`gradlew`·`deploy/compose/compose.infra.yml`·`docs/{HEXAGONAL,SETUP}.md`·`MSA-LEARNING-PLAN.md` | [PHASE-0-SCAFFOLD](PHASE-0-SCAFFOLD.md) |
| **1** 모놀리스(ACID) | `374dc47` → `3c76c63` → `c34f1be` → `b543669` | 주문+재고+결제 단일 트랜잭션, 헥사고날·비관적 락·QueryDSL·출력모델·Lombok | order-service `domain/`·`application/`·`adapter/`(특히 `c34f1be`이 비관적 락·QueryDSL·`*Query*Repository`·`QuerydslConfig`·출력모델 리팩터). `3c76c63`은 gitignore `out/` 버그로 누락됐던 `adapter/out`·`port/out` 추적 | [PHASE-1-MONOLITH](PHASE-1-MONOLITH.md) |
| **2-1** 결제 분리 | `6bc9f2b` | payment-service 신설(아직 미연결) | `services/payment-service/**` 전체 신규, `compose.infra.yml`에 payment-db 추가 | [PHASE-2-SPLIT-PAYMENT](PHASE-2-SPLIT-PAYMENT.md) |
| **2-2** 원격 결제 | `72bc785` | order→payment 동기 REST, 로컬 결제 제거 | order-service `adapter/out/payment/*`(RestClient) 추가, `domain/Payment.java`·`PaymentJpaEntity` 삭제, `V3__remove_local_payment.sql`, `OrderService` | 〃 |
| **3** 게이트웨이 | `a7ed2fb` | Spring Cloud Gateway 단일 진입점 | `services/gateway-service/**` 신규, `settings.gradle.kts` | [PHASE-3-GATEWAY](PHASE-3-GATEWAY.md) |
| **4** 디스커버리 | `0c34e24` | Eureka, 이름 기반 라우팅·호출 | `services/discovery-service/**` 신규, gateway 라우트 `lb://`, order `PaymentClientConfig`(@LoadBalanced), 각 `application.yml`에 eureka | [SERVICE-DISCOVERY](SERVICE-DISCOVERY.md) |
| **5** 보안 | `19fad3a` | RS256 JWT 인증·역할 인가·토큰 전파 | `services/auth-service/**` 신규, gateway·order·payment `SecurityConfig`, order `BearerTokenRelayInterceptor`, `OrderController` @PreAuthorize | [SECURITY](SECURITY.md) |
| _(docs)_ | `52bd050` | Phase 0~3 심화 문서 백필 + 초보자 개선 + README | `docs/PHASE-0~3-*.md` 신규, `SERVICE-DISCOVERY`·`SECURITY` 개선, `README` | — |
| **6** 중앙 설정 | `0ba2477` | Config Server(native) + 시크릿 암호화 | `services/config-service/**` 신규, `config-repo/*.yml`({cipher} 포함), 각 클라이언트 `application.yml` 스트립 + `spring.config.import` | [PHASE-6-CONFIG](PHASE-6-CONFIG.md) |
| **7** compose | `7d964b1` | Docker Compose 전체 스택 | `deploy/docker/Dockerfile.service`, `deploy/compose/compose.yml`·`.env.example`, `config-repo/*-docker.yml` | [PHASE-7-COMPOSE](PHASE-7-COMPOSE.md) |
| _(docs)_ | `08e2757` | Phase↔커밋 지도 + 파트 A 복습 | `PHASE-COMMIT-MAP`·`REVIEW-PART-A` 신규, README 링크 | — |
| _(docs)_ | `b0c8dbf` | 오프라인 HTML 문서 사이트 | `docs/tools/build-docs.mjs`·`docs/site/**` 신규, 원본 마크다운 렌더 결함 6수정 | — |
| **8a** 관측성 | `b8b6ffe` | 분산 트레이싱·메트릭(OTLP→otel-lgtm) | 4서비스 tracing 의존성, `config-repo` tracing/otlp, `compose` otel-lgtm, `PaymentClientConfig` ObservationRegistry | [PHASE-8](PHASE-8-OBSERVABILITY.md) |
| **8b** 관측성 심화 | `e9a0ed1` | 로그→Loki·RED 대시보드·트레이스↔로그 점프 | logback appender×4 + `OpenTelemetryAppenderInstaller`×4 + `logback-spring.xml`×4, `config-repo` otlp.logging/percentiles-histogram, `deploy/grafana/**` 대시보드, `OrderService` @Slf4j 로그 | 〃 |
| **9a** 비동기(Kafka) | `3e9111f` | 재고 분리·OrderPlaced 이벤트·HTTP→Kafka 트레이스·리플레이 | `services/inventory-service/**` 신규, `shared/events/**`(OrderPlacedEvent), order `OrderService`(재고 제거+발행)·`PublishOrderEventPort`·Kafka 어댑터·`V4__drop_local_stock`, `config-repo` kafka, compose kafka·kafka-ui·inventory-db·inventory-service(`--profile async`) | [PHASE-9](PHASE-9-ASYNC-KAFKA.md) |
| **10** Outbox+멱등 | `ce13c9e` | 트랜잭셔널 Outbox·@Scheduled 릴레이·멱등 소비자(effectively-once)·이중 쓰기 제거 | order `adapter/out/outbox/**`(엔티티·리포·발행어댑터·릴레이)·`V5__outbox`·`@EnableScheduling`, `OrderEventKafkaAdapter` 삭제, inventory `processed_messages`(엔티티·리포·`ProcessedMessagePort`·어댑터)·`V2__processed_messages`·`StockService` 멱등 가드·리스너 `messageId` 헤더, `config-repo` outbox.relay | [PHASE-10](PHASE-10-OUTBOX.md) |
| **11** CQRS 읽기모델 | `dd8695c` | 이벤트 투영→MongoDB 비정규화 읽기 모델·조회 전용 서비스·투영 결정성·리플레이 재구축 | `services/order-query-service/**` 신규(투영기·Mongo 어댑터·조회 API·`MongoConfig` Decimal128), `shared/events` `OrderPlacedEvent`에 `totalAmount`·`occurredAt` 추가, order `OrderService` 발행부 확장, `config-repo` order-query-service(+docker)·gateway `/order-views` 라우트, compose `mongo:8`·order-query-service(`--profile async`) | [PHASE-11](PHASE-11-CQRS.md) |
| **12** Saga(코레오그래피) | `b4d86b5` | 동기 결제 제거·주문 상태기계·보상(재고 해제)·outbox traceparent 복원→Saga 한 트레이스 | `shared/outbox/**` 신규(outbox·inbox 메커니즘 공유 라이브러리 + 트레이스 복원), `shared/events` 이벤트 5종+`Topics`, order(상태기계 `Order`·`OrderSagaService`·리스너 2종·`V6__processed_messages`, **동기 결제 어댑터 삭제**), inventory(`StockSagaTransactions`·보상 `release`·예약 원장 `V3`·리스너 2종), payment(`PaymentSagaService`·리스너·`V2__outbox_and_inbox`), order-query(단조 상태 전이 `OrderViewStatus`·3토픽 구독), compose `required:false` kafka 의존 | [PHASE-12](PHASE-12-SAGA.md) |
| **13** Saga(오케스트레이션) | `5f834cf` | 중앙 조정자·saga_instance·타임아웃 sweep·결정적 커맨드 키·모드 토글 | `shared/events/commands/**`(커맨드 3종+`SagaReply`), `shared/outbox`(`ProcessedCommand`·`CommandKeys`), order(`domain/saga/**` 상태기계·`SagaOrchestratorService` switch·**`SagaTimeoutSweeper`**·`V7__saga_instance`+부분인덱스·`SagaReplyListener`·커맨드 발행 어댑터), inventory/payment(`SagaCommandListener`·커맨드 서비스·리플라이 발행·`processed_commands` 마이그레이션), 코레오그래피 리스너 `@ConditionalOnProperty` 게이팅, `config-repo` `saga.mode`·`saga.timeout` | [PHASE-13](PHASE-13-SAGA-ORCHESTRATION.md) |

| **14** 복원력 패턴 | `5dd24a8` | Resilience4j 5종·게이트웨이 회로차단기+fallback·엣지 RateLimiter/Bulkhead·DLQ(poison→`.DLT`)·outbox 격리·**고아 결제 환불 보상** | `shared/messaging/**` 신규(`KafkaErrorHandlingConfiguration`: DefaultErrorHandler+DLPR+DLT 토픽), `shared/outbox`(`attempts` 상한 폴링·`outbox.stuck` 게이지·`OutboxRelayTest`), `shared/events/commands/RefundPaymentCommand`+`SagaReply.Kind.PAYMENT_REFUNDED`, order(`adapter/out/rest/**` 재고 사전 확인 5종 애너테이션·`StockPrecheck`·`PlaceOrderResult`·`SagaOrchestratorService#compensateOrphanPayment`), payment(`onRefundPayment`·`PaymentStatus.REFUNDED`·`V4__payment_refund`), gateway(`resilience/**` 회로차단기 Customizer·fallback·엣지 throttle 필터), inventory(`ChaosSwitch`·`ChaosController`), `config-repo`(ErrorHandlingDeserializer·`messaging.dlq`·`resilience4j.*` aspect order·라우트 CircuitBreaker 필터) | [PHASE-14](PHASE-14-RESILIENCE.md) |

| **15** 플랫폼 강화 | `1d504a0` | Spring Cloud Bus 설정 방송 · 계약 테스트(동기 API + 이벤트) · 이벤트 스키마 진화 | `config-repo/application.yml`(spring.cloud.stream 바인더 byte[] 직렬화·bus), 각 서비스 `spring-cloud-starter-bus-kafka`+actuator `refresh,busrefresh`, order(`StockPrecheckProperties` @ConfigurationProperties 전환·`contracts/messaging/order_placed.yml`·`MessagingBase` 커스텀 MessageVerifier·`InventoryContractConsumerTest`·`InventoryStockClient` Integer 검증), inventory(contract 플러그인·`contracts/rest/*.yml`·`RestBase`·stub jar 발행), order-query(`EventSchemaEvolutionTest`), `gradle/libs.versions.toml`(springCloudContract 4.3.4 + 플러그인 alias) | [PHASE-15](PHASE-15-CONTRACTS.md) |
| **16a** 로컬 k8s(kind) | `2116b6f` | kind 클러스터 · order-service 이전 · ConfigMap/Secret · liveness≠readiness probe · NodePort · 자가치유/스케일 | `deploy/k8s/**` 신규(`kind-cluster.yaml`·`build-and-load.sh`·`00`~`40` 매니페스트·README), 6개 서비스 `SecurityConfig`(probe 하위경로 `/actuator/health/**` permitAll) | [PHASE-16](PHASE-16-KUBERNETES.md) |
| **16b** 전체 플랫폼 on k8s | `68f2813` | **Eureka·Config Server 삭제** · 설정 3층화(jar·라이브러리·ConfigMap) · compose 동반 이전(15→13) · Ingress · auth 복제본 2 | `services/discovery-service`·`services/config-service`·`config-repo/` **삭제**, `shared/messaging/…/shopsaga-messaging-defaults.yml` 신규, `deploy/config/**` 신규, `deploy/k8s/{21~25,32~35,50-ingress,apply.sh}` 신규, 6개 서비스 `application.yml` 재작성, `InventoryRestConfig`(@LoadBalanced 제거)·`RsaKeyConfig`(키를 Secret 으로), `compose.yml` 재작성, gateway 테스트 사본 삭제+회귀 가드 | [PHASE-16](PHASE-16-KUBERNETES.md) |
| **17** CI/CD | `e6cdaa8` | GitHub Actions 5잡: 빌드·테스트 → 이미지(amd64·arm64 네이티브) → 매니페스트 병합 → kind 스모크 배포. GHCR `:커밋SHA` 로 추적성 확보 | `.github/workflows/ci.yml` 신규, `README.md`(CI 배지) | [PHASE-17](PHASE-17-CICD.md) |
| **18** 선언적 배포 | `72ab3f7` | Kustomize `base/`+`overlays/{local,ci}` · **생성기 해시로 설정 변경 → 자동 롤아웃**(`--config` 삭제) · CI 의 `sed` → `kustomize edit set image` · ingress-nginx 를 **Helm 릴리스**로 | `deploy/k8s/*.yaml` 15개 → `deploy/k8s/base/`(git mv, 숫자 접두사 제거·`namespace:` 34곳 삭제), `deploy/config/kustomization.yaml` 신규(로드 제한 우회 + compose 와 파일 공유 유지), `deploy/k8s/base/kustomization.yaml` 신규(namespace 변환기·공통 라벨·`secretGenerator`), `deploy/k8s/overlays/{local,ci}/` 신규, `deploy/k8s/ingress-nginx-values.yaml` 신규, `apply.sh` 축소, `kind-cluster.yaml`(ingress-ready 노드 라벨), `.github/workflows/ci.yml`(kustomize 5.8.1 설치·`apply.sh ci`), `.gitignore`(`base/.secrets/`) | [PHASE-18](PHASE-18-KUSTOMIZE.md) |
| **19** GitOps | `894e12d` | Argo CD 가 Git 을 보고 스스로 배포 · CI→Git 승격 루프 · `selfHeal`(6초 복원)·`prune`(Phase 18 한계 #2 해결) | `deploy/k8s/overlays/gitops/` 신규(Argo CD 가 추적하는 유일한 경로 — CI 봇이 이미지 태그를 여기에 커밋), `deploy/k8s/argocd-values.yaml`(차트 10.2.2/v3.4.6, 파드 7→4, UI 는 port-forward)·`argocd-application.yaml`(automated sync+prune+selfHeal)·`install-argocd.sh` 신규, `.github/workflows/ci.yml`(**gitops 승격 잡** 추가 + `paths-ignore` 로 봇 커밋 무한루프 방지), `base/kustomization.yaml`(**secretGenerator 제거** — Argo CD 는 Git 을 클론해 렌더하므로 gitignore 된 개인키를 못 읽는다), `bootstrap-secrets.sh`(kubectl 로 Secret 주입하는 클러스터 사전 조건으로 전환), `apply.sh`(Argo CD 관리 중이면 경고) | [PHASE-19](PHASE-19-GITOPS.md) |

> _(각 Phase 행의 해시는 그 Phase의 **기능 커밋**이다. 문서·HTML 갱신분은 대개 바로 뒤 커밋에 들어간다 —
> 커밋 해시는 자기 자신을 담을 수 없어 이 표의 행은 항상 다음 커밋에서 기입되기 때문이다.)_

---

## 알아둘 애매 지점 (git만 보면 헷갈리는 곳)

1. **Phase 0과 1은 최초 커밋 `374dc47`에 함께** 들어 있다(프로젝트 시작 시 스캐폴드+모놀리스를 한 번에 커밋). "0=빌드·설정·인프라 파일, 1=order-service 코드"로 구분해서 보면 된다.
2. **Phase 1은 4개 커밋에 걸쳐** 성숙했다: 최초(`374dc47`) → 누락 패키지 추적(`3c76c63`, `.gitignore`의 `out/`가 헥사고날 `adapter/out`을 잘못 무시한 버그 수정) → **리팩터(`c34f1be`)**(여기서 비관적 락·QueryDSL·출력모델·Lombok) → 문서(`b543669`).
3. **Phase 2는 2-1(`6bc9f2b`)·2-2(`72bc785`) 두 커밋**으로 나뉜다.
4. **docs 커밋 `52bd050`가 Phase 5와 6 사이에 끼어** 있다(Phase 7 작업 중 0~3 문서를 몰아서 백필했기 때문). Phase 순서 ≠ 커밋 시간순인 유일한 지점.
5. Phase 3~7은 **1커밋 = 1Phase**로 깔끔하다.

---

## 새 세션에서 이렇게 보면 됩니다 (git 명령)

```bash
# (2026-08-02 이력 재작성 이후 해시 — 위 경고 참고)
# 0) 전체 흐름
git log --oneline --reverse

# 1) 특정 Phase가 무엇을 바꿨나 (해당 커밋의 전체 diff)
git show a7ed2fb                 # Phase 3
git show 72bc785 --stat          # Phase 2-2 변경 파일 목록만

# 2) Phase A → B 사이의 누적 변화 (경계 커밋 사용)
git diff 72bc785..a7ed2fb        # Phase 2-2 → Phase 3
git diff 0c34e24..19fad3a        # Phase 4 → Phase 5

# 3) 특정 파일이 Phase마다 어떻게 바뀌었나
git log --oneline -- services/order-service/src/main/resources/application.yml
git log -p  -- services/gateway-service/src/main/resources/application.yml

# 4) 한 Phase에서 추가/삭제/수정된 파일 분류
git show 19fad3a --name-status   # A/D/M (Phase 5)
```

> 팁: 각 커밋 메시지는 **`(Phase N)` 태그**가 있어(2~7) `git log --oneline | grep Phase` 로도 빠르게 짚을 수 있다.

---

## 유지보수 규칙 (중요)

**새 Phase를 커밋할 때마다** 이 문서를 갱신한다:
1. 위 매핑 표에 `| Phase N | <새 커밋 해시> | 한 줄 | 핵심 변경 파일 | 심화 문서 |` 한 줄 추가.
2. 1커밋=1Phase가 아니면(분할/끼어든 docs 커밋 등) "애매 지점"에 주석.
3. 커밋 해시는 `git log --oneline`으로 확인해 **정확히** 기입(오타 주의).

*관련: [README](../README.md), [REVIEW-PART-A](REVIEW-PART-A.md), 각 `PHASE-*` 문서. 로드맵: `MSA-LEARNING-PLAN.md`.*
