#!/usr/bin/env bash
# Phase 18 — 전체 플랫폼을 kind 클러스터에 배포한다.
#
#   ./deploy/k8s/apply.sh              # = overlays/local (내 노트북)
#   ./deploy/k8s/apply.sh ci           # = overlays/ci    (GHCR 이미지)
#
# ─────────────────────────────────────────────────────────────────────────────
# 16b 의 이 스크립트와 무엇이 달라졌나
# ─────────────────────────────────────────────────────────────────────────────
# 16b 에서는 매니페스트만으로 안 되는 일이 셋이라 이 스크립트가 그걸 명령형으로 때웠다:
#   ① ConfigMap 7개를 `kubectl create configmap --from-file` 루프로 생성
#   ② auth-service RSA 키 생성 → `kubectl create secret`
#   ③ ingress-nginx 를 원격 raw 매니페스트 URL 로 `kubectl apply`
#   ④ 그리고 ConfigMap 이 바뀌어도 파드가 안 갈리므로 `--config` 플래그로 rollout restart 를 수동 실행
#
# 지금은:
#   ① → deploy/config/kustomization.yaml 의 configMapGenerator (선언적)
#   ② → base/kustomization.yaml 의 secretGenerator. 여기 남은 건 **키 파일 생성**뿐이다.
#        (키 자체는 git 에 없어야 하므로 '재료를 준비하는' 이 단계는 명령형으로 남는 게 맞다.
#         운영이라면 External Secrets Operator / Vault 가 이 자리를 대신한다.)
#   ③ → Helm 릴리스 (버전이 명시되고, helm list 로 조회되고, helm uninstall 로 지워진다)
#   ④ → **사라졌다.** 생성기가 내용 해시를 이름에 붙이므로 설정을 고치면 이름이 바뀌고,
#        파드 템플릿이 바뀌므로 롤링 업데이트가 저절로 일어난다. 그냥 이 스크립트를 다시 돌리면 된다.
set -euo pipefail

cd "$(dirname "$0")/../.."          # 리포지토리 루트
export DOCKER_HOST="${DOCKER_HOST:-unix://$HOME/.colima/default/docker.sock}"

OVERLAY="${1:-local}"
K8S=deploy/k8s
INGRESS_CHART_VERSION=4.13.9        # controller 1.13.9 — Phase 16b 에서 검증한 1.13 계열

[ -d "$K8S/overlays/$OVERLAY" ] || { echo "✗ 알 수 없는 오버레이: $OVERLAY (local | ci)"; exit 1; }

# ── ① 비밀 '재료' — git 에 없는 것만 여기서 만든다 ────────────────────────────
# 별도 스크립트인 이유: **렌더에도 필요**하기 때문이다. CI 는 배포 전에 `kustomize build` 로
# 이미지 치환 결과를 먼저 검증하는데, 그 시점에도 이 파일이 있어야 한다(문서 §7-⑨).
"$K8S/bootstrap-secrets.sh"

# ── ② 서드파티: ingress-nginx (Helm 릴리스) ──────────────────────────────────
# 노드 라벨은 kind-cluster.yaml 이 붙인다. 없으면 컨트롤러가 영원히 Pending 이 되는데
# 그 증상이 원인을 전혀 안 알려주므로, 여기서 먼저 확인하고 명확히 죽는다.
if [ -z "$(kubectl get nodes -l ingress-ready=true -o name 2>/dev/null)" ]; then
  echo "✗ ingress-ready=true 라벨이 붙은 노드가 없다."
  echo "  이 클러스터는 Phase 18 이전의 kind-cluster.yaml 로 만들어졌다. 둘 중 하나를 하라:"
  echo "    kind delete cluster --name shopsaga && kind create cluster --config $K8S/kind-cluster.yaml"
  echo "    kubectl label node --all ingress-ready=true --overwrite   # 기존 클러스터를 살리려면"
  exit 1
fi

echo "▶ ingress-nginx (Helm chart $INGRESS_CHART_VERSION)"
helm repo add ingress-nginx https://kubernetes.github.io/ingress-nginx >/dev/null 2>&1 || true
helm repo update ingress-nginx >/dev/null
# upgrade --install = 없으면 설치, 있으면 갱신. 같은 명령을 몇 번 돌려도 결과가 같다(멱등).
# --wait 는 컨트롤러 Deployment 가 Available 이 될 때까지 기다린다.
helm upgrade --install ingress-nginx ingress-nginx/ingress-nginx \
  --namespace ingress-nginx --create-namespace \
  --version "$INGRESS_CHART_VERSION" \
  --values "$K8S/ingress-nginx-values.yaml" \
  --wait --timeout 5m

# ⚠️⚠️ 파드가 Ready 라고 admission webhook 을 부를 수 있는 건 아니다(Phase 17 에서 CI 가 이걸로 깨졌다).
#   Ingress 를 만들 때 검증 webhook(:443)이 호출되는데, 그 Service 의 EndpointSlice 에 주소가
#   실리기까지 한 박자가 더 걸린다. 그 사이에 apply 하면:
#     failed calling webhook "validate.nginx.ingress.kubernetes.io":
#     dial tcp 10.96.x.x:443: connect: connection refused
#   "파드 Ready" 와 "Service 로 트래픽이 간다" 는 다른 사건이다.
#   ★ 이건 CI 가 **빨라지자** 드러났다(1차 실행은 이미지 pull 이 느려 우연히 피해 갔다).
echo "▶ admission webhook 엔드포인트 대기"
for _ in $(seq 1 60); do
  addrs=$(kubectl get endpointslices -n ingress-nginx \
    -l kubernetes.io/service-name=ingress-nginx-controller-admission \
    -o jsonpath='{.items[*].endpoints[*].addresses[0]}' 2>/dev/null || true)
  [ -n "$addrs" ] && break
  sleep 2
done

# ── ③ 나머지 전부를 한 번에 ──────────────────────────────────────────────────
# 네임스페이스·설정·비밀·인프라·앱·Ingress 가 전부 이 한 줄에 들어 있다.
# k8s 에는 기동 순서 개념이 없다(compose 의 depends_on 없음). 순서가 꼭 필요한 한 곳
# — Kafka 토픽을 기동 시 한 번만 만드는 Spring KafkaAdmin — 만 initContainer 로 막아 뒀다.
echo "▶ kubectl apply -k $K8S/overlays/$OVERLAY"
ok=false
for attempt in 1 2 3; do
  if kubectl apply -k "$K8S/overlays/$OVERLAY"; then ok=true; break; fi
  echo "   실패 — webhook 이 아직 준비되지 않았을 수 있다. 재시도 $attempt/3"
  sleep 5
done
# ⚠️ 재시도를 다 쓰고도 실패했다면 **반드시 실패로 끝내야 한다**. 조용히 넘어가면
#    Ingress 없는 클러스터가 '배포 성공'으로 보고되고, 뒤이은 스모크가 엉뚱한 이유로 깨진다.
if [ "$ok" != true ]; then
  echo "✗ 적용 실패 — ingress-nginx 상태를 확인할 것:"
  kubectl get pods,endpointslices -n ingress-nginx || true
  exit 1
fi

echo ""
echo "✔ 적용 완료. 준비 상태 보기:"
echo "   kubectl get pods -n shopsaga -w"
echo "   curl -s localhost:8000/actuator/health     # Ingress → gateway"
echo ""
echo "  설정을 고쳤다면(deploy/config/*.yml) 이 스크립트를 다시 돌리기만 하면 된다 —"
echo "  ConfigMap 이름의 해시가 바뀌면서 롤링 업데이트가 저절로 일어난다."
