#!/usr/bin/env bash
# Phase 19 — Argo CD 설치 + shopsaga Application 등록.
#
#   ./deploy/k8s/install-argocd.sh
#
# 이 스크립트가 끝나면 **더 이상 `apply.sh` 를 쓰지 않는다.**
# 배포는 Argo CD 가 Git 을 보고 스스로 한다.
#
# ⚠️ 전제: `deploy/k8s/overlays/gitops` 가 **origin/main 에 push 되어 있어야 한다.**
#   Argo CD 는 내 노트북의 파일이 아니라 GitHub 저장소를 클론해서 읽기 때문이다.
#   (커밋만 하고 push 안 하면 "path does not exist" 로 Application 이 Degraded 가 된다.)
set -euo pipefail

cd "$(dirname "$0")/../.."          # 리포지토리 루트
export DOCKER_HOST="${DOCKER_HOST:-unix://$HOME/.colima/default/docker.sock}"

K8S=deploy/k8s
ARGO_CHART_VERSION=10.2.2           # Argo CD v3.4.6

# ── ① 비밀 재료 (Argo CD 가 만들어 줄 수 없는 것) ────────────────────────────
# Argo CD 는 Git 만 읽고 임의 스크립트를 실행하지 않는다. 개인키는 Git 에 없으므로
# 사람이(또는 비밀 관리 시스템이) 미리 넣어 둬야 한다.
"$K8S/bootstrap-secrets.sh"

# ── ② ingress-nginx (앱이 밖에서 닿으려면 필요) ──────────────────────────────
# Argo CD 가 shopsaga 의 Ingress 오브젝트는 만들지만, 그걸 실제로 처리할 컨트롤러는
# 여전히 클러스터 사전 조건이다.
if ! helm status ingress-nginx -n ingress-nginx >/dev/null 2>&1; then
  echo "▶ ingress-nginx 가 없다 — apply.sh 가 설치한다"
  echo "  (Argo CD 없이 한 번은 띄워 봤어야 한다. 먼저 ./deploy/k8s/apply.sh 를 돌릴 것)"
  exit 1
fi

# ── ③ Argo CD ───────────────────────────────────────────────────────────────
echo "▶ Argo CD (Helm chart $ARGO_CHART_VERSION)"
helm repo add argo https://argoproj.github.io/argo-helm >/dev/null 2>&1 || true
helm repo update argo >/dev/null
helm upgrade --install argocd argo/argo-cd \
  --namespace argocd --create-namespace \
  --version "$ARGO_CHART_VERSION" \
  --values "$K8S/argocd-values.yaml" \
  --wait --timeout 8m

# ── ④ Application 등록 ──────────────────────────────────────────────────────
# ⚠️ 이 apply 는 "무엇을 배포하라"가 아니라 "무엇과 무엇이 같아야 한다"를 등록하는 것이다.
#   등록 이후의 모든 배포는 Argo CD 가 한다.
echo "▶ Application 등록"
kubectl apply -f "$K8S/argocd-application.yaml"

echo ""
echo "✔ 완료."
echo ""
echo "  UI:"
echo "     kubectl port-forward svc/argocd-server -n argocd 8081:80"
echo "     open http://localhost:8081        # admin / 아래 비밀번호"
echo "     kubectl -n argocd get secret argocd-initial-admin-secret \\"
echo "       -o jsonpath='{.data.password}' | base64 -d ; echo"
echo ""
echo "  CLI 로 상태 보기:"
echo "     kubectl get application shopsaga -n argocd -o wide"
echo "     kubectl describe application shopsaga -n argocd | tail -30"
echo ""
echo "  ⚠️ 이제부터 ./deploy/k8s/apply.sh 는 쓰지 말 것 —"
echo "     selfHeal 이 켜져 있어 Argo CD 가 60초 안에 되돌린다. 바꾸려면 Git 을 바꿔라."
