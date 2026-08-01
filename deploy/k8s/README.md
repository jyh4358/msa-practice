# deploy/k8s — 로컬 Kubernetes(kind) 배포 (Phase 16)

Phase 7~15 의 compose 스택을 **같은 이미지 그대로** 쿠버네티스로 옮긴 것이다.
자세한 설명은 [`docs/PHASE-16-KUBERNETES.md`](../../docs/PHASE-16-KUBERNETES.md).

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
        └─ k8s     : ConfigMap     → 같은 경로 (apply.sh 가 파일로부터 생성)
```

Spring Boot 는 `optional:file:./config/*/` 를 기본 탐색 경로로 갖고, 하위 디렉터리를 **이름순으로 읽어
뒤쪽이 이긴다**(10-common < 20-service). 그래서 이미지·코드를 고치지 않고 설정만 갈아끼울 수 있다.

## 파일

| 파일 | 내용 |
|---|---|
| `kind-cluster.yaml` | kind 클러스터 정의(단일 노드 + 포트 매핑 30080·8000) |
| `build-and-load.sh` | bootJar → 도커 이미지 → `kind load` (레지스트리 없이) |
| `apply.sh` | **ConfigMap 생성 + RSA 키 생성 + ingress-nginx 설치 + 전체 적용** |
| `00-namespace.yaml` | 네임스페이스 `shopsaga` |
| `10-secrets.yaml` | DB 자격증명 (dev 값) |
| `20`~`23` | order-db · payment-db · inventory-db · order-query-mongo (PVC+Deployment+Service) |
| `24-kafka.yaml` | Kafka(KRaft 단일 노드) |
| `25-otel-lgtm.yaml` | 관측성 올인원(Tempo·Loki·Prometheus·Grafana) |
| `30`~`35` | auth · order · payment · inventory · order-query · gateway |
| `50-ingress.yaml` | Ingress(`/` → gateway, `/grafana` → Grafana) |

## 전체 실행

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock

kind create cluster --config deploy/k8s/kind-cluster.yaml   # ~2분 (최초 1회)
./deploy/k8s/build-and-load.sh all                          # bootJar + 이미지 6종 + kind load
./deploy/k8s/apply.sh                                       # 설정·비밀·ingress·전체 배포

kubectl get pods -n shopsaga -w        # 전부 Running 1/1 될 때까지 (~3분)
```

> 앱이 처음 몇 번 `CrashLoopBackOff` 로 보이는 건 **정상**이다.
> k8s 에는 compose 의 `depends_on` 이 없어 DB·Kafka 보다 먼저 뜨고, 연결에 실패해 죽는다.
> 의존성이 Ready 되면 다음 재시도에서 성공한다 — "순서를 보장하지 말고 실패하고 다시 하라"가 k8s 의 방식이다.

### 설정만 바꿨을 때

```bash
vi deploy/config/common.yml
./deploy/k8s/apply.sh --config     # ConfigMap 갱신 + 전 서비스 롤아웃
```

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
kubectl delete namespace shopsaga        # 워크로드만 (PVC 포함 — 데이터 삭제됨)
kind delete cluster --name shopsaga      # 클러스터 통째로
colima stop                              # VM 까지 (12GB 즉시 회수)
```

## 자주 밟는 함정

| 증상 | 원인 | 해결 |
|---|---|---|
| `ImagePullBackOff` | 이미지를 kind 노드에 넣지 않음. 또는 태그가 `latest` 라 `imagePullPolicy=Always` | `./deploy/k8s/build-and-load.sh all` · 태그 명시 + `IfNotPresent` |
| probe 가 401 로 실패 | 보안 설정이 `/actuator/health` 만 허용 | `/actuator/health/**` 도 permitAll (Phase 16a 에서 수정함) |
| 기동하자마자 `NoSuchHealthContributorException` | health 그룹에 없는 컨트리뷰터(`db`/`mongo`)를 포함 | `management.endpoint.health.validate-group-membership: false` |
| 새 DB 파드가 `Pending` | RWO 볼륨 + `RollingUpdate` → 두 파드가 같은 PVC 경합 | Deployment 에 `strategy: Recreate` |
| `CrashLoopBackOff` 반복 | 의존성이 아직 안 뜸(정상) / 설정 오류(비정상) | `kubectl logs --previous` 로 구분 |
| Ingress 를 만들었는데 404·연결 거부 | **Ingress 컨트롤러가 없다**(오브젝트만으로는 아무 일도 안 일어남) | `apply.sh` 가 ingress-nginx 를 설치한다 |
| 파드는 Ready 인데 502 | Service 의 `selector` 가 파드 라벨과 불일치 | `kubectl get endpointslices -n shopsaga` 가 비어 있는지 확인 |
| Kafka 클라이언트가 붙었다 끊김 | `advertised.listeners` 가 Service 이름과 다름 | `PLAINTEXT://kafka:19092` 로 일치시킬 것 |
| mongo 파드 running 인데 포트 안 열림 | Apple Silicon 에서 amd64 변이를 받음(AVX 없음) | `docker pull --platform linux/arm64 mongo:8` 후 재적재 |
