#!/usr/bin/env bash
# Phase 18 — 비밀 '재료' 부트스트랩.
#
#   ./deploy/k8s/bootstrap-secrets.sh
#
# base/kustomization.yaml 의 secretGenerator 가 읽는 파일을 만든다.
# 이 파일들은 **git 에 없다**(.gitignore) — 개인키를 리포지토리에 커밋하지 않기 위해서다.
# 그래서 갓 clone 한 곳(예: CI 러너)에서는 `kustomize build` 조차 실패한다:
#
#   Error: ... evalsymlink failure on '.../base/.secrets/auth-jwt-key.pem'
#          : no such file or directory
#
# ⚠️ 이 스크립트가 apply.sh 에서 분리되어 있는 이유가 정확히 그거다.
#   CI 는 배포(apply.sh)보다 **먼저** 렌더를 검증하고 싶어 하는데, 렌더에도 이 파일이 필요하다.
#   (Phase 18 을 처음 푸시했을 때 CI 가 바로 이 순서 문제로 깨졌다 — 문서 §7-⑨.)
#
# 운영이라면 이 자리는 External Secrets Operator / Vault / SOPS 의 몫이다.
# 지금은 "개인키가 git 에 없다"까지만 지킨다.
set -euo pipefail

cd "$(dirname "$0")/../.."          # 리포지토리 루트
SECRETS=deploy/k8s/base/.secrets

if [ -f "$SECRETS/auth-jwt-key.pem" ] && [ -f "$SECRETS/auth-jwt-key-id.txt" ]; then
  echo "▶ auth-jwt-key 이미 존재 — 유지(다시 만들면 기존 토큰이 전부 무효가 된다)"
  exit 0
fi

echo "▶ auth-service RSA 서명 키 생성 (PKCS#8)"
mkdir -p "$SECRETS"

# ⚠️ PKCS#1(`BEGIN RSA PRIVATE KEY`)이 아니라 PKCS#8(`BEGIN PRIVATE KEY`)이어야 한다.
#    RsaKeyConfig 가 PKCS#8 만 읽는다. `openssl genrsa` 는 PKCS#1 을 내므로 genpkey 를 쓴다.
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$SECRETS/auth-jwt-key.pem" 2>/dev/null

# ⚠️ macOS 는 `shasum`, 리눅스는 `sha256sum` 이다. 둘 다 없는 환경은 없다고 봐도 되지만
#    한쪽만 가정하면 "내 노트북에선 되는데 CI 에선 안 되는" 전형적인 함정이 된다.
if command -v sha256sum >/dev/null 2>&1; then
  sha256() { sha256sum; }
else
  sha256() { shasum -a 256; }
fi

# kid 는 공개키 지문에서 파생한다 → 키가 바뀌면 kid 도 반드시 바뀐다(JWKS 캐시가 헷갈리지 않게).
# ⚠️ printf 로 쓴다. echo 를 쓰면 끝에 개행이 붙고, 그게 그대로 환경변수 값이 되어 kid 가 어긋난다.
printf '%s' "shopsaga-$(openssl pkey -in "$SECRETS/auth-jwt-key.pem" -pubout -outform DER 2>/dev/null \
          | sha256 | cut -c1-12)" > "$SECRETS/auth-jwt-key-id.txt"

echo "   kid = $(cat "$SECRETS/auth-jwt-key-id.txt")"
