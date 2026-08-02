# ShopSaga MSA — 세션 핸드오프 (2026-08-02, Phase 19(GitOps) 완료)

> 새 세션에서 이 파일 + 프로젝트 메모리를 먼저 읽고 이어가세요. **가장 완전한 상태는 프로젝트 메모리**
> `~/.claude/projects/-Users-younho-IdeaProjects-msa/memory/msa-learning-project.md` 에 있습니다.
> 이 파일은 "지금 어디까지 왔고, 바로 다음에 뭘 하나"의 빠른 지도입니다.

## 이 프로젝트가 뭔가
- **ShopSaga**: Spring Cloud MSA를 **Phase별로 직접 만들며 트레이드오프를 배우는** 학습 프로젝트(제품화 아님).
- 사용자: Java/Spring 개발자(GitHub `jyh4358`, 원격 `github.com/jyh4358/msa-practice`). 한국어로 대화.
- 스택(고정): Java 21 · Spring Boot **3.5.15** · Spring Cloud **2025.0.3** · Gradle 멀티모듈 모노레포(버전 카탈로그 `gradle/libs.versions.toml`).
- 아키텍처: **헥사고날**(domain / application(port.in·out) / adapter(in.web·in.event·out.persistence·out.messaging)). 상세 `docs/HEXAGONAL.md`.
- 컨테이너 런타임: **Colima**(arm64). `DOCKER_HOST=unix://$HOME/.colima/default/docker.sock`.

## 지금까지 완료 (Phase 0~19)
0 스캐폴드 · 1 모놀리스(ACID·비관적락·QueryDSL) · 2 payment 분리(원격 REST) · 3 게이트웨이 · 4 Eureka ·
5 보안(RS256 JWT) · 6 중앙설정(Config+`{cipher}`) · 7 Docker Compose · 8 관측성(8a 트레이스+메트릭 / 8b 로그→Loki·RED·트레이스↔로그) ·
9a 비동기(Kafka) · 10 Outbox+멱등(신뢰성 척추) · 11 CQRS 읽기모델(Mongo) · 12 Saga 코레오그래피+보상 · 13 Saga 오케스트레이션 ·
14 복원력(Resilience4j 5종·회로차단기+fallback·DLQ/poison·outbox 격리·고아 결제 보상) ·
15 플랫폼 강화(Spring Cloud Bus 설정 방송·계약 테스트(동기 API+이벤트)·스키마 진화 tolerant reader) ·
**16a 로컬 k8s(kind)**(클러스터+order-service 이전·ConfigMap/Secret·liveness≠readiness probe·NodePort·자가치유·스케일) ·
**16b 전체 플랫폼 on k8s**(**Eureka·Config Server 삭제** → 플랫폼 DNS+ConfigMap·Ingress·compose 동반 이전(15→13)·무중단 롤링·auth 복제본 2) ·
**17 CI/CD**(GitHub Actions 5잡: 빌드·테스트 → 네이티브 러너 2개 멀티아치 이미지 → GHCR `:커밋SHA` → **CI 안 kind 스모크 배포**).
  ★ 2차 실행이 **캐시로 빨라지자** ingress webhook 경쟁이 드러나 실패 → 수정 → 3차 통과. 그 경위가 `docs/PHASE-17-CICD.md` §7-⑦.
**18 선언적 배포**(Kustomize `base/`+`overlays/{local,ci}` · 생성기 해시로 **설정 변경 → 자동 롤아웃** ·
  CI 의 `sed` → `kustomize edit set image` · ingress-nginx 를 **Helm 릴리스**(차트 4.13.9)로 · `apply.sh --config` 삭제).
**19 GitOps**(Argo CD 차트 10.2.2/v3.4.6 · `overlays/gitops` 를 추적 · CI 가 이미지 태그를 Git 에 커밋(승격) ·
  `selfHeal` 6초 복원 · `prune` 으로 Phase 18 한계 #2 해결 · **비밀은 렌더 밖으로**(secretGenerator 제거)).

> ⚠️ **Phase 12부터 주문 흐름은 `--profile async` 필수**(동기 결제 호출 제거 — 전부 Kafka 이벤트).
> `POST /orders` 는 **`PENDING` 즉시 반환**, 최종 상태(CONFIRMED/CANCELLED)는 **조회로 확인**(결과적 일관성).
- 각 Phase 심화문서 `docs/PHASE-*.md`(+`SERVICE-DISCOVERY.md`=P4, `SECURITY.md`=P5). 오프라인 HTML `docs/site/`(**30p**, 더블클릭 — `cd docs/tools && npm run build` 로 재생성). 커밋지도 `docs/PHASE-COMMIT-MAP.md`.
- **복습 자료 4종**: `docs/REVIEW-PART-{A,B,C,D}.md` (0~7 / 8~11 / 12~15 / 16~19).
  각각 큰그림·단계별회고·개념자가진단·셀프퀴즈(접힘 답)·재현체크리스트·누적한계표.
  ★ **사용자가 지금 여기를 할 차례다** — 새 기능보다 복습이 우선이라고 함께 판단했다.

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
- ⚠️ **감사(2026-08-02) 수정분이 미커밋 상태다** — 코드(H1/H2/IDOR)·CI·k8s·docs 전반.
  커밋·푸시는 사용자가 명시 요청할 때만. 푸시 전 `git pull --rebase` (gitops 봇 커밋 존재 가능).
  감사 이전 마지막 푸시 HEAD = `1894a0e`.
  Phase 19: `894e12d`(GitOps 도입) · `46e0c69`(prune 실증) · `4c6b748`(문서).
  복습 자료: `0656391`(파트 B·C·D) · `751d32c`(복습 우선 방침).
  ⚠️ 마지막 push 로 CI 가 돌았으므로 **봇 커밋이 하나 더 생겨 있을 수 있다** →
  다음 작업 시작 전에 `git pull --rebase` 한 번.
- ⚠️⚠️ **CI 봇이 `main` 에 커밋한다** — CI 가 한 번 돌 때마다 로컬이 뒤처진다.
  `git push` 가 rejected 되면 당황하지 말고 **`git pull --rebase` 후 push**. (upstream 추적 설정해 둠)
- **Phase 18 커밋**: `72ab3f7`(기능) · `2486e00`(커밋 지도 행).
- ⚠️⚠️ **2026-08-02 이력 재작성함.** 커밋 이메일 오타(`jyh4358@gamil.com` — gmail 아님)로 GitHub 이
  31개 커밋을 계정에 연결하지 못해 **기여 그래프(잔디)에 안 잡히고 있었다.**
  `git filter-repo --mailmap` 으로 전부 `97331219+jyh4358@users.noreply.github.com` 로 교체.
  → **모든 커밋 해시가 바뀌었다**(내용·날짜·메시지는 그대로). 문서 58곳의 해시 참조는 매핑으로 자동 갱신함.
  → force-push 완료. 포크 0·스타 0·협업자 본인뿐이라 남에게 영향 없었음.
  → GitHub 이 커밋을 계정에 연결한 것 확인(`gh api …/commits/<sha> --jq .author.login` → `jyh4358`).
     재작성 전에는 전부 `author: null` 이었다.
  → 원본 이력 번들 백업은 **확인 후 삭제함**. 옛 해시로는 아무것도 복원할 수 없다.
- ⚠️ 앞으로 커밋 전 확인: `git config user.email` 이 `97331219+jyh4358@users.noreply.github.com` 인지.
  (global 에 설정돼 있음. 로컬 override 는 없음.)
- **커밋·푸시 모두 사용자가 명시 요청할 때만.**
- ⚠️ 다음 Phase 커밋 때 `docs/PHASE-COMMIT-MAP.md` 에 새 행 추가할 것.
- ⚠️ `deploy/k8s/base/.secrets/` 는 **gitignore** — auth RSA 개인키가 여기 산다.
  `bootstrap-secrets.sh` 가 없으면 만들고, **kubectl 로 Secret `auth-jwt-key` 까지 넣는다.**
  Phase 19 에서 secretGenerator 를 걷어냈으므로(Argo CD 가 Git 밖 파일을 못 읽는다)
  이 Secret 이 없으면 **Argo CD 가 `Synced` 인데 `Progressing` 에서 안 벗어난다** — 겪었던 함정이다.
- ⚠️ **`.github/workflows/` 를 건드리는 커밋은 토큰에 `workflow` + `Contents: Read and write` 필요**
  (없으면 `remote rejected` / 403). 현재 토큰엔 둘 다 있다.
  ⚠️ `gh auth setup-git` 을 실행하면 git 자격증명 헬퍼가 gh 로 바뀌고 `credential.usehttppath` 가 생겨
  기존 keychain 조회가 깨진다 — 문제가 생기면 그 두 설정을 `git config --global --unset-all` 로 되돌릴 것.
- gitignore: `deploy/compose/.env`, `docs/tools/node_modules/`, `**/build/`.

### 사용자에게 아직 답을 못 받은 것 2개 (급하지 않음)
- `docs/site/` HTML **30p 가 매 커밋마다 diff 에 잡힌다** → gitignore 할지 계속 커밋할지.
- `Co-Authored-By` 트레일러가 갈려 있다: P12 이전 `Claude Opus 4.8`, P14~17 `Claude Opus 5 (1M context)`.

## 지금 이 순간의 상태 (2026-08-02, Phase 19 완료 + 복습 자료 작성 + **전면 감사·수정 후**)
- ★★ **2026-08-02 전면 감사(Fable 5, 4개 축) 수행 → 결함 수정 적용, 아직 미커밋.**
  무엇을 왜 고쳤는지는 `docs/AUDIT-2026-08.md`, 안 고치고 이연한 것은 `docs/BACKLOG.md` 가 단일 진실 공급원.
  핵심 수정: ① processed_commands.payment_id 저장·재생(H1, 마이그레이션 payment V5·inventory V5·order V9)
  ② Order.confirm 단조 전이(H2, 코레오그래피 순서 역전) ③ 게이트웨이 payments-route 삭제 + 주문
  소유권 검사(IDOR — POST /orders 의 customerId 는 이제 JWT subject 에서 유도, 몸통 값 무시)
  ④ CI 최상단 permissions: contents: read + persist-credentials: false ⑤ Grafana 익명 Admin→Viewer.
  **테스트 88개 / 실패 0** (감사 전 87 + 신규 1) — Testcontainers·contractTest 포함 전부 실측 통과.
- **모두 정지 상태.** kind 노드 `docker stop` + `colima stop` 완료(감사 검증차 잠깐 켰다 다시 껐다).
  **클러스터 정의·PVC·이미지·Helm 릴리스(argocd·ingress-nginx) 전부 보존** — 재개는 아래 "재개 절차".
  마지막으로 확인된 Argo CD 상태: `sync=Synced health=Healthy`.
- ★ **사용자는 지금 복습 중이다.** 새 Phase 를 먼저 제안하지 말 것(아래 "다음 단계" 참조).
  복습하다 막히면 질문할 것이고, 그때 해당 Phase 문서로 안내하거나 환경을 띄워 재현 체크리스트를 같이 돌린다.
- ⚠️ **이제 `apply.sh` 를 쓰지 말 것.** Argo CD 의 `selfHeal` 이 6초 만에 되돌린다(실측).
  바꾸려면 Git 을 바꾼다. (`apply.sh` 는 경고를 출력하지만 막지는 않는다.)
  ingress-nginx·Argo CD 는 **Helm 릴리스**다(`helm list -A`).
- **Colima 12GB·6CPU**(Phase 16a 에서 증설). compose 와 kind 는 RAM·**포트(8000)** 모두 충돌 → **동시 금지**.
- 도구: `kubectl` v1.36.3 · `kind` v0.32.0 · `helm` **v4.2.3** · `k9s` · `gh` v2.97.0(**로그인 완료**, API 5000/hr).
- 빌드: `./gradlew build` 통과, **테스트 88개 / 실패 0**(감사 수정 반영 후 실측).
- **CI**: [Actions](https://github.com/jyh4358/msa-practice/actions/workflows/ci.yml) — 최신 `46e0c69` **전부 success**.
  잡 **6개**: 빌드·테스트 → 이미지(amd64/arm64 네이티브) → 매니페스트 → kind 스모크 → **gitops 승격**.
  마지막 잡이 `overlays/gitops` 에 이미지 태그를 커밋한다. `paths-ignore` 로 재트리거를 막는다.
  ★ Phase 18 첫 push(`df93958`)는 **스모크가 실패**했다 — 렌더 검증 단계가 `apply.sh` 보다 먼저인데
  `.secrets/`(gitignore)를 만드는 게 `apply.sh` 안에 있었다. `bootstrap-secrets.sh` 분리로 해결.
  경위는 `docs/PHASE-18-KUSTOMIZE.md` §7-⑨. 같이 드러난 `shasum`(macOS)→`sha256sum`(리눅스) 이식성 버그도 수정.
- **이미지**: `ghcr.io/jyh4358/msa-practice/<service>:<커밋SHA>` 및 `:latest` — **멀티아치(amd64+arm64), 공개**.
  ⚠️ 앞서 "private" 이라 적었던 것은 **오류**였다. 공개 저장소의 Actions 가 push 한 패키지는 저장소 공개 설정을
  물려받는다 → `docker pull` 에 로그인 불필요(익명 6/6 HTTP 200 실측). CI 의 `imagePullSecret` 은 이 구성에선 불필요.

### 재개 절차 (compact 후 여기부터)
```bash
colima start                                   # VM (12GB·6CPU 설정 유지)
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
docker start shopsaga-control-plane            # kind 노드 → 13파드 + argocd 4파드 자동 재생성(~2분)
kubectl get pods -n shopsaga -w
curl -s localhost:8000/actuator/health         # Ingress → gateway, 200 기대

# Phase 19 — Argo CD 상태 / UI
kubectl get application shopsaga -n argocd -o wide
kubectl port-forward svc/argocd-server -n argocd 8081:80    # http://localhost:8081
kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 -d; echo
```
⚠️ 노드 재기동 직후엔 CoreDNS·컨트롤러가 뜰 때까지 파드가 0개로 보인다 — 기다리면 controller-manager 가 만든다.

### 빠른 조작
```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
kubectl get pods -n shopsaga                        # 상태
./deploy/k8s/build-and-load.sh all                  # 이미지 재빌드 + kind load
./deploy/k8s/apply.sh                               # 전체 배포 = overlays/local
./deploy/k8s/apply.sh ci                            # GHCR 이미지로 배포 = overlays/ci
kubectl kustomize deploy/k8s/overlays/local         # 적용 전에 최종 YAML 을 눈으로
kubectl diff      -k deploy/k8s/overlays/local      # 지금 클러스터와의 차이
kind delete cluster --name shopsaga                 # 통째로 정리
```
> **Phase 18 이후 `--config` 플래그는 없다.** 설정을 고쳤으면 `apply.sh` 를 그냥 다시 돌리면 된다 —
> ConfigMap 이름의 해시가 바뀌면서 롤링 업데이트가 저절로 일어난다(실측 44초·무중단).
자세한 실행·스모크·함정표: `deploy/k8s/README.md`. 심화: `docs/PHASE-16-KUBERNETES.md`.

### 설정이 사는 곳 (Phase 16b 구조 — 꼭 기억)
```
① services/*/src/main/resources/application.yml   로컬(IDE) 기본값 localhost
② shared/messaging/.../shopsaga-messaging-defaults.yml   공통 메시징(4개 서비스가 classpath import)
③ deploy/config/{common,<service>}.yml           환경 오버라이드 — compose 와 k8s 가 **공유**
     → /application/config/{10-common,20-service}/application.yml (뒤가 이김)
     · compose : 바인드 마운트
     · k8s     : deploy/config/kustomization.yaml 의 configMapGenerator (Phase 18)
```
⚠️ **`deploy/config/kustomization.yaml` 이 왜 거기 있나**: Kustomize 는 기본적으로 kustomization
디렉터리 **바깥 파일**을 못 읽는다(`LoadRestrictionsRootOnly`). 그래서 `deploy/config/` 를 스스로
kustomization 루트로 만들고 `base` 가 `resources: [../../config]` 로 끌어온다. 이 구조를 깨면
compose 와의 설정 공유가 깨진다 — **옮기지 말 것.**

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

## 다음 단계 — **복습이 먼저다** (사용자와 합의함)

⚠️ **새 Phase 를 시작하기 전에 사용자가 Phase 0~19 를 복습하기로 했다.**
`docs/REVIEW-PART-{A,B,C,D}.md` 를 순서대로 풀면서 **막히는 항목**을 찾는 단계다.
새 기능을 얹으면 복습할 면적만 늘어나므로, 요청이 없는 한 먼저 제안하지 말 것.

복습 중 사용자가 물어볼 만한 것 → 해당 Phase 문서로 안내하고, 필요하면 클러스터를 띄워
`재현 체크리스트` 를 같이 돌려 볼 것(각 REVIEW 문서 §5).

복습이 끝난 뒤의 후보는 아래와 같다.

**권장 1순위 — Sealed Secrets (또는 External Secrets Operator).**
이유: Phase 19 가 **일부러 되돌린 것**을 되찾는 작업이라 맥락이 가장 신선하다.
Argo CD 가 Git 밖 파일을 못 읽어 `secretGenerator` 를 걷어냈고, 그래서
**키를 갈아도 파드가 자동으로 안 갈린다**(Phase 19 한계 #3). 암호문을 Git 에 두면
생성기를 다시 쓸 수 있고, DB 비밀번호 평문 문제(#4)도 같이 해결된다.

**권장 2순위 — `git revert` 롤백 실측.** Phase 19 한계 #8 — 롤백이 된다고 **주장만** 하고
검증하지 않았다. 커밋 하나 되돌려 Argo CD 가 옛 이미지로 되돌리는지 재보는 건 30분이면 된다.

**그 외 후보**
- 보안 기본값 — `runAsNonRoot`/`readOnlyRootFilesystem` · NetworkPolicy · Pod Security Standards
- 이미지 취약점 스캔(Trivy) · cosign 서명/SBOM — CI 에 한 잡 추가면 된다(가성비 좋음)
- metrics-server + **HPA**(오토스케일 실측) — 지금은 metrics-server 가 없어 불가
- **비밀 관리** — External Secrets Operator / SOPS. 지금 DB 비밀번호가 git 에 평문(dev 값)이다
- **Boot 4.1 + Spring Cloud 2025.1(Oakwood) 이전** — Jackson 3 · Jakarta EE 11 · Spring Framework 7 (큰 작업)
- shipping·catalog 서비스 추가(Saga 참여자 확장) · Debezium CDC 로 outbox 릴레이 대체 · gRPC 엣지
- **격리된 outbox 8건 재처리 도구** — Phase 16b 사고의 실제 유실분(로컬 PVC 에 아직 남아 있다)
- CI 스모크에 **실패 경로**(재고 부족 → 보상 → CANCELLED) 추가 — 지금은 행복 경로 하나뿐

### Phase 16~19 가 남긴 것 (각 문서 §8 에 전체 표)
- ~~배포 수동~~ · ~~옛 해시 ConfigMap prune 안 됨~~ **→ Phase 19 에서 해결** · 중간 태그(`:sha-arch`) 누적
- **키 교체 시 자동 롤아웃 없음**(Phase 19 가 되돌린 것) · Argo CD UI 는 port-forward 로만 · 폴링 60초
- Secret 은 base64 일 뿐(git 에 평문 dev 값) · RSA 키가 머신마다 다름 · 컨테이너 root 실행 · NetworkPolicy 없음
- DB·Kafka 가 Deployment(StatefulSet 아님) · PVC 가 local-path · 단일 노드 · Kafka 단일 브로커
- metrics-server 없음(HPA 불가) · 취약점 스캔·서명·SBOM 없음 · overlay 가 local·ci 둘뿐
- CI 스모크가 "행복 경로" 하나 · 도커 레이어 캐시 없음 · 버전 `0.0.1-SNAPSHOT` 고정
- Helm 버전 스큐(로컬 4.2.3 / CI 3.21.3) · Secret 이 매번 `configured` 로 보임(표시상 문제, §7-⑧)

> ✅ Phase 18 이 **해결한** 것: 명령형 ConfigMap 생성 · CI 의 `sed` · 원격 URL 로 ingress 설치 ·
> `--config` 수동 롤아웃(→ 자동) · 매니페스트에 흩어진 `namespace` 34곳.
> ✅ Phase 19 가 **해결한** 것: 배포 수동(→ Argo CD) · prune · 드리프트 복원(selfHeal) ·
> CI 가 클러스터 자격증명을 갖던 문제(이제 CI 는 Git 에만 쓴다).

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
  `CF="-f a.yml --profile async"; docker compose $CF ps` 도 같은 이유로 조용히 0건을 낸다. **인자는 리터럴로 쓸 것.**
- **[awk] `$2 ~ /^([0-9]+)\/\1$/` 같은 역참조는 POSIX awk 에 없다.** `1/1` 판정에 쓰면 항상 거짓이라
  "파드 0개 Ready" 로 잘못 보인다. 준비 상태는 `kubectl rollout status` 나 `kubectl wait` 로 볼 것.
- **[Kustomize] kustomization 디렉터리 바깥 파일은 못 읽는다**(`security; file ... is not in or below ...`).
  그 디렉터리를 스스로 kustomization 루트로 만들고 `resources:` 로 참조하면 통과한다(§Phase 18).
- **[Kustomize] 생성기(configMapGenerator/secretGenerator)는 이름에 내용 해시를 붙인다** — 이게 설정 변경 시
  자동 롤아웃을 만든다. 단 **DB 비밀번호처럼 '재시작해도 반영 안 되는' 값은 생성기로 만들지 말 것**
  (PostgreSQL 은 비밀번호를 PVC 초기화 때 굽는다 → 이름만 바뀌고 인증은 깨진다).
- **[Kustomize] 공통 라벨에 `includeSelectors: true` 를 쓰면 기존 Deployment 에 apply 가 거부된다**
  (`spec.selector` 는 불변 필드). 조회용 라벨은 `includeSelectors: false`.
- **[Helm] `kubectl apply` 로 깐 것을 `helm upgrade --install` 로 덮을 수 없다**(소유권 오류).
  ns + ClusterRole/Binding + IngressClass + ValidatingWebhookConfiguration 을 먼저 지워야 한다.
- **[Helm 4] `--wait` 가 불리언이 아니라 전략 플래그다**(`--wait WaitStrategy[=watcher]`, 기본 `hookOnly`).
  값 없이 `--wait` 만 쓰면 Helm 3 과 같은 뜻이라 호환되지만, `--wait 5m` 처럼 붙이면 Helm 4 가 실패한다.
- **[Gradle] `./gradlew build` 는 테스트가 up-to-date 면 건너뛴다** — "초록불"이 테스트 증거가 아니다.
  실제로 세려면 `--rerun-tasks`. 그리고 **계약 테스트는 `build/test-results/contractTest/`** 에 따로 쌓인다
  (`test/` 만 세면 84, 합치면 **87**).
- **[Testcontainers] 로컬 테스트에 `DOCKER_HOST` 가 필요하다** — 없으면
  `Could not find a valid Docker environment`. `export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock`
  와 `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`.
- **[Argo CD] `Synced` 인데 `Progressing` 에서 안 벗어나면 "Git 에 없는 전제"를 의심한다.**
  대표적으로 `auth-jwt-key` Secret — Argo CD 는 Git 에 있는 것만 만든다. `kubectl get events -n shopsaga` 로 확인.
- **[Argo CD] prune 은 자기가 만든 것만 지운다**(`argocd.argoproj.io/tracking-id` 어노테이션 기준).
  Argo CD 도입 **이전**에 `kubectl apply` 로 만든 고아는 그대로 남는다 → 전환 시 1회 수동 정리.
- **[Argo CD] selfHeal 은 변경을 '막지' 않는다.** `kubectl scale` 은 성공하고, 6초 뒤 되돌아간다.
  정말 막으려면 RBAC 으로 사람의 쓰기 권한을 빼야 한다.
- **[Argo CD] 서브경로(/argocd) 노출은 업스트림 이슈로 깨진다**(#15750·#9660·#14857).
  `basehref` 가 index.html 에 반영되지 않아 자산이 `/assets/…` 로 새어 나간다 → **port-forward 를 쓸 것.**
- **[Helm] 존재하지 않는 values 키를 줘도 조용히 무시된다**(스키마 강제가 없다).
  `applicationSet.enabled: false` 가 그래서 안 먹었다 → 남의 차트는 **`helm template` 으로 렌더를 확인**할 것.
- **[Helm] 차트 기본값 `global.domain` 이 Ingress 에 host 규칙을 심는다** — `localhost` 로는 안 닿게 된다.
- **[GitOps] CI 봇이 main 에 커밋하므로 로컬 push 가 rejected 된다** → `git pull --rebase` 후 push.
  봇 커밋 무한 루프는 `paths-ignore` 로 막는다(봇은 `overlays/gitops/**` 만 건드린다).
