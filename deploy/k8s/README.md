# deploy/k8s — 로컬 Kubernetes(kind) 배포 (Phase 16 · 18 · 19)

> ## ⚠️ Phase 19 이후: 배포는 Argo CD 가 한다
> Argo CD 를 설치했다면(`install-argocd.sh`) **`apply.sh` 를 쓰지 말 것.**
> `selfHeal` 이 켜져 있어 여기서 적용한 변경은 **6초쯤 뒤 Git 상태로 되돌아간다**(실측).
> 바꾸려면 Git 을 바꾼다 — 그게 이 방식의 요점이자 불편함이다.
>
> ```bash
> ./deploy/k8s/install-argocd.sh                                  # 최초 1회
> kubectl port-forward svc/argocd-server -n argocd 8081:80        # UI
> kubectl get application shopsaga -n argocd -o wide              # 상태
> ```
> 자세한 설명: [`docs/PHASE-19-GITOPS.md`](../../docs/PHASE-19-GITOPS.md)

Phase 7~15 의 compose 스택을 **같은 이미지 그대로** 쿠버네티스로 옮긴 것이다.
자세한 설명은 [`docs/PHASE-16-KUBERNETES.md`](../../docs/PHASE-16-KUBERNETES.md),
그리고 Kustomize/Helm 전환은 [`docs/PHASE-18-KUSTOMIZE.md`](../../docs/PHASE-18-KUSTOMIZE.md).

> **Phase 18 이후 구조**: 매니페스트는 `base/` 로 옮겼고 환경 차이는 `overlays/` 가 담는다.
> 배포는 `kubectl apply -k` 한 줄이고, 서드파티(ingress-nginx)만 Helm 릴리스로 깐다.
> **내 것은 Kustomize, 남의 것은 Helm** — 실무에서 가장 흔한 조합이다.

## ⚠️ 시작 전

**compose 와 kind 를 동시에 띄우지 말 것.** 18GB 노트북에서 RAM 최대 고비다.

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
docker compose -f deploy/compose/compose.yml --profile async down   # 먼저 내린다
colima list      # MEMORY 가 12GiB 인지 확인
```

Colima 를 12GB 로 올리려면(디스크·이미지·볼륨은 보존된다):

```bash
colima stop && colima start --memory 12 --cpu 6
```

## 설정은 compose 와 **같은 파일**을 쓴다

```
deploy/config/common.yml          ← 서비스 주소·Kafka·probe·관측성  (공통)
deploy/config/<service>.yml       ← DB 호스트 등                    (서비스별)
        │
        ├─ compose : 바인드 마운트  → /application/config/{10-common,20-service}/application.yml
        └─ k8s     : ConfigMap     → 같은 경로
                     (deploy/config/kustomization.yaml 의 configMapGenerator 가 생성)
```

`deploy/config/kustomization.yaml` 이 **설정 파일 옆에** 있는 이유: Kustomize 는 기본적으로
kustomization 디렉터리 **바깥의 파일**을 못 읽는다(`LoadRestrictionsRootOnly`). 그래서
`deploy/config/` 를 스스로 kustomization 루트로 만들고, `base` 는 `resources: [../../config]` 로
디렉터리째 끌어온다 — 그러면 compose 와 파일을 계속 공유하면서 선언적으로 ConfigMap 을 만들 수 있다.

Spring Boot 는 `optional:file:./config/*/` 를 기본 탐색 경로로 갖고, 하위 디렉터리를 **이름순으로 읽어
뒤쪽이 이긴다**(10-common < 20-service). 그래서 이미지·코드를 고치지 않고 설정만 갈아끼울 수 있다.

## 파일

| 파일 | 내용 |
|---|---|
| `kind-cluster.yaml` | kind 클러스터 정의(단일 노드 + 포트 매핑 30080·8000 + `ingress-ready` 노드 라벨) |
| `build-and-load.sh` | bootJar → 도커 이미지 → `kind load` (레지스트리 없이) |
| `apply.sh` | 부트스트랩 + ingress-nginx(Helm) + `kubectl apply -k` (Argo CD 없이 쓰는 경로) |
| `bootstrap-secrets.sh` | auth RSA 키 생성 + Secret `auth-jwt-key` 를 kubectl 로 주입(멱등) |
| `install-argocd.sh` | Argo CD(Helm) 설치 + Application 등록 — Phase 19 |
| `argocd-values.yaml` | Argo CD 차트 값(파드 7→4 로 축소, UI 는 port-forward) |
| `argocd-application.yaml` | "이 Git 경로와 이 네임스페이스가 같아야 한다" 선언(prune·selfHeal) |
| `overlays/gitops/` | **Argo CD 가 추적하는 유일한 경로.** CI 봇이 이미지 태그를 여기에 커밋한다 |
| `ingress-nginx-values.yaml` | ingress-nginx 차트 값(kind 용 hostPort·nodeSelector·toleration) |
| `base/kustomization.yaml` | 네임스페이스 변환기 · 공통 라벨 · 리소스 목록 · auth 키 `secretGenerator` |
| `base/namespace.yaml` | 네임스페이스 `shopsaga` |
| `base/db-secrets.yaml` | DB 자격증명 (dev 값) |
| `base/{order,payment,inventory}-db.yaml` · `order-query-mongo.yaml` | PVC + Deployment + Service |
| `base/kafka.yaml` | Kafka(KRaft 단일 노드) |
| `base/otel-lgtm.yaml` | 관측성 올인원(Tempo·Loki·Prometheus·Grafana) |
| `base/{auth,order,payment,inventory,order-query,gateway}-service.yaml` | 애플리케이션 6종 |
| `base/ingress.yaml` | Ingress(`/` → gateway, `/grafana` → Grafana) |
| `overlays/local/` | 내 노트북용 — order-service 를 NodePort 30080 으로도 노출(게이트웨이 우회 디버깅) |
| `overlays/ci/` | CI 용 — 이미지를 GHCR 참조로 교체(`images:` 변환기) |

> `base/.secrets/` 는 `.gitignore` 대상이다. auth-service 의 RSA 개인키가 여기 생기고,
> `secretGenerator` 가 그걸 읽어 Secret 을 만든다. **없으면 렌더가 실패한다** — 그게 의도다.

## 전체 실행

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock

kind create cluster --config deploy/k8s/kind-cluster.yaml   # ~2분 (최초 1회)
./deploy/k8s/build-and-load.sh all                          # bootJar + 이미지 6종 + kind load
./deploy/k8s/apply.sh                                       # 키 부트스트랩 + ingress-nginx + apply -k

kubectl get pods -n shopsaga -w        # 전부 Running 1/1 될 때까지 (~3분)
```

적용하기 전에 **무엇이 만들어지는지 눈으로 보려면** (렌더만 하고 클러스터는 안 건드린다):

```bash
kubectl kustomize deploy/k8s/overlays/local        # 최종 YAML 전체
kubectl diff      -k deploy/k8s/overlays/local     # 지금 클러스터와의 차이
```

> 앱이 처음 몇 번 `CrashLoopBackOff` 로 보이는 건 **정상**이다.
> k8s 에는 compose 의 `depends_on` 이 없어 DB·Kafka 보다 먼저 뜨고, 연결에 실패해 죽는다.
> 의존성이 Ready 되면 다음 재시도에서 성공한다 — "순서를 보장하지 말고 실패하고 다시 하라"가 k8s 의 방식이다.

### 설정만 바꿨을 때

```bash
vi deploy/config/common.yml
./deploy/k8s/apply.sh              # 끝. 롤아웃은 저절로 일어난다.
```

Phase 16b 의 `--config` 플래그(= `kubectl rollout restart` 를 수동으로 걸던 것)는 **없어졌다**.
`configMapGenerator` 가 내용 해시를 이름에 붙이므로 —

```
common.yml 한 글자 수정
  → shopsaga-common-49f7g8bmk6 → shopsaga-common-2497kkbgk6   (이름이 바뀐다)
  → 그 이름을 참조하는 Deployment 의 파드 템플릿이 바뀐다
  → 롤링 업데이트가 저절로 일어난다                             (실측 44초, 무중단)
```

즉 "설정 변경"이 "배포 변경"으로 **타입 승격**된다. 이게 생성기의 진짜 값어치다.

## 스모크 (compose 시절 명령과 동일하다 — 주소가 :8000 로 같다)

```bash
TOKEN=$(curl -s -X POST localhost:8000/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"secret"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')

# 주문 → PENDING 즉시 반환, 최종 상태는 조회로 확인(결과적 일관성)
curl -s -X POST localhost:8000/orders -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"customerId":"cccc1111-1111-1111-1111-111111111111",
       "items":[{"productId":"22222222-2222-2222-2222-222222222222","quantity":2,"unitPrice":10.00}]}'

curl -s localhost:8000/orders/<id>       -H "Authorization: Bearer $TOKEN"   # CONFIRMED 확인
curl -s "localhost:8000/order-views?customerId=cccc1111-1111-1111-1111-111111111111" -H "Authorization: Bearer $TOKEN"
curl -s localhost:8000/inventory/22222222-2222-2222-2222-222222222222 -H "Authorization: Bearer $TOKEN"
open http://localhost:8000/grafana       # 트레이스·RED 대시보드
```

## 실습 (문서 §7 의 실측 재현)

```bash
# 자가치유 — 파드를 지워도 Deployment 가 다시 만든다
kubectl delete pod -n shopsaga -l app=order-service

# 스케일 아웃 — 같은 주소가 여러 파드로 분산(라운드로빈 아니라 '무작위')
kubectl scale deployment/order-service -n shopsaga --replicas=3
for i in $(seq 1 30); do curl -s localhost:30080/actuator/info -H "Authorization: Bearer $TOKEN" \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["pod"])'; done | sort | uniq -c

# readiness ≠ liveness — DB 를 죽여도 파드는 재시작되지 않고 트래픽만 끊긴다
kubectl scale deployment/order-db -n shopsaga --replicas=0
kubectl get pods -n shopsaga -w        # READY 0/1, RESTARTS 는 그대로
kubectl scale deployment/order-db -n shopsaga --replicas=1   # 자동 복귀

# 무중단 롤링 업데이트 (복제본 2 이상일 때)
kubectl rollout restart deployment/order-service -n shopsaga
kubectl rollout status  deployment/order-service -n shopsaga
```

## 정리

```bash
kubectl delete -k deploy/k8s/overlays/local   # 이 오버레이가 만든 것만 정확히 (권장)
helm uninstall ingress-nginx -n ingress-nginx # Helm 이 설치 목록을 기억하므로 깨끗이 지워진다
kubectl delete namespace shopsaga             # 워크로드 통째로 (PVC 포함 — 데이터 삭제됨)
kind delete cluster --name shopsaga           # 클러스터 통째로
colima stop                                   # VM 까지 (12GB 즉시 회수)
```

> `kubectl delete -k` 가 가능해진 것도 선언적 배포의 부수 효과다 — 무엇을 만들었는지 파일이 알고 있다.
> 다만 **옛 해시 이름의 ConfigMap/Secret 은 남는다**(현재 렌더에 없으므로 delete 대상이 아니다).
> `kubectl get cm -n shopsaga` 로 확인하고 지우거나, GitOps(Argo CD)의 자동 prune 에 맡긴다.

## 자주 밟는 함정

| 증상 | 원인 | 해결 |
|---|---|---|
| `ImagePullBackOff` | 이미지를 kind 노드에 넣지 않음. 또는 태그가 `latest` 라 `imagePullPolicy=Always` | `./deploy/k8s/build-and-load.sh all` · 태그 명시 + `IfNotPresent` |
| probe 가 401 로 실패 | 보안 설정이 `/actuator/health` 만 허용 | `/actuator/health/**` 도 permitAll (Phase 16a 에서 수정함) |
| 기동하자마자 `NoSuchHealthContributorException` | health 그룹에 없는 컨트리뷰터(`db`/`mongo`)를 포함 | `management.endpoint.health.validate-group-membership: false` |
| 새 DB 파드가 `Pending` | RWO 볼륨 + `RollingUpdate` → 두 파드가 같은 PVC 경합 | Deployment 에 `strategy: Recreate` |
| `CrashLoopBackOff` 반복 | 의존성이 아직 안 뜸(정상) / 설정 오류(비정상) | `kubectl logs --previous` 로 구분 |
| Ingress 를 만들었는데 404·연결 거부 | **Ingress 컨트롤러가 없다**(오브젝트만으로는 아무 일도 안 일어남) | `apply.sh` 가 ingress-nginx 를 설치한다 |
| `security; file '…' is not in or below '…'` | Kustomize 는 kustomization 디렉터리 **밖의 파일**을 못 읽는다 | 그 디렉터리를 스스로 kustomization 루트로 만들고 `resources:` 로 참조 |
| `evalsymlink failure on '…/.secrets/…'` | 비밀 재료가 없다(`.gitignore` 대상이라 clone 직후엔 없다) | `./deploy/k8s/bootstrap-secrets.sh` — **렌더만 할 때도 필요하다**(CI 가 이걸로 깨졌다) |
| 스크립트가 CI(리눅스)에서만 실패 | `shasum`(macOS) vs `sha256sum`(리눅스) 처럼 한쪽만 가정 | 둘 다 지원하도록 `command -v` 분기 |
| `helm upgrade` 가 소유권 오류로 실패 | 같은 리소스를 전에 `kubectl apply` 로 깔았다(Helm 이 모름) | 옛 설치분을 지우고(ns + ClusterRole/Binding + IngressClass + webhook) 다시 |
| ingress-nginx 파드가 영원히 `Pending` | 노드에 `ingress-ready=true` 라벨이 없다 | 새 `kind-cluster.yaml` 로 클러스터 재생성, 또는 `kubectl label node --all ingress-ready=true` |
| 안 바꿨는데 Secret 이 매번 `configured` | `stringData`↔`data` 변환 + 생성기의 base64 줄바꿈 (표시상 문제) | `kubectl diff -k` 로 실제 차이 없음을 확인 — 무시해도 된다 |
| Argo CD 가 `Synced` 인데 `Progressing` 에서 안 벗어남 | Git 에 없는 **전제**가 빠졌다(대표적으로 `auth-jwt-key` Secret) | `kubectl get events -n shopsaga` 로 확인 후 `./deploy/k8s/bootstrap-secrets.sh` |
| kubectl 로 바꾼 게 몇 초 뒤 되돌아감 | `selfHeal: true` — 정상 동작이다 | Git 을 바꿀 것. 정말 필요하면 selfHeal 을 잠시 끈다 |
| 옛 해시 ConfigMap 이 prune 안 됨 | Argo CD **이전**에 만들어져 `tracking-id` 가 없다 | 1회 수동 삭제(전환 시점에만 생기는 일) |
| `git push` 가 rejected | CI 봇이 `main` 에 커밋해 로컬이 뒤처졌다 | `git pull --rebase` 후 push |
| Helm 값을 줬는데 안 먹음 | **Helm 은 존재하지 않는 키를 조용히 무시한다** | `helm template …` 으로 렌더 결과를 눈으로 확인 |
| 파드는 Ready 인데 502 | Service 의 `selector` 가 파드 라벨과 불일치 | `kubectl get endpointslices -n shopsaga` 가 비어 있는지 확인 |
| Kafka 클라이언트가 붙었다 끊김 | `advertised.listeners` 가 Service 이름과 다름 | `PLAINTEXT://kafka:19092` 로 일치시킬 것 |
| mongo 파드 running 인데 포트 안 열림 | Apple Silicon 에서 amd64 변이를 받음(AVX 없음) | `docker pull --platform linux/arm64 mongo:8` 후 재적재 |
