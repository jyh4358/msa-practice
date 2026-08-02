# Phase 18 — 선언적 배포 (Kustomize base/overlay · Helm 릴리스 · 설정 변경의 자동 전파)

> **한 줄 요약:** Phase 16·17 의 한계표에서 **양쪽 모두 1번**으로 적어 둔 것이 이거였다 —
> "배포가 아직 셸 스크립트다."
> `apply.sh` 안에는 ConfigMap 을 만드는 `for` 루프가, CI 안에는 이미지 이름을 바꾸는 `sed` 가 있었다.
> 이 단계에서 그것들을 **선언**으로 바꾼다. 그리고 그 부산물로,
> **설정 파일을 고치면 파드가 저절로 갈리는** 성질을 공짜로 얻는다.

초심자(쿠버네티스 매니페스트는 Phase 16 에서 처음 봤고 Kustomize/Helm 은 처음) 기준으로
**왜 → 무엇을 → 어떻게** 순서로 설명합니다.

---

## 0. 이번 단계에서 한 일 (요약)

- 매니페스트 15개를 `deploy/k8s/base/` 로 옮기고, 환경 차이는 `deploy/k8s/overlays/{local,ci}` 가 담게 했다.
  배포는 이제 **`kubectl apply -k` 한 줄**이다.
- 파일마다 박혀 있던 `namespace: shopsaga` **34곳**을 지우고 `base/kustomization.yaml` 의 **한 줄**로 모았다.
- `apply.sh` 의 `kubectl create configmap --from-file` **7회 루프**를
  `deploy/config/kustomization.yaml` 의 `configMapGenerator` 로 바꿨다.
  ⚠️ 이때 Kustomize 의 **로드 제한**에 부딪혔고(§4-①), `deploy/config/` 자체를 kustomization 루트로
  만드는 방식으로 풀었다 — 덕분에 **compose 와 설정 파일을 공유하는 구조가 그대로 유지된다**.
- **`--config` 플래그가 사라졌다.** 생성기가 내용 해시를 이름에 붙이므로 설정 변경이 곧 배포 변경이 되고,
  롤링 업데이트가 저절로 일어난다 — 실측 **44초, 무중단, `rollout restart` 명령 0회**(§7-③).
- CI 의 `sed` 를 **`kustomize edit set image`** 로 바꿨다. sed 는 패턴이 안 맞아도 조용히 성공하지만,
  Kustomize 는 YAML 을 파싱해서 **컨테이너의 image 필드만** 정확히 바꾼다(§7-⑤에 그 증거).
- **ingress-nginx 는 Helm 릴리스로** 깔았다(원격 raw 매니페스트 URL → 차트 4.13.9 + values 파일).
  **내 것은 Kustomize, 남의 것은 Helm** — 실무에서 가장 흔한 조합이고, 그 이유가 §1-⑤에 있다.
- 노드 라벨 `ingress-ready=true` 를 `kind-cluster.yaml` 로 옮겼다(클러스터를 만들 때 정해지는 성질이므로).
- auth-service 의 RSA 개인키는 여전히 git 밖에 있고(`base/.secrets/`, `.gitignore`),
  `secretGenerator` 가 그 파일을 읽는다. **없으면 렌더가 실패한다** — 의도한 동작이다.

**바뀌지 않은 것**(중요): 애플리케이션 코드 한 줄도 건드리지 않았다. 이미지도 그대로다.
테스트 **87개 전부 통과**(§7-⑦). compose 도 그대로 뜬다(§7-⑥).

---

## 1. 왜 — 셸 스크립트로 배포하면 무엇이 문제인가

Phase 16b 의 `apply.sh` 는 잘 동작했다. 그런데 다음 다섯 가지가 걸린다.

**① 무엇이 클러스터에 있는지 파일만 봐서는 모른다.**
`kubectl create configmap … | kubectl apply -f -` 는 **실행해 봐야** 결과를 안다.
매니페스트는 "이렇게 되어 있어야 한다"는 **명세**인데, 셸 스크립트는 "이렇게 해라"는 **절차**다.
명세는 읽고 비교하고 되돌릴 수 있지만, 절차는 실행 이력을 따라가야 한다.

**② 지우는 방법이 없다.** 만든 것 목록을 아무도 안 갖고 있으니
`kubectl delete namespace` 로 통째로 날리는 것 말고는 방법이 없다.

**③ 설정을 고쳐도 파드가 모른다.** ★ 이게 제일 아팠다.
ConfigMap 을 갱신해도 이미 떠 있는 파드는 **기동할 때 읽은 설정을 그대로 들고 있다.**
그래서 16b 의 `apply.sh` 에는 이런 게 붙어 있었다:

```bash
if [ "$ONLY_CONFIG" = "--config" ]; then
  for d in $(kubectl get deploy -n "$NS" -o name | grep -E "service"); do
    kubectl rollout restart "$d" -n "$NS"      # ← 사람이 기억해서 붙여야 하는 절차
  done
fi
```

`--config` 를 안 붙이고 돌리면 **ConfigMap 만 바뀌고 아무 일도 안 일어난다.**
조용히 틀리는 종류의 실수다.

> 참고: 마운트된 ConfigMap **파일 자체**는 kubelet 이 최대 ~70초 뒤 갱신해 준다.
> 하지만 Spring Boot 는 그 파일을 **기동 시 한 번** 읽고 끝이라 파일이 바뀌어도 반영되지 않는다.
> 그래서 결국 "재시작"이 유일한 답이었다.

**④ CI 의 `sed` 는 틀려도 조용하다.**

```bash
sed -i "s|image: shopsaga/${svc}:0.0.1|image: ${IMAGE_BASE}/${svc}:${GITHUB_SHA}|g" deploy/k8s/*.yaml
```

문제는 '더럽다'가 아니다. **0건 치환해도 `exit 0`** 이라는 것이다.
매니페스트에서 들여쓰기를 한 칸 바꾸거나 태그를 `0.0.2` 로 올리면 패턴이 안 맞는데,
CI 는 초록불인 채로 **옛 이미지를 배포**한다. 그리고 스모크가 통과해 버리면 아무도 모른다.

**⑤ 남의 소프트웨어를 URL 로 설치하면 관리할 수가 없다.**

```bash
kubectl apply -f https://raw.githubusercontent.com/…/controller-v1.13.1/…/kind/deploy.yaml
```

버전이 **URL 문자열 안에 숨는다**. 무엇이 깔렸는지 알려면 클러스터에 물어봐야 하고,
값을 바꾸려면 남의 YAML 을 손으로 고쳐야 하고(다음 업그레이드 때 그 수정은 날아간다),
지우려면 무엇이 깔렸는지 **다시 알아내야** 한다.

---

## 2. 개념 — 최소 용어

| 용어 | 뜻 |
|---|---|
| **Kustomize** | 완성된 YAML 을 **덧칠**해 환경별 변형을 만드는 도구. `kubectl` 에 내장(`-k`). |
| **base** | 어느 환경에서나 참인 매니페스트 묶음. **그 자체로 배포 가능**해야 한다. |
| **overlay** | base 를 가져다 그 환경에서만 참인 것을 얹은 것. `resources: [../../base]` |
| **transformer(변환기)** | 모든 리소스에 일괄 적용되는 규칙. `namespace:`, `labels:`, `images:` 등. |
| **generator(생성기)** | 파일·리터럴로부터 ConfigMap/Secret 을 **만들어 내는** 규칙. **이름에 내용 해시가 붙는다.** |
| **name reference transformer** | 생성기가 이름을 바꾸면 **그 이름을 참조하는 곳까지** 같이 고쳐 주는 내장 변환기. |
| **strategic merge patch** | 원본과 같은 모양의 YAML 조각을 주면 필드 단위로 병합되는 패치 방식. |
| **load restrictor** | kustomization 디렉터리 **바깥 파일** 읽기를 막는 안전장치. 기본 `LoadRestrictionsRootOnly`. |
| **Helm** | 템플릿(`{{ .Values.x }}`)을 값으로 채워 배포하고, **설치한 것의 목록을 릴리스로 기억**하는 패키지 매니저. |
| **release(릴리스)** | Helm 이 기억하는 "이 이름으로 이 차트를 이 값으로 깔았다"는 기록. `helm list` 로 조회. |

### Kustomize 와 Helm 은 경쟁 관계가 아니다

둘의 접근이 근본적으로 다르다.

```
Helm      : 빈칸 뚫린 템플릿 → 값으로 채운다
            templates/deployment.yaml 은 그 자체로 유효한 YAML 이 아니다({{ }} 때문에).
            강점: 파라미터가 많고, 남에게 배포할 때. 릴리스 이력·롤백.

Kustomize : 완성된 YAML → 위에 덧칠한다
            base/*.yaml 은 kubectl apply -f 로도 그냥 배포된다.
            강점: 내 것을 내가 관리할 때. diff 가 읽히고, 원본이 항상 유효.
```

그래서 실무의 표준 답은 **"내 앱은 Kustomize, 서드파티는 Helm"** 이다.
남이 만든 것(ingress-nginx, Argo CD, cert-manager…)은 대부분 **Helm 차트로 배포되므로**
받는 쪽도 Helm 을 쓰는 게 자연스럽다. 이번 Phase 가 정확히 그 구성이다.

---

## 3. 구성 — 디렉터리 모양

```
deploy/
├── config/                          ★ 설정의 단일 소스 (compose 와 공유)
│   ├── kustomization.yaml           ← configMapGenerator 7개. 왜 여기 있는지는 §4-①
│   ├── common.yml                   ← 서비스 주소·Kafka·probe·관측성
│   └── {order,payment,…}-service.yml
│
└── k8s/
    ├── kind-cluster.yaml            ← 포트 매핑 + ingress-ready 노드 라벨
    ├── ingress-nginx-values.yaml    ← Helm 차트 값 (kind 용)
    ├── build-and-load.sh            ← bootJar → 이미지 → kind load
    ├── apply.sh                     ← 키 부트스트랩 + helm + apply -k  (59줄, 16b 는 76줄)
    │
    ├── base/                        ★ 어느 환경에서나 참인 것
    │   ├── kustomization.yaml       ← namespace 변환기 · 공통 라벨 · resources · secretGenerator
    │   ├── .secrets/                ← .gitignore. RSA 개인키(apply.sh 가 생성)
    │   ├── namespace.yaml
    │   ├── db-secrets.yaml
    │   ├── {order,payment,inventory}-db.yaml · order-query-mongo.yaml
    │   ├── kafka.yaml · otel-lgtm.yaml
    │   ├── {auth,order,payment,inventory,order-query,gateway}-service.yaml
    │   └── ingress.yaml
    │
    └── overlays/
        ├── local/kustomization.yaml ← order-service 를 NodePort 30080 으로도 노출(디버깅)
        └── ci/kustomization.yaml    ← 이미지를 GHCR 참조로 교체
```

### 무엇이 base 이고 무엇이 overlay 인가

이 경계를 어디에 그을지가 Kustomize 를 쓸 때 실제로 고민하는 유일한 문제다. 기준은 하나다 —
**"다른 환경에서도 이게 참인가?"**

| 항목 | 어디에 | 왜 |
|---|---|---|
| Deployment·Service·probe·리소스 요청 | base | 어디서 돌리든 같다 |
| Ingress | base | 경로 규칙은 환경과 무관 |
| ConfigMap 내용 | base(=`../../config`) | compose 와도 공유하는 **하나의** 설정 |
| **NodePort 30080** | overlays/local | 게이트웨이 우회 디버깅용. CI 스모크는 Ingress 만 쓴다 |
| **이미지 출처** | overlays/ci | 로컬은 `kind load` 한 것, CI 는 GHCR 의 커밋 SHA |

16b 에서는 NodePort 가 base 매니페스트에 있었다. 그때는 "내 노트북 = 유일한 환경"이었으니 맞았다.
CI 라는 두 번째 환경이 생긴 지금은 **거짓말**이 된 것이고, 그래서 overlay 로 내려보냈다.

---

## 4. 코드 — 결정적인 부분

### ① 로드 제한: 이 Phase 에서 제일 먼저 부딪힌 벽

가장 자연스러운 작성은 이것인데, **동작하지 않는다**:

```yaml
# deploy/k8s/base/kustomization.yaml
configMapGenerator:
  - name: shopsaga-common
    files: [application.yml=../../config/common.yml]     # ← 상위 디렉터리
```

```
error: loading KV pairs: file sources: [application.yml=../../config/common.yml]:
  security; file '/…/deploy/config/common.yml'
  is not in or below '/…/deploy/k8s/base'
```

Kustomize 는 기본적으로 kustomization 디렉터리 **바깥의 파일**을 못 읽는다
(`LoadRestrictionsRootOnly`). 공급망 공격 방지가 목적이다 — 남의 kustomization 을 받아 빌드했을 때
내 `~/.ssh/id_rsa` 를 ConfigMap 으로 빨아가지 못하게 한다.

우회법이 셋인데 각각 대가가 있다:

| 방법 | 대가 |
|---|---|
| `kustomize build --load-restrictor LoadRestrictionsNone` | 별도 바이너리 필요(kubectl 은 이 플래그를 노출 안 함) + 안전장치를 통째로 끔 |
| `deploy/config/` 를 `base/` 안으로 이동 | compose 가 "k8s 디렉터리"에서 설정을 마운트하게 됨 — 소유 관계가 뒤집힘 |
| **`deploy/config/` 를 스스로 kustomization 루트로** | 없음 ← **이걸 골랐다** |

```yaml
# deploy/config/kustomization.yaml — 파일들이 자기 루트 안에 있으므로 제한에 안 걸린다
configMapGenerator:
  - name: shopsaga-common
    files: [application.yml=common.yml]
  - name: order-service-config
    files: [application.yml=order-service.yml]
  # … 6개 서비스
```

```yaml
# deploy/k8s/base/kustomization.yaml — 디렉터리 참조는 '새 루트'라 허용된다
resources:
  - ../../config
```

덤이 있다. **ConfigMap 을 만드는 규칙이 원본 파일 바로 옆에 있게** 되어
"이 디렉터리가 설정의 주인"이라는 사실이 구조로 드러난다.

### ② 생성기의 해시 접미사 — 이 Phase 의 핵심

```yaml
# 렌더 결과
apiVersion: v1
kind: ConfigMap
metadata:
  name: shopsaga-common-49f7g8bmk6      # ← 내용 해시가 붙는다
---
kind: Deployment
      volumes:
        - name: common-config
          configMap:
            name: shopsaga-common-49f7g8bmk6   # ← 참조도 같이 고쳐진다
```

두 번째가 핵심이다. Deployment 에는 `configMap: {name: shopsaga-common}` 이라고만 적혀 있는데,
Kustomize 의 **이름 참조 변환기**가 생성기의 결과를 알고 참조까지 바꿔 준다.

> 이게 `base` 와 `../../config` 처럼 **다른 kustomization 에 나뉘어 있어도 동작하는지**는
> 확실하지 않아서 먼저 실험했다 — 동작한다(§7-①).

그 결과 이렇게 된다:

```
common.yml 한 글자 수정
  → 내용 해시가 바뀐다
  → ConfigMap 이름이 바뀐다
  → 그걸 참조하는 Deployment 의 '파드 템플릿'이 바뀐다   ← 여기가 핵심
  → 쿠버네티스가 "배포가 바뀌었다"고 판단한다
  → 롤링 업데이트가 저절로 일어난다
```

**"설정 변경"이 "배포 변경"으로 타입 승격된다.** `--config` 플래그가 필요 없어진 이유다.

### ③ DB 자격증명은 일부러 생성기로 만들지 않았다

일관성만 보면 DB 비밀번호도 `secretGenerator` 로 옮기는 게 맞아 보인다. 하지만 안 했다.

```yaml
# base/db-secrets.yaml — 평범한 리소스로 남겨 뒀다(해시 접미사 없음)
kind: Secret
metadata: { name: order-db-credentials }
stringData:
  username: order
  password: orderpw
```

이유: **PostgreSQL 은 비밀번호를 PVC 안 데이터 디렉터리에 초기화 시 한 번 굽는다.**
Secret 을 생성기로 바꾸면 비밀번호를 고치는 순간 이름이 바뀌고 → DB 파드가 재시작되는데,
**정작 DB 안의 비밀번호는 그대로**라 앱이 인증 실패로 죽는다.
"자동 롤아웃"이 여기서는 **도움이 아니라 함정**이다.

반대로 auth-service 의 RSA 서명 키는 생성기가 맞다 — 키 교체는 원래 재시작이 필요한 일이다.

> 정리하면: **재시작이 곧 반영인 값은 생성기로, 재시작으로 반영되지 않는 값은 일반 리소스로.**

### ④ overlays/ci — sed 가 하던 일

```yaml
images:
  - name: shopsaga/order-service                              # base 의 이미지 이름과 정확히 일치
    newName: ghcr.io/jyh4358/msa-practice/order-service
    newTag: latest                                            # CI 가 커밋 SHA 로 덮어쓴다
```

```yaml
# .github/workflows/ci.yml
- name: 이미지 태그를 이번 커밋으로 고정
  working-directory: deploy/k8s/overlays/ci
  run: |
    for svc in $SERVICES; do
      kustomize edit set image "shopsaga/${svc}=${IMAGE_BASE}/${svc}:${GITHUB_SHA}"
    done
    kustomize build . | grep -E '^\s+image:' | sort -u    # ← 눈으로 검증
```

`newTag: latest` 는 자리 표시가 아니라 **안전한 기본값**이다. 사람이 손으로 `apply -k overlays/ci` 를
돌려도 동작한다.

> ⚠️ `kustomize edit` 는 **kubectl 내장 kustomize 에 없다**(kubectl 은 `build` 만 노출한다).
> 그래서 CI 는 kustomize 바이너리를 따로 설치한다 — 버전은 kubectl v1.36 내장본과 같은 **5.8.1** 로 맞췄다.
> 로컬과 CI 가 다른 렌더 결과를 내면 곤란하기 때문이다.

### ⑤ ingress-nginx: URL 에서 Helm 릴리스로

```bash
helm upgrade --install ingress-nginx ingress-nginx/ingress-nginx \
  --namespace ingress-nginx --create-namespace \
  --version 4.13.9 \
  --values deploy/k8s/ingress-nginx-values.yaml \
  --wait --timeout 5m
```

`upgrade --install` = 없으면 설치, 있으면 갱신(멱등). 그리고 이제 이런 게 가능하다:

```bash
helm list -n ingress-nginx          # 무엇이 어느 버전으로 깔렸나
helm uninstall ingress-nginx        # 깨끗이 제거(무엇을 만들었는지 Helm 이 안다)
```

kind 전용 값은 `ingress-nginx-values.yaml` 에 **선언**으로 있다:
`hostPort.enabled: true`(노드의 80/443 을 직접 점유), `nodeSelector: {ingress-ready: "true"}`,
컨트롤플레인 toleration, `publishService.enabled: false` + `publish-status-address: localhost`.

> **Helm 3 과 4 의 `--wait` 차이(주의).** 내 노트북은 Helm **4.2.3**, GitHub 러너는 **3.21.3** 이다.
> Helm 4 에서 `--wait` 는 불리언이 아니라 전략을 받는 플래그로 바뀌었다
> (`--wait WaitStrategy[=watcher]`, 생략 시 기본은 `hookOnly`).
> 값 없이 `--wait` 만 쓰면 `watcher` 가 되어 Helm 3 과 같은 뜻이므로
> `--wait --timeout 5m` 은 **양쪽에서 동일하게 동작한다**. 다만 `--wait 5m` 처럼 쓰면
> Helm 4 가 `5m` 을 전략 이름으로 읽어 실패하니 붙여 쓰지 말 것.

---

## 5. 흐름 — 명령 하나가 클러스터에 닿기까지

```
$ kubectl apply -k deploy/k8s/overlays/local

 ① overlays/local/kustomization.yaml 을 읽는다
      resources: [../../base]                → base 를 먼저 빌드하러 내려간다
 │
 ├─② base/kustomization.yaml
 │     resources: [../../config, namespace.yaml, …] → config 를 먼저 빌드하러 내려간다
 │   │
 │   └─③ config/kustomization.yaml
 │         configMapGenerator ×7  → 파일을 읽어 ConfigMap 생성, 이름에 내용 해시 부착
 │         (shopsaga-common-49f7g8bmk6 …)
 │
 │  ④ base 로 돌아와 매니페스트 15개를 얹고, secretGenerator 로 auth-jwt-key 생성
 │  ⑤ base 의 변환기 적용
 │       namespace: shopsaga         → 모든 리소스에 주입(43개 중 42개. Namespace 자신은 제외)
 │       labels: part-of=shopsaga    → metadata.labels 에만(선택자는 불변 필드라 안 건드림)
 │       이름 참조 변환기            → Deployment 의 configMap.name 을 해시 이름으로 교정
 │
 ⑥ overlay 로 돌아와 패치 적용
      Service/order-service → type: NodePort, nodePort: 30080
 │
 ⑦ 완성된 YAML 43개를 apiserver 로 전송
```

`⑦` 직전까지는 **클러스터를 전혀 건드리지 않는다.** 그래서 이렇게 미리 볼 수 있다:

```bash
kubectl kustomize deploy/k8s/overlays/local   # 최종 YAML 을 눈으로
kubectl diff      -k deploy/k8s/overlays/local # 지금 클러스터와 뭐가 다른지
```

셸 스크립트 시절에는 **불가능했던 일**이다. "실행해 봐야 아는 것"이 "읽으면 아는 것"이 됐다.

---

## 6. 원리 — 왜 이렇게 동작하나

### ① 왜 해시 접미사가 롤링 업데이트를 일으키나

쿠버네티스 Deployment 컨트롤러는 **파드 템플릿(`spec.template`)의 해시**로 ReplicaSet 을 식별한다.
템플릿이 한 바이트라도 다르면 다른 ReplicaSet 이고, 그러면 새 ReplicaSet 을 만들고
옛것을 줄이는 **롤링 업데이트**가 시작된다.

ConfigMap 의 **내용**은 파드 템플릿에 없다(템플릿에는 *이름*만 있다). 그래서 16b 에서는
내용을 바꿔도 템플릿이 그대로라 아무 일도 안 일어났던 것이다.
생성기는 **내용을 이름에 실어** 템플릿 안으로 밀어 넣는다 — 그래서 동작한다.

> 같은 목적을 Helm 은 어노테이션으로 해결한다:
> `checksum/config: {{ .Files.Get "config/common.yml" | sha256sum }}`
> 원리는 같다 — **내용의 지문을 파드 템플릿에 넣는다.** Kustomize 는 그걸 자동으로 할 뿐이다.

### ② 왜 `includeSelectors: false` 인가

공통 라벨을 붙일 때 무심코 선택자까지 바꾸면 이렇게 된다:

```
The Deployment "order-service" is invalid: spec.selector: Invalid value: …
  field is immutable
```

`Deployment.spec.selector` 는 **불변 필드**다. 이미 떠 있는 Deployment 의 선택자를 바꾸려면
지우고 다시 만드는 수밖에 없다. 그래서 조회·정리용 라벨은 `metadata.labels` 에만 붙인다.

### ③ 왜 "파드 Ready" 와 "webhook 호출 가능"이 다른가

Phase 17 에서 CI 를 깨뜨렸던 그 문제가 여기서도 그대로 유효하다.
ingress-nginx 는 Ingress 를 만들 때 검증 webhook(`:443`)을 호출하는데,
컨트롤러 파드가 Ready 가 된 **뒤에도** 그 Service 의 EndpointSlice 에 주소가 실리기까지
한 박자가 더 걸린다. 그 사이에 apply 하면 `connect: connection refused` 다.

`helm --wait` 는 Deployment 가 Available 이 될 때까지만 기다린다 — **충분하지 않다.**
그래서 `apply.sh` 는 그 뒤에 EndpointSlice 에 주소가 실릴 때까지 한 번 더 기다리고,
그래도 결정적이지 않으므로 `apply -k` 를 최대 3회 재시도한다.
그리고 **다 쓰고도 실패하면 반드시 `exit 1`** 한다(조용히 넘어가면 Ingress 없는 클러스터가
'배포 성공'으로 보고되고 뒤이은 스모크가 엉뚱한 이유로 깨진다).

### ④ 왜 Helm 이 남의 것에 맞나

ingress-nginx 하나를 깔면 Deployment·Service·ServiceAccount·ClusterRole·ClusterRoleBinding·
ValidatingWebhookConfiguration·인증서 발급 Job 까지 **수십 개가 한 덩어리로** 움직인다.
그 조합은 업스트림이 정하는 것이지 내가 정하는 게 아니다.
"파라미터 몇 개만 받고 나머지는 알아서" — 그게 정확히 템플릿 엔진이 잘하는 일이다.

반대로 내 앱의 매니페스트는 내가 전부 이해하고 있고, 바꾸고 싶은 건 몇 군데뿐이다.
그럴 땐 **원본을 그대로 두고 덧칠**하는 쪽이 읽기 쉽다.

---

## 7. 검증 — 실제로 측정한 것

> 환경: macOS(Apple Silicon) · Colima 12GB/6CPU · kind v0.32.0 · k8s v1.36.1 ·
> kubectl v1.36.3(Kustomize 내장 v5.8.1) · Helm v4.2.3

### ① 로드 제한과 그 해법 — 설계 전에 먼저 실험했다

가정을 세우고 바로 확인했다. 두 가지가 궁금했다:
(a) 상위 디렉터리 파일을 정말 못 읽는가, (b) 하위 kustomization 으로 우회하면
**이름 참조 교정이 경계를 넘어 동작하는가**.

```
시도 A: files: ../../config/common.yml
  → error: security; file '/…/config/common.yml' is not in or below '/…/k8s/base'   ✗

시도 B: resources: ../../config  (config/ 를 스스로 kustomization 루트로)
  → ConfigMap  name: shopsaga-common-52d2kftd5d
     Deployment volumes[0].configMap.name: shopsaga-common-52d2kftd5d   ✓ 같이 교정됨
```

(b)가 동작한다는 게 설계의 전제였다. **동작하지 않았다면 이 구조 자체를 못 썼다.**

### ② 배포 — 실제 클러스터에 적용

기존 클러스터(16b 가 raw 매니페스트로 깐 ingress-nginx 가 있는 상태)에서 시작했다.

```
helm upgrade --install 이 소유권 오류로 실패
  → 16b 의 설치분을 먼저 제거해야 했다:
     ns/ingress-nginx · ClusterRole·Binding ×2 · IngressClass/nginx · ValidatingWebhookConfiguration
```

제거 후 `./deploy/k8s/apply.sh`:

| 항목 | 결과 |
|---|---|
| 전체 소요 | **53초** (Helm 설치 + webhook 대기 + apply -k 포함) |
| 생성된 리소스 | ConfigMap 7 + Secret 1 **created**, 나머지 35 configured |
| 앱 롤아웃 | 6개 Deployment 전부 성공, 신규 파드 `restarts=0` |
| 최종 상태 | shopsaga 13 파드 `1/1 Running` + ingress-nginx 1 파드 |

### ③ ★ 설정 변경 → 자동 롤아웃 (이번 Phase 의 핵심 주장)

`deploy/config/common.yml` 에 `info.phase: "18"` 두 줄을 추가하고 **`apply.sh` 만 다시 실행**했다.
`kubectl rollout restart` 는 스크립트 어디에도 없다.

```
ConfigMap:  shopsaga-common-49f7g8bmk6  →  shopsaga-common-2497kkbgk6
Deployment: auth·order·payment·inventory·order-query·gateway  6개 전부 "configured"
파드:       전부 새 이름, RESTARTS=0    (재시작이 아니라 교체)
소요:       44초 (6개 서비스 롤링 완료까지)
중단:       없음 (롤링 업데이트)
```

파드 **안에서** 실제로 반영됐는지까지 확인:

```console
$ kubectl exec -n shopsaga <gateway-pod> -- \
    grep -A3 "^info:" /application/config/10-common/application.yml
info:
  pod: ${POD_NAME:${HOSTNAME:unknown}}
  # 이 값이 바뀌면 ConfigMap 내용 해시가 바뀌고 → 이름이 바뀌고 → 파드가 저절로 갈린다(Phase 18).
  phase: "18"

$ kubectl get pod -n shopsaga <gateway-pod> -o jsonpath='{.spec.volumes[*].configMap.name}'
shopsaga-common-2497kkbgk6 gateway-service-config-7fkt2t2m9d
```

### ④ 기능 회귀 없음 — Saga 완주

```
① Ingress(Helm 으로 새로 깐 것) → gateway  : HTTP 200
② 로그인(secretGenerator 가 넣은 새 RSA 키): 토큰 530자
③ 주문 접수                                : 201
④ PENDING → INVENTORY_RESERVED → CONFIRMED : 4초
⑤ NodePort 30080 (local 오버레이 패치)      : HTTP 200
```

### ⑤ `images:` 변환기가 sed 와 다른 점 — 증거

`overlays/ci` 를 렌더한 결과의 **모든** 이미지:

```
ghcr.io/jyh4358/msa-practice/auth-service:latest          ← 바뀜
ghcr.io/jyh4358/msa-practice/gateway-service:latest       ← 바뀜
ghcr.io/jyh4358/msa-practice/inventory-service:latest     ← 바뀜
ghcr.io/jyh4358/msa-practice/order-query-service:latest   ← 바뀜
ghcr.io/jyh4358/msa-practice/order-service:latest         ← 바뀜
ghcr.io/jyh4358/msa-practice/payment-service:latest       ← 바뀜
apache/kafka:4.3.1          ← 그대로
busybox:1.37                ← 그대로 (initContainer)
grafana/otel-lgtm:0.29.1    ← 그대로
mongo:8                     ← 그대로
postgres:18-alpine          ← 그대로
```

앱 6개만 바뀌고 인프라 5종은 손대지 않았다. **YAML 을 파싱해서 `image` 필드만 본다**는 증거다.
`overlays/local` 은 같은 base 에서 `shopsaga/*:0.0.1` 그대로 나온다.

### ⑥ compose 는 그대로 동작하는가 — Phase 16b 의 핵심 성과 보존 확인

`deploy/config/` 에 `kustomization.yaml` 을 새로 넣었으므로, compose 가 영향을 받는지 확인해야 했다.
kind 노드를 정지해 포트 8000 을 넘기고 전체 스택을 띄웠다.

```
docker compose -f deploy/compose/compose.yml --profile async up -d
  → 13 컨테이너, 12 healthy (kafka-ui 는 헬스체크 없음)
  → 로그인 → 주문 → CONFIRMED : 4초
```

그리고 결정적인 확인 — **k8s 에서 고친 바로 그 설정이 compose 컨테이너에도 보이는가**:

```console
$ docker exec shopsaga-order-service-1 grep -A3 "^info:" /application/config/10-common/application.yml
info:
  pod: ${POD_NAME:${HOSTNAME:unknown}}
  # 이 값이 바뀌면 ConfigMap 내용 해시가 바뀌고 → 이름이 바뀌고 → 파드가 저절로 갈린다(Phase 18).
  phase: "18"
```

같은 파일이다. **설정의 단일 소스가 유지됐다.**

### ⑦ 빌드·테스트

`./gradlew test --rerun-tasks` (캐시를 믿지 않고 전부 다시 실행):

```
BUILD SUCCESSFUL in 2m 7s
테스트 87 / 실패 0 / 에러 0
```

내역:

| 태스크 | 모듈 | 개수 |
|---|---|---|
| `test` | order-service 45 · order-query-service 16 · payment-service 11 · inventory-service 7 · shared/outbox 4 · gateway-service 1 | **84** |
| `contractTest` | inventory-service 2 · order-service 1 (Phase 15 의 Spring Cloud Contract 가 계약 파일에서 **생성**하는 테스트) | **3** |
| | | **87** |

> **세는 법 주의.** 계약 테스트는 **`build/test-results/contractTest/`** 에 따로 쌓인다.
> `build/test-results/test/` 만 더하면 84 가 나와서 "87 이 틀렸나?" 싶어지는데, 87 이 맞다.
> (auth-service 는 테스트가 없다.)

> **함정 하나.** 처음 재실행했을 때 `OrderViewProjectionIntegrationTest` 가
> `Could not find a valid Docker environment` 로 깨졌다. 코드 문제가 아니라
> **셸에 `DOCKER_HOST` 를 안 넘겨서** Testcontainers 가 Colima 소켓을 못 찾은 것이다.
> Testcontainers 를 쓰는 테스트를 로컬에서 돌릴 땐 이게 필요하다:
> ```bash
> export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
> export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
> ```
> (덧붙여, 그냥 `./gradlew build` 만 돌리면 테스트가 **up-to-date 로 건너뛰어져** 초록불이 나온다.
>  "빌드 통과"를 테스트 증거로 삼으려면 실제로 실행됐는지부터 확인할 것.)

### ⑧ 결함 — 발견했지만 고치지 않은 것

**(a) 아무것도 안 바꿔도 Secret 5개가 매번 `configured` 로 보인다.**

```
$ kubectl apply -k deploy/k8s/overlays/local
  38 unchanged
   5 configured     ← Secret 5개(DB 4 + auth-jwt-key 1). 연속 실행해도 계속 이렇다.

$ kubectl diff -k deploy/k8s/overlays/local
  (출력 없음, 종료코드 0)   ← 실제 차이는 없다
```

원인을 끝까지 파 보니 **둘이 겹쳐 있었다.**

- **DB 자격증명 4개**: 매니페스트는 `stringData` 로 쓰는데 클러스터는 `data`(base64)로 저장한다.
  `last-applied-configuration` 어노테이션에는 `stringData` 가 남아 있어, 3-way 병합이 매번
  "stringData 를 다시 설정하는" 패치를 만든다.

- **`auth-jwt-key`**: 이건 양쪽 다 `data` 인데도 그랬다. 디코드해 보니 값은 **완전히 동일**한데
  base64 **문자열**이 달랐다.

  ```
  last-applied len 2305 / 클러스터 저장본 len 2272
  디코드 결과: 둘 다 1704 바이트, 완전 동일
  last-applied 안의 줄바꿈: 33개 / 저장본: 0개
  줄바꿈만 제거하면 문자열까지 동일
  ```

  Kustomize 가 base64 를 **70자마다 줄바꿈해서** YAML 에 넣고, API 서버는 줄바꿈 없는 정규형으로
  저장하기 때문이다.

→ **의미상 무해한 표시 문제**다(`kubectl diff` 가 정상적으로 "차이 없음"을 보여 준다).
서버사이드 적용(`kubectl apply --server-side`)으로 옮기면 사라지지만,
그건 필드 소유권 모델이 바뀌는 별개의 변경이라 이번엔 손대지 않았다.

**(b) 옛 해시 이름의 ConfigMap 이 클러스터에 쌓인다.**
설정을 한 번 고쳤더니 `shopsaga-common-49f7g8bmk6`(옛것)과 `-2497kkbgk6`(새것)이 **둘 다** 남았다.
`kubectl apply -k` 는 "지금 렌더에 없는 것"을 지우지 않는다.
지금은 수동으로 지워야 한다 — 이건 GitOps 의 자동 prune 이 푸는 문제다(§8).

**(c) 16b 가 명령형으로 만든 접미사 없는 리소스 8개가 고아로 남아 있었다.**
`shopsaga-common`, `*-service-config` 6개, `auth-jwt-key`. 아무도 참조하지 않아 수동 삭제했다.
마이그레이션 때 한 번 겪고 끝나는 일이지만, (b)와 같은 뿌리다.

---

## 8. 이번 단계의 한계 → 어디서 해결되나

| # | 한계 | 지금 상태 | 어디서 해결되나 |
|---|---|---|---|
| 1 | **배포가 여전히 수동** | CI 는 "배포 가능함"만 증명한다. 실제 반영은 내가 `apply.sh` 를 친다 | **GitOps(Argo CD/Flux)** — Git 을 단일 소스로 자동 동기화 |
| 2 | **옛 해시 리소스가 안 지워짐** | `apply -k` 에 prune 이 없다(§7-⑧b) | Argo CD 의 자동 prune, 또는 `kubectl apply --prune --applyset` |
| 3 | Secret 이 매번 `configured` | 표시상 문제, 동작 영향 없음(§7-⑧a) | 서버사이드 적용(`--server-side`)으로 전환 |
| 4 | **Secret 이 여전히 base64 일 뿐** | DB 비밀번호가 git 에 평문으로 있다(dev 값) | External Secrets Operator · Vault · SOPS |
| 5 | RSA 키가 **머신마다 다름** | `.secrets/` 가 gitignore 라 노트북과 CI 의 키가 다르다 | 위와 같음(비밀 관리 시스템) |
| 6 | overlay 가 **2개뿐** | local·ci. staging/prod 가 없다 | 실제 환경이 생길 때 추가 |
| 7 | 컨테이너가 **root 로 실행** | `runAsNonRoot`·`readOnlyRootFilesystem` 없음 | 보안 기본값 강화(Pod Security Standards) |
| 8 | **NetworkPolicy 없음** | 모든 파드가 서로 자유롭게 통신 | NetworkPolicy · 서비스 메시 |
| 9 | **HPA 없음** | metrics-server 가 없어 자동 스케일 불가 | metrics-server + HPA |
| 10 | DB·Kafka 가 **Deployment** | StatefulSet 이 아니다. PVC 는 local-path, 단일 노드, 단일 브로커 | 운영으로 간다면 StatefulSet/오퍼레이터 |
| 11 | 이미지 **취약점 스캔·서명 없음** | Trivy·cosign·SBOM 미적용 | CI 에 잡 추가(가성비 좋음) |
| 12 | CI 스모크가 **행복 경로 하나** | 실패 경로(재고 부족→보상)는 CI 에서 안 돈다 | 스모크 시나리오 확장 |
| 13 | Helm 버전이 **로컬 4 / CI 3** | `--wait --timeout` 은 양쪽 호환(§4-⑤)이지만 스큐는 스큐다 | CI 에서 Helm 버전 고정 |

---

## 9. 용어

| 용어 | 뜻 |
|---|---|
| **선언적(declarative)** | "이렇게 되어 있어야 한다"를 적는 방식. 반대는 명령형(imperative, "이렇게 해라") |
| **멱등(idempotent)** | 몇 번 실행해도 결과가 같은 성질. `apply` 와 `helm upgrade --install` 이 그렇다 |
| **prune** | 선언에 없어진 리소스를 클러스터에서도 지우는 것 |
| **ReplicaSet** | Deployment 가 파드 템플릿 버전마다 만드는 중간 객체. 롤링 업데이트의 단위 |
| **불변 필드(immutable field)** | 만든 뒤 바꿀 수 없는 필드. `Deployment.spec.selector` 가 대표적 |
| **3-way merge** | `last-applied` 어노테이션 · 현재 클러스터 상태 · 새 매니페스트 셋을 비교해 패치를 만드는 방식 |
| **EndpointSlice** | Service 뒤에 실제로 어떤 파드 IP 가 붙어 있는지 담는 객체 |
| **admission webhook** | 리소스를 만들 때 apiserver 가 호출해 검증·변형하는 외부 훅 |
| **hostPort** | 파드가 노드의 특정 포트를 직접 점유하는 것. 노드당 하나만 가능 |
| **toleration / taint** | taint 는 노드가 거는 "출입 금지", toleration 은 파드가 가진 "출입증" |

---

## 10. 참고

- Kustomize 공식 — <https://kubectl.docs.kubernetes.io/references/kustomize/>
  - `configMapGenerator` / `secretGenerator` — 해시 접미사와 이름 참조 교정
  - 로드 제한(`LoadRestrictionsRootOnly`) — 왜 상위 디렉터리를 못 읽는가
- Helm 공식 — <https://helm.sh/docs/> (Helm 4 의 `--wait` 전략 변경 포함)
- ingress-nginx 차트 — <https://github.com/kubernetes/ingress-nginx/tree/main/charts/ingress-nginx>
- kind — 노드 `labels`, `extraPortMappings` — <https://kind.sigs.k8s.io/docs/user/configuration/>
- 이 프로젝트: [Phase 16 — 로컬 k8s](./PHASE-16-KUBERNETES.md) · [Phase 17 — CI/CD](./PHASE-17-CICD.md)
- `deploy/k8s/README.md` — 실행·정리·함정 표

---

*다음 단계 후보: GitOps(Argo CD)로 §8-1·2 를 한꺼번에 해결하기 · 보안 기본값(runAsNonRoot·NetworkPolicy) ·
Trivy/cosign 을 CI 에 추가 · Spring Boot 4.1 + Spring Cloud Oakwood 이전.*
