# 4차 복습 — 파트 D: 플랫폼과 배포 (Phase 16~19)

> 파트 A~C 는 **애플리케이션**을 만들었습니다. 파트 D 는 그걸 **어디서 어떻게 돌리느냐**를 다룹니다.
> 여기서 흥미로운 일이 벌어집니다 — **애플리케이션 코드가 오히려 줄어듭니다.**
> Eureka(디스커버리)와 Config Server(설정)가 **삭제**되고 그 역할이 플랫폼으로 넘어가기 때문입니다.
> 파트 D 의 관통하는 질문은 이겁니다: **"이 일은 애플리케이션의 일인가, 플랫폼의 일인가?"**

---

## 1. 큰 그림 — 배포가 네 단계에 걸쳐 어떻게 변했나

```
Phase 15 까지     내가 손으로: ./gradlew bootJar → docker build → docker compose up
                  · 지금 뜬 게 어느 커밋인지 아무도 모름

Phase 16 (k8s)    내가 손으로: build-and-load.sh → apply.sh (kubectl apply -f 여러 개)
                  · 얻은 것: 자가치유·스케일·probe / Eureka·Config Server 삭제
                  · 남은 것: 여전히 내가 친다

Phase 17 (CI)     push → [빌드·테스트 → 멀티아치 이미지 → GHCR → CI 안 kind 스모크]
                  · 얻은 것: "이 커밋은 배포 가능하다"는 증명, 이미지에 커밋 SHA
                  · 남은 것: 진짜 클러스터엔 여전히 내가 apply

Phase 18 (선언)   kubectl apply -k overlays/local  (한 줄)
                  · 얻은 것: base/overlay, 설정 변경 → 자동 롤아웃(해시), CI 의 sed 제거
                  · 남은 것: apply 를 누르는 사람 = 나

Phase 19 (GitOps) git push  ← 내가 치는 명령은 이것 하나
                  CI 가 이미지 만들고 → 태그를 Git 에 커밋 → Argo CD 가 보고 스스로 배포
                  · 얻은 것: 드리프트 복원(selfHeal), prune, CI 가 클러스터 자격증명 불필요
```

**핵심 한 문장:** 파트 D 는 **"사람이 하던 일을 하나씩 선언으로 바꾸는" 이야기**다.
그리고 매 단계마다 **무엇을 잃는지**도 같이 봤다(Phase 19 는 비밀의 자동 롤아웃을 잃었다).

### 지금의 배치

```
   Git (github.com/jyh4358/msa-practice)  ← 배포의 단일 진실
     deploy/config/*.yml        ← compose 와 k8s 가 '같은 파일'을 쓴다
     deploy/k8s/base/           ← 어디서나 참인 매니페스트
     deploy/k8s/overlays/
        local  (내 노트북·NodePort 디버그)
        ci     (CI 스모크·버려짐)
        gitops (Argo CD 가 보는 것·봇이 커밋)
              ▲                          │
              │ CI 봇이 이미지 태그 커밋   │ Argo CD 가 60초마다 당겨 본다
              │                          ▼
   ┌──────────────────────────────────────────────┐
   │ kind 클러스터 (Colima 12GB)                   │
   │  ns argocd    : controller·repo-server·server·redis
   │  ns ingress-nginx : Helm 릴리스               │
   │  ns shopsaga  : 13 파드 (GHCR 이미지)          │
   └──────────────────────────────────────────────┘
```

---

## 2. 단계별 회고 (무엇을·왜)

| Phase | 무엇을 | 왜(어떤 문제를 풀려고) |
|---|---|---|
| **16a** 로컬 k8s | kind, Deployment/Service/ConfigMap/Secret, **liveness≠readiness**, NodePort, 자가치유·스케일 | 단일 호스트 compose 는 **죽으면 끝**이고 스케일이 없다 |
| **16b** 전체 이전 | **Eureka·Config Server 삭제** → 플랫폼 DNS + ConfigMap, Ingress, compose 동반 이전 | 디스커버리·설정은 **애플리케이션의 일이 아니라 플랫폼의 일**이었다 |
| **17** CI/CD | GitHub Actions, jar 1회 빌드 + **네이티브 러너 2개**로 멀티아치, GHCR `:커밋SHA`, CI 안 스모크 | 사람이 빌드하니 **뜬 게 어느 커밋인지 모른다** |
| **18** 선언적 배포 | Kustomize base/overlay, **생성기 해시 → 자동 롤아웃**, `sed` 제거, ingress-nginx 를 Helm 으로 | 배포가 **셸 스크립트**라 읽을 수도 되돌릴 수도 없다 |
| **19** GitOps | Argo CD, **CI→Git 승격**, `selfHeal`·`prune` | `apply` 를 누르는 게 사람이면 **드리프트를 막을 수 없다** |

> **관통하는 실 ①:** 16b 에서 **코드가 줄었다.** 서비스 2개(discovery·config)와 `config-repo` 가 통째로 삭제됐다.
> 좋은 플랫폼은 애플리케이션이 짊어지던 짐을 가져간다.
>
> **관통하는 실 ②:** 18 → 19 에서 **하나를 얻고 하나를 잃었다.**
> 얻은 것: 자동 배포·드리프트 복원·prune. 잃은 것: **비밀의 자동 롤아웃**(§4 퀴즈 7번).

---

## 3. 핵심 개념 자가진단

### 쿠버네티스 기본기 (16)
- [ ] ★ **liveness ≠ readiness**: 각각 실패하면 무슨 일이 일어나나? **liveness 에 DB 를 넣으면 왜 재앙인가?** → `PHASE-16-KUBERNETES.md`
- [ ] **startupProbe** 는 왜 따로 있나(느린 기동과 liveness 의 충돌) → 〃
- [ ] **Deployment ↔ ReplicaSet ↔ Pod** 관계. 롤링 업데이트의 단위는? → 〃
- [ ] **Service 와 EndpointSlice**: "파드가 Ready" 와 "Service 로 트래픽이 간다"는 왜 다른 사건인가? → 〃
- [ ] **Ingress 와 Ingress 컨트롤러**의 차이(오브젝트만 만들면 아무 일도 안 일어난다) → 〃
- [ ] ★ **k8s 에는 `depends_on` 이 없다.** 그런데 딱 한 곳에 `initContainer` 를 뒀다 — 어디이고 왜인가? → 〃
- [ ] **Secret 은 암호화가 아니다** — 그럼 ConfigMap 보다 나은 점이 뭔가? → 〃

### 플랫폼으로의 이동 (16b)
- [ ] ★ **Eureka 를 지울 수 있었던 이유.** 그 역할을 무엇이 대신하나? 잃은 기능은 없나? → `PHASE-16-KUBERNETES.md` (16b)
- [ ] **Config Server → ConfigMap**: `{cipher}` 복호화 키가 필요 없어진 이유는? → 〃
- [ ] **설정 3층**(jar 기본값 / 클래스패스 공통 / `deploy/config`)이 각각 무엇을 담나? → 〃

### CI/CD (17)
- [ ] **jar 을 한 번만 빌드하는 이유**(아키텍처별로 다시 안 만드는 근거) → `PHASE-17-CICD.md`
- [ ] **에뮬레이션 대신 네이티브 러너 2개**를 쓴 이유와, `imagetools create` 가 하는 일 → 〃
- [ ] **이미지 태그를 커밋 SHA 로** 다는 것의 가치 → 〃
- [ ] ⚠️ **CI 가 빨라지자 드러난 버그**가 있었다. 무엇이고 왜 느릴 땐 안 보였나? → 〃 §7-⑦

### 선언적 배포 (18)
- [ ] **Kustomize vs Helm**: 접근이 어떻게 다른가? "내 것은 Kustomize, 남의 것은 Helm" 의 근거는? → `PHASE-18-KUSTOMIZE.md`
- [ ] **base 와 overlay 의 경계**를 어디에 긋나(판단 기준 한 문장) → 〃
- [ ] ★ **생성기 해시 접미사가 왜 롤링 업데이트를 일으키나?** ConfigMap 내용은 파드 템플릿에 없는데? → 〃
- [ ] ★ **DB 비밀번호는 일부러 생성기로 만들지 않았다.** 왜? → 〃
- [ ] **`sed` 대신 `images:` 변환기**를 쓰는 이유(더러워서가 아니다) → 〃

### GitOps (19)
- [ ] **push 방식 vs pull 방식** 배포. pull 이 보안상 나은 이유는? → `PHASE-19-GITOPS.md`
- [ ] **sync 와 health 의 차이**. `Synced + Degraded` 는 무슨 뜻인가? → 〃
- [ ] ★ **Argo CD 에서 `secretGenerator` 를 못 쓰는 이유**. 그래서 무엇을 잃었나? → 〃
- [ ] **prune 이 안 지우는 리소스**가 있다. 무엇을 기준으로 판단하나? → 〃
- [ ] **selfHeal 은 변경을 '막지' 않는다** — 그럼 무엇을 하나? 정말 막으려면? → 〃

---

## 4. 셀프 퀴즈 (답은 아래 접힘)

1. readiness probe 에 DB 조회를 넣는 건 괜찮은데 **liveness 에 넣으면** 왜 재앙인가?
2. 파드가 전부 `Running 1/1` 인데 Saga 가 `PENDING` 에서 멈췄다. Kafka 도 살아 있다. 무엇을 의심하나?
3. Phase 16b 에서 Eureka 를 **지울 수 있었던** 근거는? 대신 무엇이 그 일을 하나?
4. 같은 `deploy/config/common.yml` 파일을 compose 는 바인드 마운트하고, k8s 는 ConfigMap 으로 만든다. 이게 왜 중요한가?
5. Phase 17 에서 CI **1차는 통과했는데 2차가 실패**했다. 코드는 그대로였다. 왜?
6. Phase 18 에서 `deploy/config/kustomization.yaml` 이 **`deploy/k8s/` 가 아니라 `deploy/config/` 에** 있는 이유는?
7. ★ Phase 19 에서 `secretGenerator` 를 걷어냈다. **왜 걷어내야 했고, 그래서 무엇을 잃었나?**
8. Argo CD 가 `Synced` 인데 `health` 가 10분째 `Progressing` 이다. 어디를 보나?
9. `kubectl scale deploy/auth-service --replicas=5` 를 쳤다. 명령은 성공하나? 5분 뒤엔?
10. Phase 18 이 남긴 "옛 해시 ConfigMap 누적"을 Phase 19 의 `prune` 이 해결했다. 그런데 **일부는 안 지워졌다.** 왜?
11. CI 봇이 Git 에 커밋하는데, 그 커밋이 다시 CI 를 트리거하지 않는 이유는?

<details><summary>정답 펼치기</summary>

1. **liveness 실패는 컨테이너를 죽인다.** DB 가 잠깐 흔들리면 **모든 파드가 동시에 재시작**되고, 재시작한 파드가 다시 DB 에 몰려 상황을 악화시킨다(재시작 루프). readiness 는 **트래픽만 끊고 파드는 살려 두므로** DB 가 돌아오면 그대로 복귀한다. 규칙: **liveness 에 외부 의존성 금지.**
2. **토픽이 없을 가능성.** Spring 의 `KafkaAdmin` 은 `NewTopic` 빈을 **기동 시 딱 한 번** 만들고, 브로커가 아직 없으면 조용히 실패한 뒤 재시도하지 않는다. 그래서 파드는 초록불인데 발행이 `UNKNOWN_TOPIC_OR_PARTITION` 으로 계속 실패한다. → 그 한 곳에만 `initContainer` 로 Kafka 를 기다리게 했다.
3. **쿠버네티스 Service 가 이름을 주소로 바꿔 주기 때문이다**(`http://order-service:8080` → CoreDNS → ClusterIP → EndpointSlice 의 Ready 파드). 즉 Eureka 가 하던 등록·조회·헬스 기반 제외를 **플랫폼이 이미 한다.** 잃은 건 클라이언트 사이드 LB 의 세밀한 제어 정도이고, 대신 `@LoadBalanced` 를 떼어냈다.
4. **설정이 두 벌로 갈라지지 않기 때문이다.** compose 와 k8s 가 각자 설정을 갖고 있으면 한쪽만 고치는 사고가 반드시 난다. 파일 하나를 두 방식으로 **주입만** 다르게 한다. (Phase 18 에서 Kustomize 로 옮길 때도 이 구조를 지키느라 로드 제한을 우회해야 했다.)
5. **캐시 덕에 빨라졌기 때문이다.** 1차는 이미지 pull 이 느려서 ingress-nginx 의 admission webhook 이 준비될 시간을 **우연히** 벌었다. 2차는 캐시로 빨라져 그 창이 사라졌고 `connection refused` 로 깨졌다. **경쟁 조건은 빨라질 때 드러난다.**
6. **Kustomize 는 kustomization 디렉터리 바깥 파일을 못 읽는다**(`LoadRestrictionsRootOnly`, 공급망 공격 방지). `deploy/config/` 를 스스로 kustomization 루트로 만들고 상위에서 `resources: [../../config]` 로 끌어오면 제한에 안 걸리면서 **compose 와의 파일 공유도 유지**된다.
7. **Argo CD 는 Git 저장소를 클론해서 렌더**한다. 개인키는 `.gitignore` 대상이라 Git 에 없으므로 `kustomize build` 가 영원히 실패한다. 부트스트랩 스크립트를 대신 돌려 줄 수도 없다(Argo CD 는 선언만 읽고 명령은 실행하지 않는다 — 그게 안전성이다). 그래서 서명 키를 `kubectl` 로 넣는 **클러스터 사전 조건**으로 옮겼고, **해시 접미사가 사라져 키를 갈아도 파드가 자동으로 안 갈린다**. 제대로 된 답은 Sealed Secrets / External Secrets Operator.
8. **`kubectl get events`.** `Synced` 는 "Git 대로 만들었다"까지만 보장한다. Git 에 **없는 전제**(대표적으로 `auth-jwt-key` Secret)가 빠지면 파드가 못 뜨고 계속 `Progressing` 이다. 실제로 이 프로젝트에서 그 일이 있었다(부트스트랩을 건너뛴 실수).
9. **성공한다.** 실제로 replicas 가 5가 되고 파드가 늘어난다. 그리고 **약 6초 뒤 Argo CD 의 selfHeal 이 Git 값(2)으로 되돌린다.** selfHeal 은 **차단이 아니라 복원**이다. 정말 막으려면 RBAC 으로 사람의 쓰기 권한을 빼야 한다.
10. **Argo CD 는 자기가 만든 것만 지운다.** 판단 기준은 `argocd.argoproj.io/tracking-id` 어노테이션이다. Phase 18 시절 `kubectl apply -k` 로 만들어진 ConfigMap 에는 그 표시가 없으므로 "남의 것"으로 보고 건드리지 않는다. 전환 시점에 **한 번은 손으로** 치워야 한다(안전장치이기도 하다).
11. **`paths-ignore`.** 봇은 `deploy/k8s/overlays/gitops/**` 만 건드리고, push 트리거가 그 경로를 무시하도록 돼 있다. (커밋 메시지의 `[skip ci]` 는 이중 안전장치.) 이게 없으면 커밋 → CI → 커밋 → … 무한 루프가 된다.

</details>

---

## 5. 재현 체크리스트

> ⚠️ compose 와 kind 를 **동시에 띄우지 말 것**(RAM + 포트 8000 충돌).

**기본 기동**
- [ ] `colima start` → `docker start shopsaga-control-plane` → 13파드 + argocd 4파드 Ready.
- [ ] `curl localhost:8000/actuator/health` → 200, 로그인 → 주문 → `CONFIRMED`.

**쿠버네티스 감각 (16)**
- [ ] `kubectl delete pod -n shopsaga -l app=order-service` → 저절로 다시 생기는지(자가치유).
- [ ] `kubectl scale deploy/order-service -n shopsaga --replicas=3` → 요청이 여러 파드로 분산되는지.
- [ ] ★ `kubectl scale deploy/order-db --replicas=0` → **readiness 는 죽고 liveness 는 산다**(RESTARTS 가 안 늘어남)를 눈으로. 다시 1로 올리면 자동 복귀.

**선언적 배포 (18)**
- [ ] `kubectl kustomize deploy/k8s/overlays/local` — 적용 **전에** 최종 YAML 을 눈으로.
- [ ] `kubectl diff -k deploy/k8s/overlays/local` — 지금 클러스터와의 차이.
- [ ] `deploy/config/common.yml` 을 한 글자 고치고 렌더 → **ConfigMap 이름의 해시가 바뀌는지** 확인.

**GitOps (19)**
- [ ] `kubectl get application shopsaga -n argocd -o wide` → `Synced/Healthy`.
- [ ] `kubectl port-forward svc/argocd-server -n argocd 8081:80` → UI 에서 리소스 트리 보기.
- [ ] ★ **selfHeal**: `kubectl scale deploy/auth-service --replicas=5` → 몇 초 뒤 되돌아오는지 **시간을 재본다**.
- [ ] ★ **전체 루프**: 설정을 한 줄 고쳐 `git push` → CI 통과 → **봇 커밋** 확인(`git log origin/main`) → Argo CD 리비전 전환 → 파드 교체까지, **`kubectl` 을 한 번도 치지 않고** 관찰.
- [ ] `git log deploy/k8s/overlays/gitops/` — **이게 곧 배포 이력**임을 확인.

---

## 6. 아직 열려 있는 한계 (누적) → 어디서 해결되나

| 한계 | 어디서 |
|---|---|
| ★ **키 교체 시 자동 롤아웃 없음**(Phase 19 가 되돌린 것) | **Sealed Secrets / ESO** |
| **DB 비밀번호가 Git 에 평문**(dev 값) | 위와 같음 |
| **롤백 미검증** — `git revert` 로 될 것이나 실측 안 함 | 다음 기회 |
| Argo CD UI 가 port-forward 로만(서브경로는 업스트림 이슈) | 이슈 해결 대기 |
| 폴링 60초 — 즉시 반영 아님 | webhook(클러스터가 인터넷에 노출돼야 함) |
| 컨테이너 **root 실행** · NetworkPolicy 없음 | 보안 기본값 강화 |
| 이미지 **취약점 스캔·서명 없음** | Trivy · cosign |
| **HPA 불가**(metrics-server 없음) | metrics-server + HPA |
| DB·Kafka 가 Deployment(StatefulSet 아님) · 단일 노드 · 단일 브로커 | 운영이라면 오퍼레이터 |
| CI 스모크가 **행복 경로 하나** | 실패 경로 시나리오 추가 |
| 환경이 **하나**(gitops 오버레이 = 사실상 prod) | staging/prod 분리 + ApplicationSet |
| Argo CD 가 **단일 장애점** | HA 모드 |

---

## 7. 여기까지 오면

파트 A~D 로 **Phase 0~19** 를 한 바퀴 돌았습니다. 스스로 확인해 볼 것:

- [ ] Phase 2 에서 **깨진 것**(분산 트랜잭션)이 어디서 **어떻게** 복구됐는지 한 호흡에 설명할 수 있다. (2 → 10 → 12/13 → 14)
- [ ] 같은 문제(Saga)를 **두 방식**으로 푼 경험에서, 무엇을 기준으로 고를지 말할 수 있다.
- [ ] "이 일은 애플리케이션의 일인가 플랫폼의 일인가" 를 Eureka·Config Server 사례로 설명할 수 있다.
- [ ] 배포가 **명령형 → 선언형 → GitOps** 로 가면서 매 단계 **무엇을 얻고 무엇을 잃었는지** 말할 수 있다.
- [ ] 각 Phase 문서의 **§8 한계표**가 다음 Phase 의 시작점이었다는 흐름이 보인다.

> 막히는 항목이 있으면 그 Phase 문서로 돌아가세요. **막히는 곳이 곧 다음에 볼 곳**입니다.

*관련: [파트 A](REVIEW-PART-A.md) · [파트 B](REVIEW-PART-B.md) · [파트 C](REVIEW-PART-C.md) ·
[README](../README.md) 인덱스 · `PHASE-16`~`PHASE-19` 심화 문서 · [커밋 지도](PHASE-COMMIT-MAP.md).*
