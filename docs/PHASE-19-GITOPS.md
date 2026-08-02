# Phase 19 — GitOps (Argo CD · CI→Git 승격 루프 · self-heal · prune)

> **한 줄 요약:** Phase 17 의 CI 는 "이 커밋은 배포해도 된다"를 **증명**했고,
> Phase 18 의 Kustomize 는 배포될 것을 **선언**으로 만들었다.
> 그런데 `kubectl apply` 를 누르는 건 여전히 **나**였다.
> 이 단계에서 그 손마저 없앤다 — 클러스터 안의 Argo CD 가 Git 을 보고 **스스로** 맞춘다.

초심자(쿠버네티스와 CI 는 앞 단계에서 했고 GitOps 는 처음) 기준으로
**왜 → 무엇을 → 어떻게** 순서로 설명합니다.

---

## 0. 이번 단계에서 한 일 (요약)

- **Argo CD**(차트 10.2.2 / v3.4.6)를 Helm 으로 설치했다. dex·notifications 를 끄고
  applicationSet 을 `replicas: 0` 으로 두어 **파드 7 → 4** 로 줄였다.
- **`deploy/k8s/overlays/gitops`** 를 새로 만들었다. Argo CD 가 보는 **유일한** 경로다.
- **CI 에 `gitops 승격` 잡을 추가**했다 — 스모크가 통과하면 `kustomize edit set image` 로
  이미지 태그를 그 오버레이에 **커밋·push** 한다. 그러면 Argo CD 가 알아서 배포한다.
  `paths-ignore` 로 봇 커밋이 CI 를 다시 부르지 않게 막았다(무한 루프 방지).
- **`prune: true` 가 Phase 18 의 한계 #2 를 해결했다** — 설정을 고칠 때마다 쌓이던
  옛 해시 ConfigMap 을 Argo CD 가 치운다(§7-④ 에 실측).
- **`selfHeal: true`** — `kubectl` 로 손댄 것을 **6초** 만에 Git 상태로 되돌린다(§7-③).
- ⚠️ **secretGenerator 를 걷어내야 했다.** Argo CD 는 Git 을 클론해 렌더하므로
  `.gitignore` 된 개인키를 읽을 수 없다. 서명 키는 `bootstrap-secrets.sh` 가 넣는
  **클러스터 사전 조건**이 됐다 — 그 대가로 키 교체 시 자동 롤아웃을 잃었다(§8-3).

**바뀌지 않은 것:** 애플리케이션 코드 0줄. base 매니페스트도 그대로다.

---

## 1. 왜 — CI 까지 했는데 뭐가 부족한가

Phase 17 이 끝났을 때 파이프라인은 이랬다.

```
push → 빌드·테스트 → 이미지 → GHCR → (CI 안에서) kind 에 배포해 스모크
```

마지막 칸이 함정이다. **CI 가 배포한 건 "일회용 kind"** 였다. 진짜 클러스터에는
내가 `./deploy/k8s/apply.sh` 를 쳐서 반영했다. 여기서 네 가지가 새어 나온다.

**① 클러스터의 실제 상태를 아무도 기록하지 않는다.**
Git 은 "무엇을 배포해야 하는지"를 알지만, "무엇이 배포**됐는지**"는 모른다.
누가 급해서 `kubectl edit` 로 replicas 를 바꿔 놨다면? 그건 어디에도 안 남는다.
**설정 드리프트**(configuration drift)라고 부르는 이 현상이 운영 사고의 단골 원인이다.

**② 배포가 사람의 기억에 의존한다.**
"설정 바꿨으니 apply 해야지"를 잊으면 Git 과 클러스터가 조용히 갈라진다.
Phase 18 이 `--config` 플래그를 없앤 것도 같은 종류의 문제였다.

**③ CI 가 클러스터 자격증명을 갖게 된다.**
진짜 클러스터에 CI 가 배포하려면 러너에 kubeconfig 를 줘야 한다.
러너는 인터넷에 있고, 워크플로는 PR 로 수정될 수 있으며, 서드파티 액션도 돈다.
**"클러스터를 지울 수 있는 권한"이 그 안에 있다는 뜻**이다.

**④ 롤백이 어렵다.**
"어제 상태로 되돌려" 를 하려면 어제 이미지 태그를 찾아 매니페스트를 고쳐 다시 apply 해야 한다.

### GitOps 가 뒤집는 것: push → pull

```
지금까지 (push 방식)        CI 가 클러스터 쪽으로 '밀어 넣는다'
                            → 자격증명이 바깥에 있어야 한다

GitOps (pull 방식)          클러스터 안의 에이전트가 Git 을 '당겨 온다'
                            → 자격증명이 안쪽에만 있다. CI 는 클러스터를 모른다
```

그리고 **"Git 이 곧 배포 상태"** 가 되므로 ①②④가 한꺼번에 풀린다.
롤백은 `git revert` 다.

---

## 2. 개념 — 최소 용어

| 용어 | 뜻 |
|---|---|
| **GitOps** | 배포 상태를 Git 에 선언해 두고, 클러스터가 그걸 계속 따라가게 하는 운영 방식 |
| **Argo CD** | 그 "따라가게 하는" 컨트롤러. 클러스터 안에서 돈다 |
| **Application** | Argo CD 의 CRD. "이 Git 경로와 이 클러스터 네임스페이스가 같아야 한다"는 선언 |
| **sync** | Git 과 클러스터를 맞추는 동작. `Synced` / `OutOfSync` |
| **health** | 맞춘 결과가 실제로 잘 도는지. `Healthy` / `Progressing` / `Degraded` |
| **drift(드리프트)** | 선언과 실제가 갈라진 상태 |
| **selfHeal** | 드리프트를 발견하면 Git 쪽으로 되돌리는 옵션 |
| **prune** | Git 에서 사라진 리소스를 클러스터에서도 지우는 옵션 |
| **tracking-id** | Argo CD 가 "이건 내가 만든 것"이라고 표시해 두는 어노테이션 |
| **승격(promotion)** | 검증된 산출물을 다음 환경의 선언에 기록하는 일. 여기선 "이미지 태그를 Git 에 쓰기" |

### sync ≠ health (헷갈리기 쉬움)

```
Synced   + Healthy      정상
Synced   + Progressing  Git 대로 만들었고, 파드가 아직 뜨는 중
Synced   + Degraded     Git 대로 만들었는데 그게 안 돈다 ← Git 내용이 틀렸다는 뜻
OutOfSync               클러스터가 Git 과 다르다(자동 동기화면 곧 맞춰진다)
```

이번 Phase 에서 실제로 `Synced + Progressing` 이 10분간 지속되는 걸 겪었다.
"동기화는 됐는데 안 뜬다" = **매니페스트는 적용됐지만 전제 조건이 없었다**(§7-②).

---

## 3. 구성 — 전체 그림

```
   ┌──────────────┐  push
   │  개발자      │────────────┐
   └──────────────┘            ▼
                        ┌─────────────────────────────────────┐
                        │ GitHub  jyh4358/msa-practice        │
                        │                                     │
                        │  deploy/k8s/base/         (공통)     │
                        │  deploy/config/           (설정)     │
                        │  deploy/k8s/overlays/gitops/ ◄────┐  │
                        └──────────┬──────────────────────┼──┘
                                   │ ① 트리거              │
                                   ▼                      │
                        ┌─────────────────────────┐       │
                        │ GitHub Actions (CI)     │       │
                        │  빌드·테스트             │       │
                        │  이미지 → GHCR :<SHA>    │       │
                        │  kind 스모크             │       │
                        │  ★ gitops 승격 ─────────┼───────┘ ② 태그를 커밋·push
                        └─────────────────────────┘         (봇 커밋)
                                   ┆
                                   ┆ CI 는 클러스터를 모른다 (자격증명 없음)
   ═══════════════════════════════ ┆ ═══════════════════════════════════
                                   ┆
   내 노트북 (kind)                 ▼ ③ Argo CD 가 60초마다 Git 을 당겨 본다
   ┌────────────────────────────────────────────────────┐
   │ ns: argocd                                         │
   │   application-controller  ← 비교하고 맞춘다          │
   │   repo-server             ← git clone + kustomize   │
   │   server (UI/API) · redis                          │
   │        │ ④ 다르면 apply                             │
   │        ▼                                           │
   │ ns: shopsaga   13 파드 (GHCR 이미지)                │
   └────────────────────────────────────────────────────┘
```

### 오버레이가 셋이 된 이유

| 오버레이 | 누가 쓰나 | 이미지 | 커밋되나 |
|---|---|---|---|
| `local` | 내가 `apply.sh` 로 | `kind load` 한 로컬 이미지 | 태그 고정 |
| `ci` | CI 스모크 잡이 | GHCR `:<SHA>` | ❌ 러너에서 고쳐 쓰고 버린다 |
| **`gitops`** | **Argo CD 가** | GHCR `:<SHA>` | ✅ **봇이 커밋한다** |

`ci` 와 `gitops` 를 합치고 싶어지는데, 합치면 안 된다 —
"검증하려고 잠깐 바꾼 것"과 "배포하기로 결정한 것"이 구분되지 않는다.

---

## 4. 코드 — 결정적인 부분

### ① Application: 명령이 아니라 불변식

```yaml
# deploy/k8s/argocd-application.yaml
spec:
  source:
    repoURL: https://github.com/jyh4358/msa-practice.git
    targetRevision: main
    path: deploy/k8s/overlays/gitops     # ← kustomization.yaml 이 있으면 알아서 kustomize build
  destination:
    server: https://kubernetes.default.svc
    namespace: shopsaga
  syncPolicy:
    automated:
      prune: true        # Git 에서 사라지면 클러스터에서도 지운다
      selfHeal: true     # 손으로 바꾼 걸 되돌린다
```

이 파일에 **"배포하라"는 말이 없다.** "이 둘이 같아야 한다"만 있다.
컨트롤러는 그 불변식이 깨지면 고친다 — 명령형과 선언형의 차이가 여기서 제일 선명하다.

> ⚠️ `prune` 과 `selfHeal` 은 **기본값이 false** 다. 켜지 않으면
> "만들기만 하고 지우지도 되돌리지도 않는" 반쪽짜리가 된다.

### ② CI 의 승격 잡 — 여기가 CI 와 CD 의 경계

```yaml
gitops:
  needs: smoke                     # 스모크가 통과해야 승격
  if: github.event_name == 'push' && github.ref == 'refs/heads/main'
  permissions:
    contents: write                # ★ 없으면 push 가 403
  steps:
    - working-directory: deploy/k8s/overlays/gitops
      run: |
        for svc in $SERVICES; do
          kustomize edit set image "shopsaga/${svc}=${IMAGE_BASE}/${svc}:${GITHUB_SHA}"
        done
    - run: |
        git config user.name "github-actions[bot]"
        if git diff --quiet -- deploy/k8s/overlays/gitops; then
          echo "이미 최신 태그다"; exit 0        # ← 변경 없으면 commit 이 실패하므로 먼저 확인
        fi
        git commit -m "chore(gitops): 이미지 태그를 ${GITHUB_SHA} 로 갱신 [skip ci]"
        git push
```

**이 잡은 클러스터에 접속하지 않는다.** Git 에 쓰기만 한다.
그래서 CI 러너에 kubeconfig 가 필요 없다 — §1-③ 이 여기서 해결된다.

### ③ 무한 루프 방지

봇이 push 하면 그것도 push 이므로 CI 가 다시 돈다 → 또 커밋 → 끝나지 않는다.

```yaml
on:
  push:
    branches: [main]
    paths-ignore:
      - 'deploy/k8s/overlays/gitops/**'    # 봇은 여기만 건드린다
```

`paths-ignore` 는 변경 파일이 **전부** 패턴에 맞을 때만 건너뛴다.
사람이 코드와 이 파일을 같이 고치면 CI 는 정상적으로 돈다.
커밋 메시지의 `[skip ci]` 는 이중 안전장치다.

> ⚠️ 대가: 이 파일만 손으로 고쳐 push 하면 CI 가 안 돈다.
> 배포는 Argo CD 가 하니 의도한 동작이지만, 알고 있어야 한다.

### ④ ★ secretGenerator 를 걷어내야 했다 — 이번 Phase 에서 가장 아팠던 부분

Phase 18 에서는 이랬다.

```yaml
# base/kustomization.yaml (Phase 18)
secretGenerator:
  - name: auth-jwt-key
    files: [private-key=.secrets/auth-jwt-key.pem]   # .gitignore 대상
```

**Argo CD 에서는 이게 구조적으로 불가능하다.**

```
Argo CD repo-server: git clone → kustomize build
  → .secrets/ 는 Git 에 없다 → 렌더 실패 → 영원히 Degraded
```

부트스트랩 스크립트를 대신 돌려 줄 수도 없다. Argo CD 는 **임의 스크립트를 실행하지 않는다** —
그게 GitOps 의 안전성이기도 하다(선언만 읽고 명령은 안 읽는다).

그래서 서명 키를 **매니페스트 밖의 클러스터 사전 조건**으로 옮겼다.

```bash
# deploy/k8s/bootstrap-secrets.sh
kubectl create secret generic auth-jwt-key -n shopsaga \
  --from-file=private-key="$SECRETS/auth-jwt-key.pem" \
  --from-file=key-id="$SECRETS/auth-jwt-key-id.txt"
```

**잃은 것**: 해시 접미사가 사라져서 **키를 갈아도 파드가 자동으로 갈리지 않는다.**
Phase 18 이 얻었던 성질 하나를 되돌린 것이다. 정직하게 한계표(§8-3)에 남긴다.
제대로 된 답은 **Sealed Secrets / External Secrets Operator** — 암호문을 Git 에 두거나
외부 저장소에서 받아오면 생성기를 다시 쓸 수 있다.

---

## 5. 흐름 — 커밋 하나가 클러스터에 닿기까지

실제로 측정한 타임라인이다(§7 의 실측을 시간순으로 정리).

```
 t=0      나: git push (46e0c69 — deploy/config/common.yml 한 줄 수정)
 t~4m     CI: 빌드·테스트 → 이미지 6종 → GHCR → kind 스모크 통과
 t~4m     CI: gitops 승격 잡
            kustomize edit set image ×6 → 커밋 → push (봇 커밋)
 t~5m     Argo CD: 폴링(60초 주기)에서 새 리비전 발견 → 자동 sync
            · configMapGenerator 새 해시 → shopsaga-common-52mkbkthhm 생성
            · 옛 shopsaga-common-2497kkbgk6 → prune(삭제)
            · 파드 템플릿이 바뀌었으므로 롤링 업데이트
 t~7m     13 파드 Ready · Saga CONFIRMED 5초
```

**이 전체 과정에서 내가 친 명령은 `git push` 하나다.**

---

## 6. 원리 — 왜 이렇게 동작하나

### ① prune 은 "자기가 만든 것"만 지운다

Argo CD 는 자기가 만든 리소스에 표시를 남긴다.

```
shopsaga-common-52mkbkthhm  →  argocd.argoproj.io/tracking-id: shopsaga:/ConfigMap:shopsaga/...
shopsaga-common-49f7g8bmk6  →  (없음)   ← Phase 18 에서 kubectl apply -k 로 만든 것
```

그래서 Argo CD 를 붙이기 **전에** 생긴 고아는 prune 되지 않는다(§7-④ 실측).
"Argo CD 를 켰으니 알아서 청소되겠지"가 아니라 **한 번은 손으로 정리해야 한다.**

이건 안전장치이기도 하다 — 남이 만든 리소스를 함부로 지우지 않는다.

### ② selfHeal 은 "되돌리기"지 "막기"가 아니다

`kubectl scale` 은 **성공한다.** 6초 뒤에 Argo CD 가 되돌릴 뿐이다.
그 사이에 진짜로 파드가 5개로 늘었다가 2개로 줄었다.

즉 selfHeal 은 변경을 **차단**하지 않는다. 정말 막으려면 RBAC 으로 사람의 쓰기 권한을 빼야 한다.
GitOps 를 켰다고 클러스터가 잠기는 게 아니다 — **"손댄 게 오래 가지 못한다"** 는 성질일 뿐이다.

### ③ 폴링 vs webhook

Argo CD 는 기본 180초마다 Git 을 확인한다(우리는 60초로 줄였다).
즉시 반영하려면 GitHub webhook 을 Argo CD 로 보내야 하는데,
**내 노트북 클러스터는 인터넷에서 접근할 수 없다.** 그래서 폴링이 유일한 선택이다(§8-5).

### ④ Argo CD 자신은 GitOps 로 관리하지 않는다

Argo CD 가 자기 자신을 관리하게 만들 수도 있다(app-of-apps).
하지만 그러면 **Argo CD 가 망가졌을 때 그걸 고칠 Argo CD 가 없다.**
부트스트랩은 클러스터 밖에서(여기선 Helm 으로) 하는 게 안전하다.

---

## 7. 검증 — 실제로 측정한 것

> 환경: macOS(Apple Silicon) · Colima 12GB/6CPU · kind v0.32.0 · k8s v1.36.1 ·
> Argo CD 차트 10.2.2 (v3.4.6) · Helm v4.2.3

### ① 설치 — 파드 7 → 4

```
argocd-application-controller-0     1/1 Running
argocd-repo-server-…                1/1 Running
argocd-server-…                     1/1 Running
argocd-redis-…                      1/1 Running
argocd-applicationset-controller    replicas=0   ← 렌더로 확인
```

설치 53초. 메모리 여유는 설치 전 7.4GB 였다.

### ② ★ 첫 동기화가 10분간 Progressing — 내가 만든 실패

`Synced` 는 즉시 됐는데 `health` 가 10분 넘게 `Progressing` 이었다.

```
Warning  Failed   pod/auth-service-…   Error: secret "auth-jwt-key" not found
```

원인: **`install-argocd.sh` 대신 Application 만 직접 apply 해서 부트스트랩을 건너뛰었다.**
`secretGenerator` 를 걷어낸 뒤로 `auth-jwt-key` 는 스크립트가 만들어야 하는데 그걸 안 했다.

**이게 이번 Phase 의 교훈이 그대로 재현된 사건이다** — Argo CD 는 Git 에 있는 것만 만든다.
Git 에 없는 전제(비밀)는 **아무도 대신 해 주지 않고, 증상은 "그냥 안 뜬다"로만 나타난다.**

```
▶ ./deploy/k8s/bootstrap-secrets.sh
  Secret auth-jwt-key 생성
  [30s] sync=Synced health=Healthy  파드 13/13 Ready     ← 30초 만에 정상화
```

### ③ selfHeal — kubectl 로 손댄 것이 되돌아온다

```
Git 의 auth-service replicas : 2
$ kubectl scale deploy/auth-service -n shopsaga --replicas=5
  [1s] replicas=5 sync=Synced
  [6s] replicas=2 sync=Synced      ← ✔ 6초 만에 복원
```

`kubectl scale` 은 **성공했다**(실제로 5가 됐다). Argo CD 가 6초 뒤 되돌렸을 뿐이다.

### ④ ★ prune — Phase 18 한계 #2 가 해결되는 순간

`deploy/config/common.yml` 을 한 줄 고쳐 push 했다(`info.phase: 18 → 19`).

```
  [1s]  rev=36e64d3  shopsaga-common-2497kkbgk6  shopsaga-common-49f7g8bmk6
  [21s] rev=36e64d3  (동일)
  [41s] rev=36e64d3  (동일)
  [62s] rev=46e0c69  shopsaga-common-49f7g8bmk6  shopsaga-common-52mkbkthhm
                     ↑ 옛것(-2497…)이 사라졌다 ← pruned
                     ↑ -49f7… 은 남았다 = Argo 이전에 만들어진 고아
```

| ConfigMap | 만든 주체 | tracking-id | prune 됐나 |
|---|---|---|---|
| `shopsaga-common-2497kkbgk6` | Argo CD | 있음 | ✅ 지워짐 |
| `shopsaga-common-49f7g8bmk6` | Phase 18 의 `kubectl apply -k` | **없음** | ❌ 남음(수동 삭제함) |

**Phase 18 의 한계 #2 는 "Argo CD 가 관리하는 범위 안에서" 해결된다.**
전환 시점의 고아는 한 번 손으로 치워야 한다 — 실제로 ConfigMap 2개 + Secret 1개를 지웠다.

### ⑤ ★ CI → Git → 배포 전체 루프

내가 `894e12d` 를 push 한 뒤, **아무것도 하지 않고** 관찰한 결과:

```
$ git log origin/main --oneline -2
36e64d3  github-actions[bot]  chore(gitops): 이미지 태그를 894e12d4b62… 로 갱신 [skip ci]
894e12d  JDong                feat: GitOps 도입 …

$ git show 36e64d3 --stat
 deploy/k8s/overlays/gitops/kustomization.yaml | 38 ++++++-------    ← 이 파일만

# 그리고 클러스터에 실제로 뜬 이미지:
ghcr.io/jyh4358/msa-practice/auth-service:894e12d4b621637038e2bc4fb1b1ebb0dff09f8f
ghcr.io/jyh4358/msa-practice/gateway-service:894e12d4b62…
… (6종 전부)
```

Argo CD 의 리비전도 `894e12d` → `36e64d3` 으로 **저절로** 넘어갔다(540초 지점에서 관측).
`kubectl apply` 를 친 사람은 없다.

### ⑥ 설정이 파드 안까지 도달했나

```console
$ kubectl exec -n shopsaga <gateway-pod> -- \
    grep -A4 "^info:" /application/config/10-common/application.yml
info:
  pod: ${POD_NAME:${HOSTNAME:unknown}}
  # Phase 19 부터는 그 적용까지 Argo CD 가 한다 — 나는 Git 만 바꾸고 kubectl 을 치지 않는다.
  phase: "19"

$ kubectl get pod -n shopsaga <gateway-pod> -o jsonpath='{.spec.volumes[0].configMap.name}'
shopsaga-common-52mkbkthhm
```

### ⑦ 기능 회귀 없음

```
Ingress → gateway            HTTP 200
로그인(부트스트랩 키)         토큰 발급 OK
Saga: PENDING → INVENTORY_RESERVED → CONFIRMED    5초
Argo CD 최종                 sync=Synced health=Healthy rev=46e0c69
```

### ⑧ 겪은 결함·오진 (숨기지 않고 기록)

**(a) Argo CD 를 `/argocd` 경로로 노출하려다 실패 — 업스트림 이슈**
Grafana 처럼 `localhost:8000/argocd` 로 열고 싶어 `server.rootpath`·`server.basehref` 를 켰다.

```
파드 env  ARGOCD_SERVER_BASEHREF=/argocd     ← 값은 정확히 전달됨
rootpath  동작함 (`/` → 404, `/argocd/` → 200)
그런데    index.html 이 여전히 <base href="/">
          → 브라우저가 /assets/… 를 요청 → 그건 gateway 라우트 → 401
```

Argo CD 의 **알려진 미해결 이슈**다(argoproj/argo-cd #15750 · #9660 · #14857).
→ 서브경로를 포기하고 **port-forward** 로 바꿨다(Argo CD 공식 시작 가이드도 그렇게 한다).

> **오진 정정:** 조사 중 `/argocd/applications` 가 404 라 "딥링크도 깨진다"고 적었는데
> 그건 **curl 아티팩트**였다. Argo CD 는 `Accept` 헤더로 콘텐츠 협상을 해서
> `Accept: */*`(curl 기본)엔 404, `Accept: text/html`(브라우저)엔 200 을 준다.
> 실제 근거는 base href 하나뿐이다.

**(b) `applicationSet.enabled: false` 가 조용히 무시됐다**
차트 10.2.2 의 `applicationSet:` 에는 **`enabled` 키가 없다.**
그런데 **Helm 은 존재하지 않는 값을 줘도 오류를 내지 않는다**(values 스키마를 강제하지 않는다).
`helm template` 으로 렌더해 보지 않았으면 파드가 하나 더 뜨는 걸 몰랐을 것이다.
→ 남의 차트를 쓸 때는 "값을 줬으니 됐겠지"가 아니라 **렌더 결과를 봐야 한다.**
→ `replicas: 0` 으로 해결.

**(c) `global.domain` 기본값이 Ingress 를 망가뜨릴 뻔했다**
차트 기본값이 `argocd.example.com` 이라, `server.ingress.hostname: ""` 로 둬도
거기로 폴백해 `host: argocd.example.com` 규칙이 붙었다. `localhost:8000` 으로는 영원히 안 닿는다.
(결국 Ingress 자체를 안 쓰기로 했지만, 렌더 검증에서 미리 잡았다.)

**(d) 봇 커밋 때문에 로컬 push 가 거부된다**
CI 봇이 `main` 에 커밋하므로, CI 가 한 번 돌 때마다 **내 로컬 브랜치가 뒤처진다.**

```
$ git push
 ! [rejected]  main -> main (fetch first)
```

→ `git pull --rebase` 후 push. GitOps 를 켠 대가로 생긴 **일상적인 마찰**이고,
팀이라면 봇 커밋을 별도 브랜치나 별도 config 저장소로 빼는 이유가 이것이다(§8-6).

---

## 8. 이번 단계의 한계 → 어디서 해결되나

| # | 한계 | 지금 상태 | 어디서 해결되나 |
|---|---|---|---|
| 1 | **Argo CD UI 가 Ingress 에 없다** | port-forward 로만 접근(업스트림 이슈 §7-⑧a) | 이슈 해결 대기 · 또는 전용 호스트명 |
| 2 | **폴링 60초** | 즉시 반영 아님 | GitHub webhook — 단 클러스터가 인터넷에 노출돼야 한다 |
| 3 | **키 교체 시 자동 롤아웃 없음** | secretGenerator 를 잃어 해시 접미사가 없다 | **Sealed Secrets / External Secrets Operator** |
| 4 | **DB 비밀번호가 Git 에 평문** | dev 값이라 감수 | 위와 같음 |
| 5 | **부트스트랩이 여전히 명령형** | `bootstrap-secrets.sh` · ingress-nginx · Argo CD 자신 | 클러스터 프로비저닝 도구(Terraform/Crossplane) |
| 6 | **봇 커밋이 main 을 흔든다** | 매번 `git pull --rebase` 필요 | 설정 저장소 분리(app repo ↔ config repo) |
| 7 | **환경이 하나뿐** | `gitops` 오버레이 하나 = 사실상 prod | staging/prod 분리 + ApplicationSet |
| 8 | **롤백을 안 해 봤다** | `git revert` 로 될 것이나 **미검증** | 다음 기회에 실측 |
| 9 | Argo CD 가 **단일 장애점** | 죽으면 드리프트가 방치된다 | HA 모드(복제본 증가) |
| 10 | 전환 시점 **고아 리소스** | prune 이 안 건드림(§7-④) | 1회 수동 정리(했음) |
| 11 | 컨테이너 root 실행 · NetworkPolicy 없음 | Phase 18 에서 그대로 | 보안 기본값 강화 |
| 12 | 이미지 취약점 스캔·서명 없음 | | Trivy · cosign |

---

## 9. 용어

| 용어 | 뜻 |
|---|---|
| **pull 방식 배포** | 클러스터가 스스로 당겨 오는 방식. 반대는 CI 가 밀어 넣는 push 방식 |
| **드리프트** | 선언(Git)과 실제(클러스터)가 갈라진 상태 |
| **reconciliation(조정)** | 선언과 실제를 비교해 맞추는 반복 동작. 쿠버네티스 컨트롤러의 기본 원리 |
| **app-of-apps** | Application 이 다른 Application 들을 만드는 구성. 대규모에서 쓴다 |
| **finalizer** | 오브젝트를 지울 때 정리 작업을 보장하는 표시. 없으면 Application 만 지워지고 리소스는 남는다 |
| **ServerSideApply** | 필드 소유권을 서버가 추적하는 apply 방식. 큰 매니페스트의 어노테이션 크기 제한을 피한다 |
| **승격(promotion)** | 검증된 산출물을 다음 환경 선언에 기록하는 일 |

---

## 10. 참고

- Argo CD 공식 — <https://argo-cd.readthedocs.io/>
  - Application 스펙 · sync options · tracking 방식
- Argo CD Helm 차트 — <https://github.com/argoproj/argo-helm/tree/main/charts/argo-cd>
- 서브경로 이슈 — argoproj/argo-cd [#15750](https://github.com/argoproj/argo-cd/issues/15750) ·
  [#9660](https://github.com/argoproj/argo-cd/issues/9660) ·
  [#14857](https://github.com/argoproj/argo-cd/issues/14857)
- OpenGitOps 원칙 — <https://opengitops.dev/>
- 이 프로젝트: [Phase 17 — CI/CD](./PHASE-17-CICD.md) · [Phase 18 — 선언적 배포](./PHASE-18-KUSTOMIZE.md)
- `deploy/k8s/README.md` — 실행·정리·함정 표

---

*다음 단계 후보: Sealed Secrets 로 §8-3·4 해결 · `git revert` 롤백 실측(§8-8) ·
Trivy/cosign 공급망 보안 · 보안 기본값(runAsNonRoot·NetworkPolicy) · staging/prod 분리.*
