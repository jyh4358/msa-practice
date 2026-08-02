#!/usr/bin/env bash
# Phase 19 — 클러스터 사전 조건: auth-service 서명 키 Secret.
#
#   ./deploy/k8s/bootstrap-secrets.sh
#
# ─────────────────────────────────────────────────────────────────────────────
# 왜 이게 매니페스트가 아니라 스크립트인가
# ─────────────────────────────────────────────────────────────────────────────
# Phase 18 에서는 `base/kustomization.yaml` 의 secretGenerator 가 `.secrets/` 파일을 읽었다.
# Phase 19(GitOps)에서 그게 불가능해졌다 — Argo CD 는 **Git 을 클론해서 렌더**하므로
# .gitignore 된 개인키를 볼 수 없다. 그리고 Argo CD 는 임의 스크립트를 실행하지 않는다
# (선언만 읽고 명령은 안 읽는 것이 GitOps 의 안전성이다).
#
# 그래서 서명 키는 **배포 대상이 아니라 클러스터가 갖춰야 할 전제**로 취급한다.
# 실무에서도 부트스트랩 비밀은 대개 이 자리에 있다(그다음 단계가 Sealed Secrets / ESO / Vault).
#
# ─────────────────────────────────────────────────────────────────────────────
# 두 가지를 한다
# ─────────────────────────────────────────────────────────────────────────────
#   ① 키 파일이 없으면 만든다 (`deploy/k8s/base/.secrets/`, .gitignore 대상)
#      → 파일로 남겨 두는 이유: **클러스터를 다시 만들어도 같은 키를 쓴다.**
#        (키가 바뀌면 이미 발급된 JWT 가 전부 무효가 된다.)
#   ② 그 파일로 클러스터에 Secret `auth-jwt-key` 를 만든다(멱등).
set -euo pipefail

cd "$(dirname "$0")/../.."          # 리포지토리 루트
SECRETS=deploy/k8s/base/.secrets
NS=shopsaga

# ── ① 키 재료 ────────────────────────────────────────────────────────────────
if [ ! -f "$SECRETS/auth-jwt-key.pem" ] || [ ! -f "$SECRETS/auth-jwt-key-id.txt" ]; then
  echo "▶ auth-service RSA 서명 키 생성 (PKCS#8)"
  mkdir -p "$SECRETS"

  # ⚠️ PKCS#1(`BEGIN RSA PRIVATE KEY`)이 아니라 PKCS#8(`BEGIN PRIVATE KEY`)이어야 한다.
  #    RsaKeyConfig 가 PKCS#8 만 읽는다. `openssl genrsa` 는 PKCS#1 을 내므로 genpkey 를 쓴다.
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$SECRETS/auth-jwt-key.pem" 2>/dev/null

  # ⚠️ macOS 는 `shasum`, 리눅스는 `sha256sum` 이다. 한쪽만 가정하면
  #    "내 노트북에선 되는데 CI 에선 안 되는" 전형적인 함정이 된다(Phase 18 §7-⑨).
  if command -v sha256sum >/dev/null 2>&1; then sha256() { sha256sum; }
  else                                          sha256() { shasum -a 256; }; fi

  # kid 는 공개키 지문에서 파생한다 → 키가 바뀌면 kid 도 반드시 바뀐다(JWKS 캐시가 헷갈리지 않게).
  # ⚠️ printf 로 쓴다. echo 면 끝에 개행이 붙고, 그게 그대로 환경변수 값이 되어 kid 가 어긋난다.
  printf '%s' "shopsaga-$(openssl pkey -in "$SECRETS/auth-jwt-key.pem" -pubout -outform DER 2>/dev/null \
            | sha256 | cut -c1-12)" > "$SECRETS/auth-jwt-key-id.txt"
  echo "   kid = $(cat "$SECRETS/auth-jwt-key-id.txt")"
else
  echo "▶ 키 파일 이미 존재 — 유지 (kid = $(cat "$SECRETS/auth-jwt-key-id.txt"))"
fi

# ── ② 클러스터에 반영 ────────────────────────────────────────────────────────
# 네임스페이스가 먼저 있어야 한다. Argo CD 가 만들기 전에 우리가 필요할 수 있으므로 여기서 보장한다
# (`kubectl apply -k` 로 만들어도 같은 결과 — 멱등이라 두 번 만들어도 문제없다).
kubectl create namespace "$NS" --dry-run=client -o yaml | kubectl apply -f - >/dev/null

# ⚠️ 이미 있으면 **내용을 덮어쓰지 않는다.** 다시 만들면 발급된 토큰이 전부 무효가 되기 때문이다.
#    (키를 정말 갈고 싶다면 Secret 을 지우고 이 스크립트를 다시 돌린 뒤
#     `kubectl rollout restart deploy/auth-service -n shopsaga` 까지 해야 한다 —
#     Secret 에 해시 접미사가 없으므로 파드가 저절로 갈리지 않는다. 한계표 참조.)
if kubectl get secret auth-jwt-key -n "$NS" >/dev/null 2>&1; then
  echo "▶ Secret auth-jwt-key 이미 존재 — 유지"
else
  echo "▶ Secret auth-jwt-key 생성"
  kubectl create secret generic auth-jwt-key -n "$NS" \
    --from-file=private-key="$SECRETS/auth-jwt-key.pem" \
    --from-file=key-id="$SECRETS/auth-jwt-key-id.txt"
fi
