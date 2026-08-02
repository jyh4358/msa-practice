# Phase 17 — CI/CD (GitHub Actions · 멀티아치 이미지 · GHCR · CI 내 스모크 배포)

> **한 줄 요약:** Phase 16 까지 "빌드하고 배포하는 사람"은 **나**였다.
> `./gradlew bootJar` → `docker build` → `kind load` → `kubectl apply` 를 손으로 쳤고,
> 그래서 **지금 클러스터에 떠 있는 게 어느 커밋인지 아무도 몰랐다.**
> 이 단계에서 그 고리를 기계에게 넘긴다 — push 하면 빌드·테스트·이미지·배포 검증까지 저절로 돈다.

초심자(Java/Spring 은 알지만 CI/CD 는 처음) 기준으로 **왜 → 무엇을 → 어떻게** 순서로 설명합니다.

---

## 0. 이번 단계에서 한 일 (요약)

- **GitHub Actions 파이프라인** 하나(`.github/workflows/ci.yml`), 잡 5개:
  `빌드·테스트` → `이미지(amd64)` · `이미지(arm64)` → `매니페스트 병합` → `kind 스모크 배포`.
- **jar 은 한 번만 빌드**한다. Java 바이트코드는 아키텍처와 무관하므로 artifact 로 넘기고,
  이미지 단계는 **포장만** 한다. 테스트 87개도 한 번만 돈다.
- **에뮬레이션 없이 멀티아치.** `ubuntu-latest`(amd64)와 `ubuntu-24.04-arm`(arm64)에서 각각 **네이티브로** 만들고
  `docker buildx imagetools` 로 하나의 태그에 묶는다.
- **GHCR**(GitHub Container Registry)에 `:<커밋SHA>` 와 `:latest` 로 push → **어느 커밋의 이미지인지 추적 가능**.
- **CI 안에서 kind 클러스터를 띄워** Phase 16 매니페스트를 그대로 적용하고,
  로그인 → 주문 → **Saga 완주(CONFIRMED)** 까지 확인한다. 매니페스트가 깨지면 빌드가 빨간불이 된다.
- PR 에서는 **빌드·테스트만** 돈다(포크 PR 은 레지스트리 쓰기 토큰을 받지 못한다).

---

## 1. 왜 — 손으로 하면 무엇이 문제인가

**① 지금 떠 있는 게 어느 커밋인지 모른다.**
Phase 16 에서 우리는 `shopsaga/order-service:0.0.1` 이라는 태그를 계속 재사용했다.
버그를 발견했을 때 "이 이미지가 어느 소스로 만들어졌나?"에 답할 방법이 없다.
롤백도 불가능하다 — 되돌아갈 이전 이미지가 남아 있지 않다.

**② 테스트를 건너뛸 수 있다.**
`./gradlew bootJar` 는 테스트를 돌리지 않는다. 바쁘면 그냥 이미지를 만들어 올리게 된다.
**사람의 규율에 의존하는 절차는 언젠가 반드시 깨진다.**

**③ "내 노트북에서는 되는데"를 못 잡는다.**
우리 Testcontainers 테스트는 Colima 에 의존하고, 이미지는 arm64 로만 만들었다.
다른 사람의 amd64 리눅스에서 되는지 아무도 모른다.

**④ 매니페스트가 깨져도 배포 직전까지 모른다.**
Phase 16b 에서 실제로 겪었다 — Kafka 부트스트랩 교착도, KafkaAdmin 토픽 미생성도
**직접 배포해 보고 나서야** 발견했다. 그 검증을 자동화하지 않으면 다음에도 똑같이 겪는다.

> **CI/CD 의 본질은 자동화가 아니라 '되돌릴 수 없는 기준선'을 만드는 것이다.**
> 초록불이 아니면 머지하지 않는다는 규칙이 서는 순간, 위 네 가지가 구조적으로 막힌다.

---

## 2. 개념 — 최소 용어

| 용어 | 한 줄 정의 |
|---|---|
| **워크플로(workflow)** | `.github/workflows/*.yml` 파일 하나. 언제(`on:`) 무엇을 할지 정의한다 |
| **잡(job)** | 워크플로 안의 실행 단위. **잡마다 새 가상머신**을 받는다(그래서 파일이 공유되지 않는다) |
| **스텝(step)** | 잡 안의 명령 하나. `run:`(셸) 또는 `uses:`(남이 만든 액션) |
| **러너(runner)** | 잡이 실제로 도는 머신. `ubuntu-latest` = amd64 4vCPU/16GB |
| **artifact** | 잡 사이에 파일을 넘기는 수단. 잡마다 머신이 다르므로 이게 필요하다 |
| **`GITHUB_TOKEN`** | 실행마다 자동 발급되는 임시 토큰. `permissions:` 로 권한을 준다 |
| **GHCR** | GitHub Container Registry(`ghcr.io`). 저장소와 권한이 연결된다 |
| **매니페스트 리스트** | "이 태그는 amd64면 A, arm64면 B" 라는 목록. 멀티아치 이미지의 실체 |

### 왜 잡을 나누나

한 잡에 다 넣으면 더 간단해 보인다. 나눈 이유는 셋이다.

1. **병렬** — amd64·arm64 이미지는 동시에 만들 수 있다.
2. **다른 머신이 필요** — arm64 이미지는 arm64 러너에서 만들어야 네이티브다.
3. **실패 지점이 보인다** — "이미지(arm64)"만 빨간불이면 원인 범위가 즉시 좁혀진다.

대가는 **파일이 공유되지 않는다**는 것이다. 그래서 jar 을 artifact 로 넘긴다.

---

## 3. 구성 — 파이프라인 모양

```
 push to main
      │
      ▼
┌─────────────────┐
│  빌드·테스트     │  ubuntu-latest
│  ./gradlew build│  · 테스트 87개(Testcontainers 포함 — 러너의 Docker 데몬 사용)
│                 │  · 산출물: bootJar 6개  ──────┐
└─────────────────┘                              │ artifact "boot-jars"
      │                                          │  (★ jar 은 아키텍처 무관)
      ├──────────────────┬───────────────────────┘
      ▼                  ▼
┌──────────────┐  ┌──────────────────┐
│ 이미지 amd64  │  │ 이미지 arm64      │   ← 각자 네이티브. QEMU 에뮬레이션 없음
│ ubuntu-latest│  │ ubuntu-24.04-arm │
└──────────────┘  └──────────────────┘
      │                  │
      │ ghcr.io/…:<sha>-amd64      ghcr.io/…:<sha>-arm64
      └────────┬─────────┘
               ▼
      ┌──────────────────┐
      │ 매니페스트 병합    │  docker buildx imagetools create
      │                  │  → ghcr.io/…:<sha>  ·  :latest   (둘 다 멀티아치)
      └──────────────────┘
               │
               ▼
      ┌────────────────────────────────────────┐
      │ kind 스모크 배포  (ubuntu-latest)        │
      │  1. helm/kind-action + 우리 kind-cluster.yaml
      │  2. 매니페스트의 image 를 GHCR 로 교체
      │  3. GHCR pull secret 생성
      │  4. ./deploy/k8s/apply.sh   ← 로컬과 같은 스크립트
      │  5. 로그인 → 주문 → Saga 완주(CONFIRMED)
      └────────────────────────────────────────┘
```

### 이미지 이름

```
ghcr.io/jyh4358/msa-practice/order-service:<커밋SHA>          ← 멀티아치(추적 가능)
ghcr.io/jyh4358/msa-practice/order-service:<커밋SHA>-amd64    ← 중간 산출물
ghcr.io/jyh4358/msa-practice/order-service:<커밋SHA>-arm64    ← 중간 산출물
ghcr.io/jyh4358/msa-practice/order-service:latest             ← 편의용
```

**핵심은 `:<커밋SHA>`** 다. 이 태그가 있으면 "떠 있는 이미지 → 소스 커밋"이 1:1로 이어진다.
`:latest` 만 쓰면 Phase 16 과 똑같은 문제가 남는다.

---

## 4. 코드 — 결정적인 부분

### ① jar 은 한 번만 — 아키텍처 무관성을 이용한다

```yaml
      # ★ jar 은 아키텍처 무관 — 여기서 만든 것을 두 아키텍처가 함께 쓴다.
      - name: bootJar 업로드
        uses: actions/upload-artifact@v4
        with:
          name: boot-jars
          path: |
            services/*/build/libs/*-0.0.1-SNAPSHOT.jar
```

Java 바이트코드는 CPU 아키텍처를 타지 않는다. 아키텍처마다 달라지는 건 **베이스 이미지(JRE)** 뿐이다.
그래서 컴파일·테스트를 두 번 할 이유가 없다 — 시간도 절반이고, **두 아키텍처가 정확히 같은 코드**임이 보장된다.

⚠️ 다운로드 쪽에는 함정이 있다. `upload-artifact` 는 매칭된 파일들의 **공통 조상**을 기준으로 경로를 접는다.
파일 개수가 바뀌면 그 조상도 바뀐다. 그래서 경로를 가정하지 않고 찾아서 옮긴다:

```bash
for svc in $SERVICES; do
  src=$(find services-jars -name "$svc-0.0.1-SNAPSHOT.jar" | head -1)
  [ -n "$src" ] || { echo "jar 을 찾지 못했다: $svc"; exit 1; }
  mkdir -p "services/$svc/build/libs"; cp "$src" "services/$svc/build/libs/"
done
```

### ② 네이티브 러너 두 개 — 에뮬레이션을 피한다

```yaml
    strategy:
      matrix:
        include:
          - arch: amd64
            runner: ubuntu-latest        # GitHub 기본 러너
          - arch: arm64
            runner: ubuntu-24.04-arm     # 공개 저장소 무료 arm64 러너
```

**왜 이게 필요한가.** 우리 노트북의 kind 는 arm64(Apple Silicon)이고 GitHub 기본 러너는 amd64 다.
러너에서 만든 amd64 이미지를 노트북에 가져오면 `exec format error` 로 죽는다.

흔한 해법은 `buildx` + QEMU 로 한 러너에서 두 아키텍처를 만드는 것인데, 비네이티브 쪽은 **에뮬레이션**이라 느리다.
공개 저장소는 arm64 러너를 무료로 쓸 수 있으므로 **각자 네이티브로 만들고 나중에 묶는** 편이 낫다.

### ③ 매니페스트 병합 — 이미지를 다시 만들지 않는다

```bash
docker buildx imagetools create \
  -t "${IMAGE_BASE}/${svc}:${GITHUB_SHA}" \
  -t "${IMAGE_BASE}/${svc}:latest" \
  "${IMAGE_BASE}/${svc}:${GITHUB_SHA}-amd64" \
  "${IMAGE_BASE}/${svc}:${GITHUB_SHA}-arm64"
```

이 명령은 **레이어를 하나도 옮기지 않는다.** 레지스트리 안에서
"이 태그는 amd64면 A, arm64면 B"라는 목록(manifest list)만 새로 쓴다. 그래서 몇 초면 끝난다.
`docker pull` 하는 쪽은 자기 아키텍처에 맞는 것을 알아서 받아 간다 — **클라이언트가 고민할 필요가 없다.**

### ④ CI 안의 kind — 로컬과 같은 스크립트를 쓴다

```yaml
      - name: kind 클러스터
        uses: helm/kind-action@v1
        with:
          cluster_name: shopsaga
          config: deploy/k8s/kind-cluster.yaml     # ★ 로컬과 같은 클러스터 정의
      …
      - name: 배포
        run: ./deploy/k8s/apply.sh                 # ★ 로컬과 같은 배포 스크립트
```

**CI 전용 배포 코드를 새로 쓰지 않는 것**이 중요하다. 새로 쓰면 그건 로컬과 다른 것을 검증하게 되고,
Phase 16b 에서 겪은 "테스트가 사본을 검증하던 문제"가 배포판으로 재현된다.

CI 에서만 다른 것은 **이미지 출처**뿐이다:

```bash
sed -i "s|image: shopsaga/${svc}:0.0.1|image: ${IMAGE_BASE}/${svc}:${GITHUB_SHA}|g" deploy/k8s/*.yaml
```

> ⚠️ `sed` 로 매니페스트를 고치는 건 정석이 아니다. Kustomize 의 `images:` 오버레이가 이 일을 위해 있다.
> 지금은 Kustomize 를 도입하지 않았으므로(Phase 18 과제) 가장 단순한 방법을 택했다 — 한계표 #1.

### ⑤ 권한은 최소로

```yaml
    permissions:
      contents: read
      packages: write        # GHCR push 에 필요. 기본 GITHUB_TOKEN 은 read 만 갖는다.
```

`GITHUB_TOKEN` 은 실행이 끝나면 폐기되는 임시 토큰이다. 기본값은 읽기 전용이고,
필요한 잡에만 `packages: write` 를 준다. 스모크 잡은 pull 만 하므로 `packages: read` 다.

클러스터가 이미지를 받아올 자격증명은 이렇게 넣는다:

```bash
kubectl create secret docker-registry ghcr -n shopsaga \
  --docker-server=ghcr.io --docker-username=${{ github.actor }} \
  --docker-password=${{ secrets.GITHUB_TOKEN }}
kubectl patch serviceaccount default -n shopsaga \
  -p '{"imagePullSecrets":[{"name":"ghcr"}]}'
```

기본 서비스어카운트에 붙이면 그 네임스페이스의 **모든 파드가 자동으로** 쓴다(매니페스트를 안 고쳐도 된다).

> ⚠️ **처음엔 "GHCR 패키지는 기본이 private" 이라고 알고 이 secret 을 넣었는데, 실제로는 아니었다.**
> 이미지 이름을 `ghcr.io/<owner>/<repo>/<service>` 로 지으면 GitHub 이 그 패키지를 **저장소에 연결**하고,
> **공개 저장소에서 Actions 로 push 한 패키지는 저장소의 공개 설정을 물려받는다.**
> 나중에 익명으로(토큰 없이) 확인해 보니 6개 전부 `HTTP 200` 이었다 — §7-④ 참고.
>
> 그래도 이 secret 을 남겨 둔 이유: ① 저장소를 비공개로 바꾸거나 조직 계정으로 옮겨도 그대로 동작하고
> ② "레지스트리 자격증명을 클러스터에 넣는 법"은 그 자체로 알아 둘 값어치가 있다.
> **다만 지금 이 구성에서는 없어도 된다** — 있는 것과 없는 것의 차이를 알고 두는 것과, 모르고 두는 것은 다르다.

---

## 5. 흐름 — 커밋 하나가 배포 검증까지 가는 길

```
git push
   │
   ├─ GitHub 이 워크플로 트리거 (on: push, branches: [main])
   │
   ├─ [빌드·테스트]  러너 A(amd64)
   │     checkout → JDK 21 → Gradle 캐시 복원
   │     ./gradlew build
   │        ├─ 컴파일 · 단위 테스트
   │        ├─ Testcontainers 통합 테스트  ← 러너의 Docker 데몬으로 Postgres 를 실제로 띄운다
   │        └─ 계약 테스트(Phase 15)
   │     bootJar 6개 → artifact 업로드
   │
   ├─ [이미지 amd64] 러너 B(amd64)  ┐  동시에
   ├─ [이미지 arm64] 러너 C(arm64)  ┘
   │     artifact 다운로드 → jar 원위치 → docker build → GHCR push (:<sha>-<arch>)
   │
   ├─ [매니페스트]  imagetools create → :<sha> · :latest  (멀티아치)
   │
   └─ [kind 스모크]  러너 D(amd64)
         kind 클러스터 생성(우리 kind-cluster.yaml)
         → 매니페스트 image 를 GHCR 로 교체 → pull secret
         → apply.sh (ConfigMap 생성 · RSA 키 생성 · ingress-nginx 설치 · 13파드 배포)
         → Ingress(:8000) 헬스 → 로그인 → POST /orders
         → 폴링으로 CONFIRMED 확인   ← Saga 가 클러스터 안에서 실제로 완주해야 초록불
```

---

## 6. 원리 — 왜 이렇게 동작하나

### ① 잡마다 머신이 다르다 — 그래서 artifact 가 필요하다

한 워크플로 안이라도 **잡은 각자 새 VM 을 받는다.** `빌드·테스트` 잡이 만든 jar 은
`이미지` 잡의 파일시스템에 존재하지 않는다. 이게 처음 CI 를 짤 때 가장 헷갈리는 지점이다.

같은 잡의 스텝끼리는 파일이 공유된다. 그래서 "잡을 나눌 것인가"는
**병렬성·머신 종류 vs artifact 왕복 비용**의 트레이드오프다.

### ② 멀티아치 이미지는 "이미지"가 아니라 "목록"이다

`ghcr.io/…/order-service:abc123` 를 pull 하면 실제로는 두 단계가 일어난다.

```
1. 매니페스트 리스트를 받는다:  { amd64 → sha256:AAA, arm64 → sha256:BBB }
2. 자기 아키텍처의 것만 받는다
```

그래서 `imagetools create` 가 빠르다 — 1번만 새로 쓰기 때문이다.
그리고 배포하는 쪽(k8s 매니페스트)은 **아키텍처를 신경 쓰지 않아도 된다.**
같은 태그가 CI 러너(amd64)에서도, 노트북 kind(arm64)에서도 그대로 동작한다.

### ③ Testcontainers 가 CI 에서 그냥 도는 이유

우리 통합 테스트는 실제 Postgres 컨테이너를 띄운다. 로컬에서는 Colima 가 그 역할을 했다.
GitHub 러너에는 **Docker 데몬이 미리 설치돼 있어** `DOCKER_HOST` 를 따로 주지 않아도 붙는다.

⚠️ 다만 이게 **러너 종류에 의존하는 부분**이다. 자체 호스팅 러너나 컨테이너 안에서 도는 잡이라면
Docker socket 을 따로 마운트해야 한다("Docker-in-Docker" 문제). 지금은 기본 러너를 쓰므로 문제가 없다.

### ④ `GITHUB_TOKEN` 과 포크 PR

`GITHUB_TOKEN` 은 실행마다 발급되고 끝나면 폐기된다. 그런데 **포크에서 온 PR** 에는
쓰기 권한이 있는 토큰을 주지 않는다 — 안 그러면 아무나 PR 을 열어 우리 레지스트리에 쓸 수 있다.

그래서 이미지·배포 잡은 PR 에서 건너뛴다:

```yaml
    if: github.event_name != 'pull_request'
```

PR 은 **빌드·테스트만** 돈다. 이것만으로도 "깨진 코드가 머지되는 것"은 막힌다.

### ⑤ 왜 `.github/workflows/` 푸시에 별도 권한이 필요한가

이 Phase 를 진행하며 실제로 막혔다:

```
! [remote rejected] refusing to allow a Personal Access Token to create or update
  workflow `.github/workflows/ci.yml` without `workflow` scope
```

워크플로 파일은 **CI 러너에서 임의 코드를 실행**시킬 수 있다. 토큰이 유출됐을 때
공격자가 워크플로를 심어 시크릿을 빼돌리는 것을 막기 위해, GitHub 은 이 경로만
`workflow` 스코프를 가진 토큰에게만 허용한다. **파일 하나가 곧 실행 권한**이라는 관점이다.

---

## 7. 검증 — 실제로 측정한 것

> 대상: 커밋 `e6cdaa8` · [CI 실행 #1](https://github.com/jyh4358/msa-practice/actions/runs/30702698385)
> **첫 실행에서 5개 잡 전부 통과했다**(재시도 없음).

### ① 잡별 결과와 소요 시간

| 잡 | 러너 | 결과 | 소요 |
|---|---|---|---|
| 빌드·테스트 | `ubuntu-latest` (amd64) | ✅ | **5m41s** |
| 이미지 (amd64) | `ubuntu-latest` | ✅ | 2m40s |
| 이미지 (arm64) | **`ubuntu-24.04-arm`** | ✅ | 3m41s |
| 멀티아치 매니페스트 | `ubuntu-latest` | ✅ | **0m30s** |
| kind 스모크 배포 | `ubuntu-latest` | ✅ | 3m32s |
| **전체(대기 포함)** | | ✅ | **13m43s** |

### ② arm64 러너가 실제로 배정됐는가

```
빌드·테스트          ubuntu-latest
이미지 (amd64)       ubuntu-latest
이미지 (arm64)       ubuntu-24.04-arm      ← ★ 네이티브 arm64
멀티아치 매니페스트    ubuntu-latest
kind 스모크 배포      ubuntu-latest
```

공개 저장소라 arm64 러너가 무료로 배정됐다. **QEMU 에뮬레이션 없이** arm64 이미지를 만들었다는 뜻이다.
두 이미지 잡의 소요가 비슷한 것(2m40s vs 3m41s)이 그 증거다 —
에뮬레이션이었다면 arm64 쪽이 몇 배로 늘어났을 것이다.

### ③ 매니페스트 병합이 "빠른" 이유의 실측

**0m30s.** 6개 서비스의 멀티아치 태그를 전부 만드는 데 30초다.
레이어를 하나도 옮기지 않고 레지스트리 안에서 목록만 새로 쓰기 때문이다(§6-②).
비교하면 같은 6개 이미지를 실제로 빌드하는 데는 2~4분이 걸렸다.

### ④ GHCR 에 멀티아치 매니페스트가 실제로 만들어졌는가

매니페스트 잡이 `imagetools inspect` 로 남긴 로그(6개 서비스 모두 동일한 형태):

```
Name:      ghcr.io/jyh4358/msa-practice/order-service:9a60530f0fdb84c4dae5fe45a97248d3b4bb45b8
  Name:      …@sha256:6c6f5cf8dff3c8f75b616ac130fb52f55a59ef2ae51feed90fdc0dae74d15598
  Platform:  linux/amd64
  Name:      …@sha256:922c84d9e373edd3615b7bcf1bacd8b4a2475000b2271c21b6f0da8ad68749f3
  Platform:  linux/arm64
```

**태그 하나가 두 다이제스트를 가리킨다**(§6-② 의 "매니페스트 리스트").

CI 로그는 "만들 때" 찍힌 것이므로, **나중에 밖에서 레지스트리에 직접 물어** 다시 확인했다
(`docker pull` 이 실제로 타는 경로 `GET /v2/<name>/manifests/<tag>`):

```
order-service          latest / 9a60530   HTTP 200  [amd64, arm64]
payment-service        latest / 9a60530   HTTP 200  [amd64, arm64]
inventory-service      latest / 9a60530   HTTP 200  [amd64, arm64]
order-query-service    latest / 9a60530   HTTP 200  [amd64, arm64]
gateway-service        latest / 9a60530   HTTP 200  [amd64, arm64]
auth-service           latest / 9a60530   HTTP 200  [amd64, arm64]
```

**6개 서비스 × 2개 태그 = 12/12 전부 멀티아치.** 노트북(arm64)에서도 러너(amd64)에서도 같은 태그로 받아진다.

그리고 **토큰 없이(익명)** 도 받아진다 — GHCR 의 익명 토큰 흐름
(`GET /token?scope=repository:<name>:pull` → 그 토큰으로 매니페스트 조회)으로 확인:

```
order-service · payment-service · inventory-service ·
order-query-service · gateway-service · auth-service     익명 HTTP 200  [amd64, arm64]
```

즉 **패키지가 공개 상태**다. 이미지 이름에 저장소명이 들어가 있어(`…/msa-practice/…`)
GitHub 이 패키지를 저장소에 연결했고, **공개 저장소의 Actions 가 push 한 패키지는 그 공개 설정을 물려받는다.**

> ⚠️ 그래서 §4-⑤ 에서 넣은 `imagePullSecret` 은 **이 구성에서는 실제로 필요 없다.**
> 처음에 "GHCR 는 기본이 private" 이라고 잘못 알고 넣은 것이다.
> 이미지가 공개된다는 건 **이미지 안에 비밀값이 있으면 전 세계에 공개된다**는 뜻이기도 하다 —
> 우리 이미지에는 jar 만 들어 있고 설정·비밀번호는 밖에서 주입하므로(Phase 16의 원칙) 문제가 없다.

> ⚠️ 곁다리로 알게 된 것: **Packages REST API(`/user/packages`)는 fine-grained PAT 를 지원하지 않는다.**
> 권한을 추가해도 403 이다 — classic PAT + `read:packages` 가 필요하다.
> 반면 **레지스트리 자체(`ghcr.io/v2/...`)는 fine-grained PAT 로 잘 된다.**
> 즉 "이미지를 받는 것"과 "패키지를 관리하는 것"은 인증 경로가 다르다.

### ⑤ CI 안에서 전체 플랫폼이 실제로 떴는가

스모크 잡 **3m32s** 안에서 다음이 전부 일어났다. 파드 13개가 **2m16s 만에 전부 `1/1 Running`** 이 됐다:

```
NAME                                   READY   STATUS    RESTARTS   AGE
auth-service-7786b89d48-mnkgc          1/1     Running   0          2m16s      ← 복제본 2
auth-service-7786b89d48-v9nks          1/1     Running   0          2m16s
gateway-service-58c44b7d66-ct957       1/1     Running   0          2m15s
order-service-c49985bd4-fdstg          1/1     Running   0          2m16s
payment-service-5b5fb4cb58-v89sq       1/1     Running   0          2m16s
inventory-service-5b9cff56d4-x58s9     1/1     Running   0          2m16s
order-query-service-5645c5cddd-rprvc   1/1     Running   0          2m16s
order-db / payment-db / inventory-db / order-query-mongo / kafka / otel-lgtm   전부 1/1 Running
```

**RESTARTS 가 전부 0** 이다 — Phase 16b 에서 넣은 initContainer(Kafka 대기) 덕분에
"앱이 브로커보다 먼저 떠서 CrashLoopBackOff" 가 재현되지 않았다.

이어서 스모크가 돌았다:

```
① Ingress 헬스
② 로그인
③ 주문 접수
   order=bb53b191-fa81-4d8b-aeea-76b0ead0fbfc
④ Saga 완주 대기 (결과적 일관성 — 폴링으로 확인)
   [3s] PENDING
   [6s] CONFIRMED        ← ★ 클러스터 안에서 Saga 가 실제로 완주
```


```
kind 클러스터 생성(우리 kind-cluster.yaml)
→ 매니페스트 image 를 GHCR 참조로 교체
→ GHCR pull secret 생성 + 기본 ServiceAccount 에 부착
→ ./deploy/k8s/apply.sh          (ConfigMap 생성 · RSA 키 생성 · ingress-nginx 설치 · 13파드)
→ 인프라 5개 available 대기 → 앱 6개 available 대기
→ Ingress(:8000) 헬스 200
→ 로그인 → POST /orders → 폴링
→ CONFIRMED                      ← Saga 가 클러스터 안에서 실제로 완주
```

**로컬에서 손으로 하던 것과 같은 스크립트(`apply.sh`)로 같은 결과가 나왔다.**
이것이 이 Phase 의 실질적 성과다 — 이제 매니페스트가 깨지면 push 시점에 빨간불이 된다.

> 참고로 로컬 첫 배포(Phase 16b)에서는 Kafka 부트스트랩 교착과 KafkaAdmin 토픽 미생성으로
> 두 번 막혔다. 그 수정이 매니페스트에 반영돼 있었기에 CI 는 한 번에 통과했다 —
> 바꿔 말하면, **그 수정이 되돌려지면 이제는 CI 가 잡아낸다.**

### ⑥ Testcontainers 가 러너에서 그대로 돌았는가

`빌드·테스트` 잡 5m41s 중 Gradle 자체는 **4m41s**(`BUILD SUCCESSFUL in 4m 41s`, 65 tasks)였고 테스트 87개가 전부 통과했다. `DOCKER_HOST` 를 따로 주지 않았는데도
Testcontainers 통합 테스트(Postgres 실기동)가 동작했다 — 러너에 Docker 데몬이 미리 있기 때문이다(§6-③).

첫 실행이라 **Gradle 캐시가 비어 있었다.** 다음 실행부터는 의존성 다운로드가 빠지므로 더 짧아진다.

### ⑦ ★ 2차 실행이 깨졌다 — CI 가 **빨라지자** 드러난 경쟁 상태

문서만 고친 커밋을 푸시했더니 **스모크 잡이 실패**했다. 1차는 통과했는데 2차는 깨졌다.

| 잡 | 1차 | 2차 | 변화 |
|---|---|---|---|
| 빌드·테스트 | 5m41s | **1m12s** | −79% (Gradle 캐시) |
| 이미지 (amd64) | 2m40s | 1m10s | −56% |
| 이미지 (arm64) | 3m41s | 1m33s | −58% |
| 매니페스트 | 0m30s | 0m16s | −47% |
| kind 스모크 | 3m32s | **1m12s ❌** | 실패 |
| **전체** | 13m43s | **4m26s** | **−68%** |

로그:

```
14:55:08.37  pod/ingress-nginx-controller-… condition met        ← 파드는 Ready
14:55:09.29  Error from server (InternalError): … failed calling webhook
             "validate.nginx.ingress.kubernetes.io": … dial tcp 10.96.36.82:443:
             connect: connection refused
```

**1초 차이다.** ingress-nginx 는 Ingress 를 만들 때 검증용 admission webhook(:443)을 호출하는데,
컨트롤러 파드가 Ready 가 돼도 그 **Service 의 EndpointSlice 에 주소가 실리기까지 한 박자 더** 걸린다.

**왜 1차는 통과했나.** 1차는 이미지 pull·의존성 다운로드가 느려서, 컨트롤러가 Ready 가 된 뒤
Ingress 를 적용하기까지 시간이 충분히 벌어졌다. **2차는 캐시로 빨라지면서 그 창에 정확히 들어갔다.**

> 이게 이 Phase 에서 가장 배울 만한 사건이다.
> - **성능 개선이 잠재 결함을 드러냈다.** 느려서 우연히 가려져 있던 경쟁이 빨라지자 터졌다.
> - **로컬에서는 절대 안 걸린다.** 사람이 명령을 하나씩 치는 동안 시간이 흐르기 때문이다.
> - Phase 16 §6-③ 에서 배운 성질("파드 Ready" ≠ "Service 로 트래픽이 간다")이
>   이번엔 **우리 배포 스크립트를 물었다.** Kafka 부트스트랩 교착과 같은 뿌리다.

**수정**(`deploy/k8s/apply.sh`): 두 겹으로 막았다.

```bash
# ① webhook Service 의 EndpointSlice 에 주소가 실릴 때까지 기다린다
for _ in $(seq 1 60); do
  addrs=$(kubectl get endpointslices -n ingress-nginx \
    -l kubernetes.io/service-name=ingress-nginx-controller-admission \
    -o jsonpath='{.items[*].endpoints[*].addresses[0]}' 2>/dev/null || true)
  [ -n "$addrs" ] && break
  sleep 2
done

# ② 그래도 완전히 결정적이지 않으므로 짧게 재시도한다 — 단 다 쓰면 반드시 실패시킨다
ingress_ok=false
for attempt in 1 2 3 4 5; do
  if kubectl apply -f "$K8S/50-ingress.yaml"; then ingress_ok=true; break; fi
  sleep 5
done
[ "$ingress_ok" = true ] || { kubectl get pods,endpointslices -n ingress-nginx; exit 1; }
```

②의 마지막 줄이 중요하다. 재시도만 넣고 실패 처리를 빠뜨리면 **Ingress 없는 클러스터가
'배포 성공'으로 보고되고**, 그 뒤 스모크가 엉뚱한 이유(연결 거부)로 깨져 원인 추적이 어려워진다.

### ⑧ 겪은 문제 — 워크플로 파일은 그냥 푸시되지 않는다

파이프라인 자체는 한 번에 통과했지만, **푸시가 먼저 막혔다.**

```
! [remote rejected] main -> main (refusing to allow a Personal Access Token to create or
  update workflow `.github/workflows/ci.yml` without `workflow` scope)
```

`.github/workflows/` 아래 파일은 CI 러너에서 임의 코드를 실행시킬 수 있으므로,
GitHub 은 이 경로만 **`workflow` 스코프를 가진 토큰**에게만 허용한다(§6-⑤).

우회로 **워크플로 파일이 없는 앞의 3커밋(P15·P16a·P16b)만 먼저 푸시**했고,
토큰에 스코프를 추가한 뒤 Phase 17 커밋을 올렸다.

> 부수적으로 하나 더 배웠다: GitHub API 를 **인증 없이** 폴링하면 시간당 60회 제한에 걸린다.
> CI 진행 상황을 자주 확인하다 한도를 소진했다. `gh auth login` 으로 인증하면 5,000회/시간이 된다.

---

## 8. 이번 단계의 한계 → 어디서 해결되나

| # | 한계 | 왜 문제인가 | 해결 |
|---|---|---|---|
| 1 | **이미지 교체를 `sed` 로 한다** | 매니페스트를 문자열로 고친다. 환경(dev/stage/prod)별 오버레이가 없다 | Phase 18 (Kustomize/Helm) |
| 2 | **CD 가 없다 — 배포는 여전히 수동** | CI 는 "배포 가능함"만 증명한다. 실제 클러스터에 반영하는 건 사람이다 | Phase 18 (Argo CD/Flux 같은 GitOps) |
| 3 | **중간 태그(`:<sha>-amd64`)가 레지스트리에 쌓인다** | 정석은 `push-by-digest`. 학습용으로 태그가 보이는 쪽을 택했다 | 정리 정책 또는 by-digest 전환 |
| 4 | **이미지 취약점 스캔이 없다** | base 이미지의 CVE 를 아무도 안 본다 | Trivy/Grype 추가(간단) |
| 5 | **서명·SBOM 이 없다** | 이 이미지가 이 파이프라인에서 나왔음을 증명할 수 없다 | cosign/SLSA provenance |
| 6 | **스모크가 "행복 경로" 하나** | 로그인→주문→CONFIRMED 만 본다. 보상·타임아웃 경로는 CI 가 검증하지 않는다 | 시나리오 추가 |
| 7 | **버전이 `0.0.1-SNAPSHOT` 고정** | 릴리스 개념이 없다. 태그·체인지로그·시맨틱 버저닝 부재 | Phase 18 |
| 8 | **캐시가 Gradle 뿐** | 도커 레이어 캐시가 없어 이미지 빌드가 매번 처음부터 | `cache-from/to` (buildx 필요) |
| 9 | **비용·시간** | 스모크가 13파드를 띄운다. 커밋마다 도는 건 부담 | 경로 필터·야간 배치로 분리 |

---

## 9. 용어

- **잡(job) / 스텝(step)** — 잡은 머신 단위, 스텝은 명령 단위. **잡이 다르면 파일이 공유되지 않는다.**
- **artifact** — 잡 사이에 파일을 옮기는 수단. 보존 기간을 지정할 수 있다.
- **매트릭스(matrix)** — 같은 잡을 값만 바꿔 여러 번 돌리는 문법. 우리는 아키텍처로 나눴다.
- **매니페스트 리스트 / OCI 인덱스** — 아키텍처별 이미지를 묶는 목록. 멀티아치 태그의 실체.
- **GHCR** — GitHub Container Registry. 저장소 권한과 연결되고 `GITHUB_TOKEN` 으로 접근한다.
- **`concurrency`** — 같은 그룹의 이전 실행을 취소하는 설정. 연속 push 때 러너 낭비를 막는다.
- **`workflow` 스코프** — 워크플로 파일을 푸시하려면 토큰에 필요한 별도 권한(§6-⑤).
- **QEMU 에뮬레이션** — 다른 아키텍처의 바이너리를 흉내 내 실행하는 것. 정확하지만 느리다.

---

## 10. 참고

- 코드: [`.github/workflows/ci.yml`](../.github/workflows/ci.yml)
- 실행 결과: [Actions 탭](https://github.com/jyh4358/msa-practice/actions/workflows/ci.yml)
- 이전 단계: [Phase 16 · 로컬 k8s](PHASE-16-KUBERNETES.md)(이 파이프라인이 배포하는 매니페스트) ·
  [Phase 7 · Docker Compose](PHASE-7-COMPOSE.md)(Dockerfile 의 출처)
- 공식 문서:
  - [GitHub Actions — Workflow syntax](https://docs.github.com/actions/reference/workflow-syntax-for-github-actions)
  - [GitHub Actions — Publishing images to GHCR](https://docs.github.com/actions/publishing-packages/publishing-docker-images)
  - [Docker — Multi-platform images](https://docs.docker.com/build/building/multi-platform/)
  - [helm/kind-action](https://github.com/helm/kind-action)
  - [Testcontainers — CI 환경](https://java.testcontainers.org/supported_docker_environment/continuous_integration/)
