# ShopSaga MSA — 세션 핸드오프 (2026-08-01, Phase 17 완료 시점)

> 새 세션에서 이 파일 + 프로젝트 메모리를 먼저 읽고 이어가세요. **가장 완전한 상태는 프로젝트 메모리**
> `~/.claude/projects/-Users-younho-IdeaProjects-msa/memory/msa-learning-project.md` 에 있습니다.
> 이 파일은 "지금 어디까지 왔고, 바로 다음에 뭘 하나"의 빠른 지도입니다.

## 이 프로젝트가 뭔가
- **ShopSaga**: Spring Cloud MSA를 **Phase별로 직접 만들며 트레이드오프를 배우는** 학습 프로젝트(제품화 아님).
- 사용자: Java/Spring 개발자(GitHub `jyh4358`, 원격 `github.com/jyh4358/msa-practice`). 한국어로 대화.
- 스택(고정): Java 21 · Spring Boot **3.5.15** · Spring Cloud **2025.0.3** · Gradle 멀티모듈 모노레포(버전 카탈로그 `gradle/libs.versions.toml`).
- 아키텍처: **헥사고날**(domain / application(port.in·out) / adapter(in.web·in.event·out.persistence·out.messaging)). 상세 `docs/HEXAGONAL.md`.
- 컨테이너 런타임: **Colima**(arm64). `DOCKER_HOST=unix://$HOME/.colima/default/docker.sock`.

## 지금까지 완료 (Phase 0~17)
0 스캐폴드 · 1 모놀리스(ACID·비관적락·QueryDSL) · 2 payment 분리(원격 REST) · 3 게이트웨이 · 4 Eureka ·
5 보안(RS256 JWT) · 6 중앙설정(Config+`{cipher}`) · 7 Docker Compose · 8 관측성(8a 트레이스+메트릭 / 8b 로그→Loki·RED·트레이스↔로그) ·
9a 비동기(Kafka) · 10 Outbox+멱등(신뢰성 척추) · 11 CQRS 읽기모델(Mongo) · 12 Saga 코레오그래피+보상 · 13 Saga 오케스트레이션 ·
14 복원력(Resilience4j 5종·회로차단기+fallback·DLQ/poison·outbox 격리·고아 결제 보상) ·
15 플랫폼 강화(Spring Cloud Bus 설정 방송·계약 테스트(동기 API+이벤트)·스키마 진화 tolerant reader) ·
**16a 로컬 k8s(kind)**(클러스터+order-service 이전·ConfigMap/Secret·liveness≠readiness probe·NodePort·자가치유·스케일) ·
**16b 전체 플랫폼 on k8s**(**Eureka·Config Server 삭제** → 플랫폼 DNS+ConfigMap·Ingress·compose 동반 이전(15→13)·무중단 롤링·auth 복제본 2) ·
**17 CI/CD**(GitHub Actions 5잡: 빌드·테스트 → 네이티브 러너 2개 멀티아치 이미지 → GHCR `:커밋SHA` → **CI 안 kind 스모크 배포**).

> ⚠️ **Phase 12부터 주문 흐름은 `--profile async` 필수**(동기 결제 호출 제거 — 전부 Kafka 이벤트).
> `POST /orders` 는 **`PENDING` 즉시 반환**, 최종 상태(CONFIRMED/CANCELLED)는 **조회로 확인**(결과적 일관성).
- 각 Phase 심화문서 `docs/PHASE-*.md`(+`SERVICE-DISCOVERY.md`=P4, `SECURITY.md`=P5). 오프라인 HTML `docs/site/`(25p, 더블클릭). 커밋지도 `docs/PHASE-COMMIT-MAP.md`.

## 서비스 (13 컨테이너 / 13 파드 — Phase 16b 에서 discovery·config 삭제)
| 서비스 | 포트 | 프로파일 | 역할 |
|---|---|---|---|
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
- HEAD = `9a60530`(Phase 17). P15=`19a4435` · P16a=`d413b1c` · P16b=`787ab76`.
- ✅ **origin/main 까지 전부 푸시 완료** — 미푸시 커밋 없음. CI 실행 #1 **전부 초록불**.
- ⚠️ **Phase 17 문서·인덱스 갱신분이 미커밋**: `docs/PHASE-17-CICD.md` 신규 · **README 대폭 갱신**(Phase 13 시점에 멈춰 있던 '현재 상태'·'실행' 섹션을 Phase 17 기준으로 재작성) · 커밋맵 · build-docs · HTML 25p · handoff.
- **커밋·푸시 모두 사용자가 명시 요청할 때만.**
- ⚠️ **`docs/PHASE-COMMIT-MAP.md` 17행은 `9a60530` 으로 채움 완료.** 다음 Phase 커밋 때 새 행을 추가할 것.
- ⚠️ **`.github/workflows/` 를 건드리는 커밋은 `workflow` 스코프 토큰이 필요**하다(없으면 push 거부). 현재 토큰엔 추가돼 있다.
- gitignore: `deploy/compose/.env`, `docs/tools/node_modules/`, `**/build/`.

## 지금 이 순간의 상태 (2026-08-01, Phase 17 종료 시점)
- **kind 클러스터 `shopsaga` 는 정지 상태**(`docker stop shopsaga-control-plane` + `colima stop`).
  클러스터 정의·PVC·이미지는 **전부 보존**됨 — 재개는 `colima start && docker start shopsaga-control-plane`.
- **Colima 12GB·6CPU**(Phase 16a 에서 증설). compose 와 kind 는 RAM·포트(8000) 모두 충돌하므로 **동시 금지**.
- 도구: `kubectl` v1.36.3 · `kind` v0.32.0 · `helm` v4.2.3 · `k9s` · **`gh` v2.97.0**(단 `gh auth login` 은 아직 안 함).
- 빌드: `./gradlew build` 통과, **테스트 87개 / 실패 0**. CI 에서도 동일하게 통과.
- **CI**: [Actions](https://github.com/jyh4358/msa-practice/actions/workflows/ci.yml) — 실행 #1 전부 초록불(13m43s).
  이미지: `ghcr.io/jyh4358/msa-practice/<service>:<커밋SHA>`(멀티아치) · `:latest`.
  ⚠️ GHCR 패키지는 **private** 이라 pull 에 인증이 필요하다(CI 는 imagePullSecret 으로 처리).

### 빠른 조작
```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
kubectl get pods -n shopsaga                        # 상태
./deploy/k8s/build-and-load.sh all                  # 이미지 재빌드 + kind load
./deploy/k8s/apply.sh                               # 전체 배포(ConfigMap·RSA키·ingress 포함)
./deploy/k8s/apply.sh --config                      # 설정만 갱신 + 롤아웃
kind delete cluster --name shopsaga                 # 통째로 정리
```
자세한 실행·스모크·함정표: `deploy/k8s/README.md`. 심화: `docs/PHASE-16-KUBERNETES.md`.

### 설정이 사는 곳 (Phase 16b 구조 — 꼭 기억)
```
① services/*/src/main/resources/application.yml   로컬(IDE) 기본값 localhost
② shared/messaging/.../shopsaga-messaging-defaults.yml   공통 메시징(4개 서비스가 classpath import)
③ deploy/config/{common,<service>}.yml           환경 오버라이드 — compose 와 k8s 가 **공유**
     → /application/config/{10-common,20-service}/application.yml (뒤가 이김)
```

## 실행 / 검증 (재현)
```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
# ⚠️ ENCRYPT_KEY 는 Phase 16b 에서 불필요해졌다(Config Server·{cipher} 삭제).
./gradlew bootJar
docker compose -f deploy/compose/compose.yml --profile async up -d --build   # 13컨테이너
# 빌드/테스트(Docker 필요 — Testcontainers). 현재 87개 통과:
DOCKER_HOST=unix://$HOME/.colima/default/docker.sock TESTCONTAINERS_RYUK_DISABLED=true ./gradlew build
```
- 공개 포트(compose): gateway :8000 · Grafana :3000 · kafka-ui :8090 · kafka :9092 · mongo :27017.
- 공개 포트(k8s): Ingress :8000(`/`→gateway, `/grafana`→Grafana) · order-service NodePort :30080(게이트웨이 우회 디버깅).
- **스모크**: 로그인(`POST :8000/auth/login`) → 주문(`POST :8000/orders` → **PENDING**) → 폴링(`GET :8000/orders/{id}` → CONFIRMED)
  · 읽기모델 `GET :8000/order-views?customerId=` · 재고 `GET :8000/inventory/{productId}`
  · Saga 상태 `docker exec shopsaga-order-db-1 psql -U order -d orderdb -c "SELECT * FROM saga_instance;"`
- **실패 시연**: 수량 9999(재고 부족→CANCELLED) / 합계 `*.99`(결제 거절→재고 원복+CANCELLED)
  / **payment 중단 후 주문**(타임아웃 sweep → 재촉 3회 → 보상 → CANCELLED, ~70초)
- **Phase 14 장애 주입**: `POST :8000/inventory/chaos?failRate=100` (Retry→회로 개방) / `?delayMs=3000` (TimeLimiter) / `DELETE` 로 해제
  · 회로 상태 `GET :8000/actuator/circuitbreakers`(토큰 필요) · order 쪽은 `/actuator/health` 의 `circuitBreakers`
  · poison pill: `echo 'NOT-JSON' | kafka-console-producer.sh --topic order-events` → `order-events.DLT` 로 이동, 파티션 안 막힘
  · **고아 결제 보상**: payment 정지 → 주문 → sweep 포기(~70s) → payment 재기동 → payment.status = **REFUNDED**
- **Phase 15 설정 방송**: config-repo 수정 → 아무 서비스 한 곳에 busrefresh →
  `docker exec shopsaga-inventory-service-1 sh -c "wget -q --post-data='{}' --header='Content-Type: application/json' --header='Authorization: Bearer $TOKEN' -O- http://localhost:8082/actuator/busrefresh"`
  · 시연: `order.stock-precheck.reject-on-insufficient` false→true 후 수량 99999 주문 → **409**(재시작 없음)
- **Phase 15 계약 테스트**: `./gradlew :services:inventory-service:contractTest`(프로듀서) ·
  `./gradlew :services:order-service:test --tests '*InventoryContractConsumerTest*' --rerun-tasks`(소비자) ·
  `./gradlew :services:order-service:contractTest`(이벤트 계약) · 생성 코드는 `build/generated-test-sources/contractTest`
- ⚠️ **gateway는 config만 바뀌어도 재시작 필요**(라우트는 기동 시 로드). k8s 는 `kubectl rollout restart`.
- **Phase 16b 설정 방송**: `deploy/config/*.yml` 수정 → `./deploy/k8s/apply.sh --config`(롤아웃) 또는
  ConfigMap 만 갱신 후 아무 서비스에 `busrefresh`(재시작 없이 재바인딩 — 단 파일 동기화에 최대 ~70초).
- ⚠️ **Gradle 결과를 `| tail` 로 파이프하면 exit code가 tail 것** → `> /tmp/x.log 2>&1; echo $?` 로 확인할 것(실제로 오판했음).
- ⚠️ **`docker exec` 폴링 루프는 매우 느리다**(호출당 수~수십 초). 검증 루프는 호출 수를 최소화하고 한 번에 여러 값을 조회할 것.
- gradle/docker/git 명령은 `dangerouslyDisableSandbox: true`로 실행.

## 다음 단계 — Phase 18 (캡스톤 · 선택)
로드맵 `MSA-LEARNING-PLAN.md` §Phase 18. **필수가 아니라 고르는 단계**다. 후보:
- **Helm/Kustomize** 리팩터 — `apply.sh` 의 명령형 부분(ConfigMap 생성·이미지 sed)을 선언적으로. Phase 16·17 한계 #1
- **GitOps**(Argo CD/Flux) — CI 는 "배포 가능함"만 증명한다. 실제 반영은 아직 수동. Phase 17 한계 #2
- metrics-server + **HPA** · NetworkPolicy · `runAsNonRoot`/`readOnlyRootFilesystem` (보안 기본값)
- **Boot 4.1 + Spring Cloud 2025.1(Oakwood) 이전** — Jackson 3 · Jakarta EE 11 · Spring Framework 7
- shipping·catalog 서비스 추가(Saga 참여자 확장) · Debezium CDC 로 outbox 릴레이 대체 · gRPC 엣지
- 이미지 취약점 스캔(Trivy) · cosign 서명/SBOM · outbox 격리분 재처리 도구

### Phase 17 이 남긴 것 (문서 §8 에 전체 9항목)
- 이미지 교체를 `sed` 로(Kustomize 부재) · **CD 없음**(배포는 여전히 수동) · 중간 태그 누적
- 취약점 스캔·서명·SBOM 없음 · 스모크가 행복 경로 하나 · 도커 레이어 캐시 없음 · 버전 고정(`0.0.1-SNAPSHOT`)

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
- **★ spring-cloud-stream 바인더는 `spring.kafka.{producer,consumer}` 설정을 물려받는다** → Phase 14의 JsonSerializer/ErrorHandlingDeserializer가
  Bus 의 byte[] 메시지를 **base64로 이중 인코딩**해 조용히 무력화한다(토픽엔 쌓이는데 아무도 리프레시 안 됨·에러도 없음).
  `spring.cloud.stream.kafka.binder.configuration` 으로 ByteArray(De)Serializer 를 되돌려야 한다.
- **`@Value` 는 리프레시로 안 바뀐다** → `@ConfigurationProperties` 로 받아야 재바인딩된다. `@ConditionalOnProperty`(빈 존재 여부)는 재시작 필요.
- 컨테이너의 wget 은 **busybox** — `--method` 없음. `--post-data='{}'` + `Content-Type: application/json`(없으면 actuator 415).
- SCC 4.3에는 **Kafka 메시징 통합이 없다**(Camel/SI/Stream/JMS만) → `MessageVerifier` 직접 구현.
  스트림이 클래스패스에 있으면 검증기 제네릭이 **`Message<?>`** 여야 한다(아니면 `NoSuchBeanDefinitionException`).
- SCC 계약에서 `testMode = EXPLICIT` 를 주면 **메시징 테스트가 생성되지 않는다**(기본값 유지할 것).
  matcher 는 `predefined:` 대신 **명시 정규식(`value:`)** — predefined 는 단언이 생성되지 않는 경우가 있다(생성 코드를 꼭 열어 볼 것).
- 소비자 계약 테스트는 `~/.m2` stub 이 Gradle 입력이 아니라 **UP-TO-DATE 로 건너뛴다** → `--rerun-tasks`.
- **[k8s] `imagePullPolicy` 기본값은 태그로 결정된다**: `:latest` → `Always`(레지스트리로 나감 → `ImagePullBackOff`), 그 외 → `IfNotPresent`.
  버전 태그를 쓰고 **`kind load docker-image` 를 반드시** 실행할 것(kind 노드는 자기만의 containerd 저장소를 갖는다).
- **[k8s] Spring Security 가 probe 를 막는다**: `/actuator/health` 만 permitAll 하면 `/actuator/health/{liveness,readiness}` 가 401 →
  readiness 실패로 트래픽 차단 + liveness 실패로 무한 재시작. `/actuator/health/**` 도 열 것(Phase 16a 에서 6개 서비스 전부 수정함).
- **[k8s] liveness 에 DB 같은 외부 의존성을 넣지 말 것** — 전 파드 동시 재시작 루프 + 복구 시 커넥션 폭풍. readiness 에만 넣는다.
  `probe.timeoutSeconds` 기본값은 **1초**라 DB 조회하는 readiness 엔 빡빡하다(flapping) → 3초.
- **[k8s] RWO PVC + 기본 `RollingUpdate` = 교착**(새 파드가 볼륨을 못 잡아 Pending, 헌 파드는 안 죽음) → DB 는 `strategy: Recreate`.
- **[k8s] ConfigMap 만 바꾸면 아무 일도 안 일어난다** → `kubectl rollout restart`. (파드가 자동으로 뜬다면 podTemplate 이 같이 바뀐 것이다.)
- **[k8s] Service 분산은 라운드로빈이 아니라 무작위**(iptables `statistic mode random`). 표본이 작으면 편중돼 보인다(실측 30회에 13/11/6).
- **[k8s] `depends_on` 이 없다** — 앱이 DB 보다 먼저 떠서 CrashLoopBackOff 2~3회 후 자력 회복하는 게 정상 동작이다.
- **[k8s/CI] ingress-nginx admission webhook 경쟁** — 컨트롤러 파드가 Ready 여도 webhook Service 엔드포인트는
  아직일 수 있다 → `failed calling webhook … connection refused`. **CI 가 빨라지자(캐시) 드러났다**(1차는 우연히 통과).
  EndpointSlice 대기 + apply 재시도로 해결. 재시도만 넣고 **실패 처리를 빼면 안 된다**(Ingress 없는 채 '성공' 보고).
- **[CI] `.github/workflows/` 푸시는 `workflow` 스코프 토큰이 필요**하다 — 없으면 `remote rejected`.
  우회: 워크플로 파일이 없는 커밋만 먼저 푸시(`git push origin <sha>:main`).
- **[CI] GitHub API 무인증 폴링은 시간당 60회** — CI 진행 확인을 자주 하면 금방 소진된다. `gh auth login` 하면 5,000회.
- **[CI] `upload-artifact` 는 매칭 파일들의 '공통 조상' 기준으로 경로를 접는다** → 다운로드 쪽에서 경로를 가정하지 말고 `find` 로 찾을 것.
- **[CI] 잡이 다르면 파일이 공유되지 않는다**(각자 새 VM) → artifact 로 넘겨야 한다.
- **[k8s] Service 는 Ready 인 파드에게만 트래픽을 보낸다 → 자기참조 부트스트랩이 교착한다.**
  Kafka KRaft `controller.quorum.voters` 를 `1@kafka:29093`(Service 이름)로 두면 브로커가 자기 등록을 못 해 영원히 안 뜬다.
  단일 노드면 `1@localhost:29093`. 다중이면 StatefulSet + headless + `publishNotReadyAddresses: true`.
- **[k8s] `KafkaAdmin` 은 토픽을 기동 시 딱 한 번 만든다.** 브로커보다 먼저 뜨면 조용히 실패하고 재시도하지 않는다
  → 파드는 전부 초록불인데 `UNKNOWN_TOPIC_OR_PARTITION` 으로 Saga 가 멈춘다. **initContainer 로 대기**시킬 것.
- **[k8s] health 그룹에 없는 컨트리뷰터를 넣으면 기동이 실패**한다(`NoSuchHealthContributorException`).
  공통 설정을 여러 서비스가 공유하면 `management.endpoint.health.validate-group-membership: false` 가 필요하다.
- **[k8s] Ingress 오브젝트만 만들면 아무 일도 안 일어난다** — Ingress **컨트롤러**(ingress-nginx)를 따로 설치해야 한다.
- **[compose↔kind] 포트도 충돌한다**(둘 다 8000). kind 를 재우려면 `docker stop shopsaga-control-plane`, 복구는 `docker start`.
  포트 충돌로 생성 실패한 컨테이너는 `up -d` 로 살려도 **포트 매핑이 없다** → `--force-recreate`.
- **[Spring] `spring.config.import: classpath:...`** 로 공통 설정을 라이브러리에서 공유할 수 있다(Config Server 대체).
  단 **import 문서와 겹치는 키를 두지 말 것**(우선순위를 외우지 않아도 되게).
- **[테스트] `src/test/resources/application.yml` 사본은 실제 설정을 가린다.** Phase 6 때 만든 게이트웨이 사본이
  라우트 4개짜리 옛 버전을 검증하고 있었다 — 원본이 5개로 바뀐 걸 몰랐다. 사본을 두면 썩는다.
- **[zsh] `NS="-n shopsaga"; kubectl get pods $NS` 는 동작하지 않는다** — zsh 는 변수를 단어 분할하지 않아 통째로 한 인자가 된다(`namespaces " shopsaga" not found`).
