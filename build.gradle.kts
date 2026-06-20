// 루트 빌드 — Phase 0에서는 비워 둔다.
// 서비스가 1개뿐이라 공통 설정 중복이 아직 없다.
// 2번째 서비스가 생기는 Phase 2에서 공통 설정(Boot+Cloud BOM, Java 21 toolchain, 테스트 설정)을
// build-logic/ 컨벤션 플러그인으로 추출한다. ("중복이 아플 때 추출" 원칙 — 계획 §10·Phase 2)
