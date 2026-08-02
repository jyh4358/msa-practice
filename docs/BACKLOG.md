# BACKLOG — 남은 주제 (단일 진실 공급원)

> **왜 이 파일이 필요한가.** 이 프로젝트의 각 `PHASE-*.md`는 §8 "알려진 한계 → 해결 Phase" 표로
> 각 한계가 **어느 Phase에서 해결되는지** 약속해 왔습니다. 그런데 2026-08-02 감사([AUDIT-2026-08.md](AUDIT-2026-08.md))
> 과정에서 **Phase 18이 계획했던 "캡스톤"이 아니라 Kustomize/Helm으로, Phase 19가 GitOps로 재번호매김**된
> 사실이 확정되면서, 여러 문서에 흩어져 있던 "→ Phase 18" 같은 약속이 **가리키는 대상을 잃었습니다**.
> 그 무효화된 항목들을 전부 여기 한곳으로 모으고, 각 문서에는 이 파일을 가리키는 화살표만 남겼습니다.
> 우선순위·"왜 지금 안 했나"는 이 프로젝트가 **학습 목적**이라는 전제 위에서 매겨졌습니다 —
> 실무 프로덕션이라면 순서가 달라질 항목이 많습니다(특히 보안 항목).

---

## 우선순위 표

| # | 우선순위 | 항목 | 왜 필요한가 | 유래 | 왜 지금 안 했나 |
|---|---|---|---|---|---|
| 1 | P1(보안) | **보안·운영 하드닝** — `runAsNonRoot`/Pod Security Standards, NetworkPolicy, Dockerfile `USER` | 모든 컨테이너가 root로 돌고 파드 간 통신이 전부 열려 있다 — 클러스터 하나가 뚫리면 전체가 뚫린다 | [PHASE-16-KUBERNETES.md §8](PHASE-16-KUBERNETES.md), [PHASE-18-KUSTOMIZE.md §8](PHASE-18-KUSTOMIZE.md) | 로컬 kind 학습 클러스터라 실제 공격 표면이 없음 — 우선순위가 CI/Saga/GitOps 같은 "동작하는 기능"에 밀림 |
| 2 | P1(보안) | **issuer/audience 검증 + 서비스 신원**(mTLS 또는 SPIFFE) | JWT는 서명·만료만 검증한다. 서비스 간 호출이 사용자 토큰 재전파에만 의존해 서비스 자체 신원이 없다 | [SECURITY.md §8](SECURITY.md) | mTLS/SPIFFE는 서비스 메시 수준 인프라가 필요해 학습 곡선이 크게 뜀 — 별도 Phase급 주제 |
| 3 | P1(보안) | **Sealed Secrets 또는 External Secrets Operator** | DB 비밀번호·RSA 개인키가 Git에 평문(dev 값) 또는 머신별 로컬 파일로만 존재 | [PHASE-19-GITOPS.md §8](PHASE-19-GITOPS.md) | GitOps(Argo CD)가 Git만 렌더하는 특성상 시크릿 관리 도구 도입이 별도 인프라(Vault 등) 결정을 요구함 |
| 4 | P2(운영) | **metrics-server + HPA + 간단한 부하 테스트** | `kubectl top`도 자동 스케일도 불가능 — 스케일 전략을 실측한 적이 없다 | [PHASE-16-KUBERNETES.md §8](PHASE-16-KUBERNETES.md) | kind 단일 노드에서 HPA 실습 가치가 제한적(스케일할 여유 리소스가 애초에 적음) |
| 5 | P3(플랫폼) | **스키마 레지스트리·API 버저닝** | 이벤트 스키마 진화 규칙(tolerant reader)이 테스트로만 강제되고, 동기 API에 `/api/v1` 같은 버전이 없음 | [PHASE-15-CONTRACTS.md §8](PHASE-15-CONTRACTS.md) | 소비자를 전부 우리가 통제 중이라 당장 깨질 위험이 낮음. Apicurio 컨테이너는 RAM 예산이 가장 빡빡한 Phase 16 직전에 추가하기 부담스러웠음 |
| 6 | P1(공급망) | **이미지 스캔(Trivy)·서명(cosign)**, CI 액션 SHA 고정, kustomize 체크섬 검증 | 빌드 이미지의 CVE를 아무도 보지 않고, 이 이미지가 이 파이프라인에서 나왔음을 증명할 수 없다 | [PHASE-17-CICD.md §8](PHASE-17-CICD.md) + 감사 | 가성비는 좋지만(구현 자체는 간단) Saga/GitOps 등 핵심 학습 목표에 밀려 후순위로 이연됨 |
| 7 | P2(운영 검증) | **`git revert` 롤백 실측** | GitOps의 핵심 약속인 "Git을 되돌리면 클러스터도 되돌아간다"를 이론상으로만 알고 실제로 해 본 적이 없다 | [PHASE-19-GITOPS.md §8-8](PHASE-19-GITOPS.md), REVIEW-PART-D가 미실측을 인정 | 정상 동작할 것으로 예상되고(selfHeal과 원리가 같음) 다른 검증 항목이 더 급했음 |
| 8 | P3(하드닝) | **게이트웨이 횡단 필터 — CORS·요청 로깅** | 브라우저 클라이언트가 생기면 CORS 정책이, 운영 감사가 필요해지면 엣지 요청 로그가 필수가 된다(레이트리밋은 Phase 14에서 이미 해결) | [PHASE-3-GATEWAY.md §8](PHASE-3-GATEWAY.md) | 현재 클라이언트가 curl/스크립트뿐이라 CORS 수요가 없고, 요청 추적은 Phase 8 트레이싱이 대신하고 있음 |

---

## 8. 감사(2026-08-02)에서 발견된 미수정 결함 (코드)

이번 감사에서 발견됐지만 **이번 라운드엔 고치지 않고** 이연한 코드 결함입니다. H1(paymentId 유실)·H2(코레오그래피 순서 역전)·IDOR 등 **이미 고친 것**은 [AUDIT-2026-08.md](AUDIT-2026-08.md) 참고 — 아래는 그 감사에서 "발견했지만 범위 밖"으로 분류한 것들입니다.

| 결함 | 왜 필요한가(리스크) | 왜 지금 안 했나 |
|---|---|---|
| `payments.order_id` UNIQUE 제약 복원 | 제약이 없으면 한 주문에 결제가 여러 건 쌓일 수 있는 여지가 남는다 | 현재 애플리케이션 로직(멱등 가드)이 사실상 막고 있어 실사고 이력 없음 — DB 레벨 이중 방어는 다음 마이그레이션 라운드로 |
| `OutboxRelay`·`SagaTimeoutSweeper`에 `FOR UPDATE SKIP LOCKED` | 지금은 단일 replica 전제 — replica를 늘리면 두 인스턴스가 같은 row를 동시에 집어 중복 처리 위험 | 현재 배포가 각 서비스 1 replica라 실제로는 안 터짐. 다중 replica로 갈 때 반드시 먼저 처리해야 할 선행 조건 |
| inventory-service 멱등 체크·부수효과를 payment 방식으로 트랜잭션 통합 | payment-service는 멱등 체크와 부수효과가 한 트랜잭션인데 inventory는 아직 분리돼 있어 경합 시 이중 처리 여지가 상대적으로 큼 | 지금까지 실제 경합으로 터진 적이 없어 우선순위가 낮게 매겨짐 |
| `/order-views/{id}` 소유권 검사 | `GET /orders/{id}`는 이번 감사로 소유권 검사가 들어갔지만, 읽기 모델(CQRS) 쪽 단건 조회는 아직 같은 검사가 없음 | IDOR 폐쇄 작업이 쓰기 모델(order-service) 위주였고, 읽기 모델은 다음 라운드로 이연 |
| `GET /orders` 페이지네이션 | 주문이 많아지면 목록 조회가 무한정 커진다 | 데모 데이터 규모에서는 체감되지 않아 후순위 |
| outbox/`processed_messages` 보존 정책 | 두 테이블 모두 완료된 row를 정리하는 장치가 없어 무한정 커진다 | 각 Phase 문서(10·12·13)의 §8에서 이미 "운영 과제"로 명시된 항목 — 새로 발견된 게 아니라 재확인 |
| `.dockerignore`·베이스 이미지 digest 고정 | 빌드 컨텍스트에 불필요한 파일이 섞이고, 베이스 이미지 태그가 플로팅돼 재현성이 흔들린다 | 지금까지 빌드 재현성 문제로 실패한 적이 없어 체감 우선순위가 낮음 |
| `preStop` sleep 훅 | 롤링 업데이트 시 종료 중인 파드로 트래픽이 잠깐 더 들어갈 수 있다(무중단이 완벽하지 않음) | 실측(Phase 16/18)상 롤링 업데이트가 이미 무중단으로 확인됐고, 훅은 그 마진을 더 넓히는 수준이라 급하지 않음 |
| `overlays/ci`의 `latest` + `IfNotPresent` 조합 | 태그가 고정돼 있지 않으면 이론상 오래된 이미지를 캐시에서 재사용할 수 있다 | CI가 매번 새 커밋 SHA 태그로 빌드해 실질 위험이 낮음(관성적으로 남은 설정) |
| 격리(quarantine)된 outbox 행 재처리 도구 + `outbox.stuck` 경보 (유래: PHASE-14 §8-5) | Phase 14가 `attempts` 상한으로 outbox row를 격리는 하지만, 격리된 뒤 재투입하는 도구도 격리 발생을 알리는 경보도 없어 사람이 대시보드를 봐야 안다 | 각 Phase 문서에서 이미 "운영 도구 영역"으로 남긴 항목 — DLQ 재투입 도구(PHASE-13/14 §8)와 같은 갈래 |
| 컨슈머/DLT/투영 lag **경보**(Grafana Alertmanager) (유래: PHASE-11 §8, PHASE-14 §8-4) | lag·DLT 적재를 지금은 kafka-ui를 사람이 봐야만 안다 — 쌓여도 아무도 모르는 구조 | 관측 스택(Phase 8)에 Alertmanager 연동이 빠져 있고, 경보 룰 설계는 별도 학습 주제로 판단해 이연 |
| 가짜 PG stub의 **환불 실연동** — 실제 결제망 환불 실패 시나리오 (유래: PHASE-14 §8-8) | 지금 환불은 stub 내부 상태 변경일 뿐, 외부 결제망 환불이 실패·지연하는 현실 시나리오를 다루지 않는다 | 실제 PG 연동 자체가 학습 범위 밖 — 캡스톤 성격의 Phase가 사라지며 목적지를 잃은 항목 |
| chaos 엔드포인트 운영 제거/권한 강화 (유래: PHASE-14 §8-6) | `chaos.enabled` 가 기본 켜져 있고 인증만 통과하면 누구나 지연·실패를 주입할 수 있다 | 학습 도구로서 의도된 상태 — 운영 프로파일 분리 시점에 함께 처리 |
| `/actuator/health` `show-details`를 `when-authorized`로 (유래: SECURITY.md §8, 감사 인프라 지적) | 게이트웨이 `permitAll` 경로라 인증 없이 DB·디스크·서킷브레이커 내부 상태가 노출된다 | k8s probe가 같은 엔드포인트를 쓰므로 `management.server.port` 분리까지 함께 설계해야 안전 — 단순 값 변경으로 끝나지 않아 이연 |
| 게이트웨이 `permitAll(/auth/**)` → `/auth/login`만으로 축소 (유래: SECURITY.md §8) | 현재 auth-service에 다른 공개 엔드포인트가 없어 실노출은 없지만, 앞으로 추가될 경로가 의도치 않게 열리는 잠재 위험 | 한 줄 수정이지만 실노출이 없어 후순위 — auth 엔드포인트 추가 시점에 반드시 함께 처리 |
| Flyway `connectRetries`·Hikari `initializationFailTimeout` (유래: PHASE-16 §8-10) | 파드 기동 경합 시 DB보다 앱이 먼저 뜨면 CrashLoopBackOff 를 수 회 겪는다 — 재시작으로 수렴은 하지만 기동이 느려지고 로그가 지저분해진다 | k8s 재시작 루프가 결국 수렴해 실사고는 아님 — "기동 품질" 개선 항목으로 이연 |
| SemVer/릴리스 프로세스 — `0.0.1-SNAPSHOT` 고정 탈피 (유래: PHASE-17 §8-7) | jar 버전이 늘 같아 아티팩트만 보고는 버전을 구분할 수 없다(이미지 태그=커밋 SHA가 사실상의 버전 역할) | 커밋 SHA 태깅이 재현성 요구를 이미 충족 — 대외 배포물이 생기는 시점에 도입 |

---

*관련: [README.md](../README.md) 인덱스 · [AUDIT-2026-08.md](AUDIT-2026-08.md)(이 백로그를 만든 감사) · 각 `PHASE-*.md` §8(원래 "해결 Phase" 약속이 적혀 있던 자리, 지금은 여기로 화살표만 남음).*
