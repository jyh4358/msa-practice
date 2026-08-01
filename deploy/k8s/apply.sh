#!/usr/bin/env bash
# Phase 16b — 전체 플랫폼을 kind 클러스터에 배포한다.
#
#   ./deploy/k8s/apply.sh              # 설정 생성 + 매니페스트 적용 (+ 필요 시 ingress 설치)
#   ./deploy/k8s/apply.sh --config     # ConfigMap/Secret 만 다시 만들고 롤아웃
#
# 왜 스크립트가 필요한가 — 매니페스트만으로는 안 되는 일이 셋 있다.
#   ① ConfigMap 을 `deploy/config/*.yml` **파일로부터** 만든다.
#      YAML 을 매니페스트 안에 인라인으로 복사하면 compose 와 내용이 갈라진다. 파일 하나를 공유한다.
#   ② auth-service 의 RSA 서명 키를 그 자리에서 생성한다(리포지토리에 개인키를 커밋하지 않기 위해).
#   ③ ingress-nginx 컨트롤러를 설치한다(Ingress 오브젝트만으로는 아무 일도 일어나지 않는다).
#
# ⚠️ Helm/Kustomize 를 쓰면 ①③은 선언적으로 처리된다 — Phase 18 의 과제로 남겨 둔다.
set -euo pipefail

cd "$(dirname "$0")/../.."          # 리포지토리 루트
export DOCKER_HOST="${DOCKER_HOST:-unix://$HOME/.colima/default/docker.sock}"

NS=shopsaga
CFG=deploy/config
K8S=deploy/k8s
ONLY_CONFIG="${1:-}"

kubectl apply -f "$K8S/00-namespace.yaml"

# ── ① 설정: deploy/config/*.yml → ConfigMap ────────────────────────────────────
# compose 는 같은 파일을 바인드 마운트한다. 즉 **설정의 단일 소스**다.
echo "▶ ConfigMap 생성 (deploy/config/ → 클러스터)"
kubectl create configmap shopsaga-common -n "$NS" \
  --from-file=application.yml="$CFG/common.yml" \
  --dry-run=client -o yaml | kubectl apply -f -

for svc in order-service payment-service inventory-service order-query-service gateway-service auth-service; do
  kubectl create configmap "${svc}-config" -n "$NS" \
    --from-file=application.yml="$CFG/${svc}.yml" \
    --dry-run=client -o yaml | kubectl apply -f -
done

# ── ② 비밀: DB 자격증명(매니페스트) + auth 서명 키(생성) ──────────────────────
kubectl apply -f "$K8S/10-secrets.yaml"

# RSA 키는 한 번만 만든다. 이미 있으면 그대로 둔다 —
# 새로 만들면 기존에 발급된 토큰이 전부 무효가 되기 때문이다.
if ! kubectl get secret auth-jwt-key -n "$NS" >/dev/null 2>&1; then
  echo "▶ auth-service RSA 서명 키 생성 (PKCS#8)"
  tmp=$(mktemp -d)
  # ⚠️ PKCS#1(`BEGIN RSA PRIVATE KEY`)이 아니라 PKCS#8 이어야 한다 — RsaKeyConfig 가 PKCS#8 만 읽는다.
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$tmp/key.pem" 2>/dev/null
  kubectl create secret generic auth-jwt-key -n "$NS" \
    --from-file=private-key="$tmp/key.pem" \
    --from-literal=key-id="shopsaga-$(date +%Y%m%d)"
  rm -rf "$tmp"
else
  echo "▶ auth-jwt-key 이미 존재 — 유지(다시 만들면 기존 토큰이 전부 무효가 된다)"
fi

if [ "$ONLY_CONFIG" = "--config" ]; then
  echo "▶ 설정만 갱신 → 롤아웃"
  # ConfigMap 이 바뀌어도 파드는 저절로 안 바뀐다(Spring 이 이미 읽은 설정을 다시 바인딩하지 않는다).
  for d in $(kubectl get deploy -n "$NS" -o name | grep -E "service"); do
    kubectl rollout restart "$d" -n "$NS"
  done
  exit 0
fi

# ── ③ Ingress 컨트롤러 ────────────────────────────────────────────────────────
if ! kubectl get ns ingress-nginx >/dev/null 2>&1; then
  echo "▶ ingress-nginx 설치 (kind 전용 매니페스트)"
  # kind 전용 매니페스트는 컨트롤러를 hostPort 80/443 으로 노출하고
  # `ingress-ready=true` 라벨이 붙은 노드에만 스케줄한다.
  kubectl label node shopsaga-control-plane ingress-ready=true --overwrite
  kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.13.1/deploy/static/provider/kind/deploy.yaml
  echo "▶ ingress-nginx 준비 대기"
  kubectl wait --namespace ingress-nginx --for=condition=ready pod \
    --selector=app.kubernetes.io/component=controller --timeout=180s
fi

# ── ④ 인프라 → 앱 → Ingress 순으로 적용 ───────────────────────────────────────
# k8s 에는 기동 순서 개념이 없다(depends_on 없음). 아래 순서는 사람이 읽기 좋으라고 둔 것일 뿐,
# 실제로는 앱이 먼저 떠서 CrashLoopBackOff 를 몇 번 돈 뒤 인프라가 준비되면 스스로 회복한다.
echo "▶ 인프라 적용"
kubectl apply -f "$K8S/20-order-db.yaml" -f "$K8S/21-payment-db.yaml" \
              -f "$K8S/22-inventory-db.yaml" -f "$K8S/23-order-query-mongo.yaml" \
              -f "$K8S/24-kafka.yaml" -f "$K8S/25-otel-lgtm.yaml"

echo "▶ 앱 적용"
kubectl apply -f "$K8S/30-auth-service.yaml" -f "$K8S/31-order-service.yaml" \
              -f "$K8S/32-payment-service.yaml" -f "$K8S/33-inventory-service.yaml" \
              -f "$K8S/34-order-query-service.yaml" -f "$K8S/35-gateway-service.yaml"

echo "▶ Ingress 적용"
kubectl apply -f "$K8S/50-ingress.yaml"

echo ""
echo "✔ 적용 완료. 준비 상태 보기:"
echo "   kubectl get pods -n $NS -w"
echo "   curl -s localhost:8000/actuator/health     # Ingress → gateway"
