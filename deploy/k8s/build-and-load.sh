#!/usr/bin/env bash
# Phase 16 — 서비스 이미지를 빌드해 kind 클러스터에 적재한다.
#
#   ./deploy/k8s/build-and-load.sh                    # 16a 기본(order-service, auth-service)
#   ./deploy/k8s/build-and-load.sh order-service      # 하나만
#   ./deploy/k8s/build-and-load.sh all                # 전체(16b)
#
# ★ 왜 `kind load` 가 필요한가
#   kind 노드는 그 자체가 컨테이너이고, 안에 **자기만의 containerd 이미지 저장소**를 갖는다.
#   Colima 의 도커 데몬에 이미지를 빌드해 두어도 노드는 그걸 볼 수 없다 →
#   `kubectl apply` 하면 파드가 레지스트리(docker.io/shopsaga/...)로 나가려다 ImagePullBackOff 로 죽는다.
#   `kind load docker-image` 는 도커 이미지를 tar 로 말아 노드 컨테이너 안 containerd 로 밀어 넣는다.
#   (대안: 로컬 레지스트리 컨테이너를 띄우고 kind 가 그걸 보게 하기 — Phase 17 CI/CD 에서 다룬다.)
set -euo pipefail

cd "$(dirname "$0")/../.."          # 리포지토리 루트
export DOCKER_HOST="${DOCKER_HOST:-unix://$HOME/.colima/default/docker.sock}"

CLUSTER=shopsaga
TAG=0.0.1
VERSION=0.0.1-SNAPSHOT

DEFAULT_SERVICES=(order-service auth-service)
ALL_SERVICES=(order-service auth-service payment-service inventory-service order-query-service gateway-service)

if [ $# -eq 0 ]; then
  SERVICES=("${DEFAULT_SERVICES[@]}")
elif [ "$1" = "all" ]; then
  SERVICES=("${ALL_SERVICES[@]}")
else
  SERVICES=("$@")
fi

echo "▶ bootJar 빌드"
./gradlew bootJar -q

for svc in "${SERVICES[@]}"; do
  jar="services/$svc/build/libs/$svc-$VERSION.jar"
  [ -f "$jar" ] || { echo "✗ jar 없음: $jar"; exit 1; }

  echo "▶ [$svc] 이미지 빌드 → shopsaga/$svc:$TAG"
  # ⚠️ 태그를 latest 로 두면 imagePullPolicy 기본값이 Always 가 되어 로컬 이미지를 무시한다.
  docker build -q \
    --build-arg "JAR_FILE=$jar" \
    -f deploy/docker/Dockerfile.service \
    -t "shopsaga/$svc:$TAG" . > /dev/null

  echo "▶ [$svc] kind 노드로 적재"
  kind load docker-image "shopsaga/$svc:$TAG" --name "$CLUSTER"
done

echo ""
echo "✔ 완료. 노드 안 이미지 확인:"
docker exec "${CLUSTER}-control-plane" crictl images 2>/dev/null | grep -E "shopsaga|IMAGE" || true
