# deploy/k8s — 로컬 Kubernetes(kind) 배포 (Phase 16)

Phase 7~15 의 compose 스택을 **같은 이미지 그대로** 쿠버네티스로 옮긴 것이다.
자세한 설명은 [`docs/PHASE-16-KUBERNETES.md`](../../docs/PHASE-16-KUBERNETES.md).

## ⚠️ 시작 전

**compose 와 kind 를 동시에 띄우지 말 것.** 18GB 노트북에서 RAM 최대 고비다.

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
docker compose -f deploy/compose/compose.yml --profile async down   # 먼저 내린다
colima list      # MEMORY 가 12GiB 인지 확인 (아니면 아래 참고)
```

Colima 를 12GB 로 올리려면(디스크·이미지·볼륨은 보존된다):

```bash
colima stop && colima start --memory 12 --cpu 6
```

## 파일

| 파일 | 내용 |
|---|---|
| `kind-cluster.yaml` | kind 클러스터 정의(단일 노드 + 포트 매핑 30080·8000) |
| `build-and-load.sh` | bootJar → 도커 이미지 → `kind load` (레지스트리 없이) |
| `00-namespace.yaml` | 네임스페이스 `shopsaga` |
| `10-secrets.yaml` | DB 자격증명 Secret (dev 값) |
| `20-order-db.yaml` | Postgres: PVC + Deployment + Service |
| `30-auth-service.yaml` | 토큰 발급용 보조 서비스: ConfigMap + Deployment + Service |
| `40-order-service.yaml` | **주인공**: ConfigMap + Deployment(probe·Secret·Downward API) + NodePort Service |

## 전체 실행 (16a)

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock

kind create cluster --config deploy/k8s/kind-cluster.yaml     # ~2분
./deploy/k8s/build-and-load.sh                                # bootJar + 이미지 + kind load
kubectl apply -f deploy/k8s/00-namespace.yaml \
               -f deploy/k8s/10-secrets.yaml \
               -f deploy/k8s/20-order-db.yaml \
               -f deploy/k8s/30-auth-service.yaml \
               -f deploy/k8s/40-order-service.yaml

kubectl wait --for=condition=ready pod -l app=order-service -n shopsaga --timeout=300s
```

> order-service 가 처음 2~3회 `CrashLoopBackOff` 로 보이는 건 **정상**이다.
> k8s 에는 compose 의 `depends_on` 이 없어 DB 보다 먼저 뜨고, Flyway 가 연결에 실패해 죽는다.
> DB 가 Ready 되면 다음 재시도에서 성공한다 — "순서를 보장하지 말고 실패하고 다시 하라"가 k8s 의 방식이다.

## 스모크

```bash
# ① NodePort 로 직접 (macOS → Colima → kind 노드 → Service → Pod)
curl -s localhost:30080/actuator/health | python3 -m json.tool
curl -s localhost:30080/actuator/health/liveness      # livenessState 만
curl -s localhost:30080/actuator/health/readiness     # readinessState + db

# ② 토큰 발급 (auth-service 는 ClusterIP → port-forward 로 임시 노출)
kubectl port-forward -n shopsaga svc/auth-service 9000:9000 &
TOKEN=$(curl -s -X POST localhost:9000/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"secret"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')

# ③ 주문 (Kafka 가 없어도 201 PENDING — outbox 덕분)
curl -s -X POST localhost:30080/orders -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"customerId":"cccc1111-1111-1111-1111-111111111111",
       "items":[{"productId":"22222222-2222-2222-2222-222222222222","quantity":2,"unitPrice":10.00}]}'

# ④ 어느 파드가 받았는지 (Downward API)
curl -s localhost:30080/actuator/info -H "Authorization: Bearer $TOKEN"
```

## 실습 (문서 §7 의 실측 재현)

```bash
# 자가치유 — 파드를 지워도 Deployment 가 다시 만든다
kubectl delete pod -n shopsaga -l app=order-service

# 스케일 아웃 — 같은 NodePort 가 여러 파드로 분산(라운드로빈 아니라 '무작위')
kubectl scale deployment/order-service -n shopsaga --replicas=3
for i in $(seq 1 30); do curl -s localhost:30080/actuator/info -H "Authorization: Bearer $TOKEN" \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["pod"])'; done | sort | uniq -c

# readiness ≠ liveness — DB 를 죽여도 파드는 재시작되지 않고 트래픽만 끊긴다
kubectl scale deployment/order-db -n shopsaga --replicas=0
kubectl get pods -n shopsaga -w        # READY 0/1, RESTARTS 는 그대로
kubectl scale deployment/order-db -n shopsaga --replicas=1   # 자동 복귀

# 설정 변경 — ConfigMap 만 고치면 파드는 안 바뀐다(명시적 롤아웃 필요)
kubectl edit configmap/order-service-config -n shopsaga
kubectl rollout restart deployment/order-service -n shopsaga
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
| `ImagePullBackOff` | 이미지를 kind 노드에 넣지 않음. 또는 태그가 `latest` 라 `imagePullPolicy=Always` | `./deploy/k8s/build-and-load.sh` · 태그 명시 + `IfNotPresent` |
| probe 가 401 로 실패 | 보안 설정이 `/actuator/health` 만 허용 | `/actuator/health/**` 도 permitAll (Phase 16 에서 수정함) |
| 새 DB 파드가 `Pending` | RWO 볼륨 + `RollingUpdate` → 두 파드가 같은 PVC 경합 | Deployment 에 `strategy: Recreate` |
| `CrashLoopBackOff` 반복 | DB 가 아직 안 뜸(정상) / 설정 오류(비정상) | `kubectl logs --previous` 로 구분 |
| 파드는 Ready 인데 502·연결 거부 | Service 의 `selector` 가 파드 라벨과 불일치 | `kubectl get endpointslices` 가 비어 있는지 확인 |
