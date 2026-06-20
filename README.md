# ShopSaga — MSA 핸즈온 학습 플랫폼

Spring Cloud로 마이크로서비스 아키텍처를 단계별로 직접 만들어 보는 학습 프로젝트.
전체 로드맵(18단계)은 **[`MSA-LEARNING-PLAN.md`](./MSA-LEARNING-PLAN.md)**,
설치·실행 상세 가이드(트러블슈팅 포함)는 **[`docs/SETUP.md`](./docs/SETUP.md)**,
서비스 내부 아키텍처(**헥사고날**) 컨벤션은 **[`docs/HEXAGONAL.md`](./docs/HEXAGONAL.md)** 참고.

- 스택: Java 21 LTS · Spring Boot 3.5.15 · Spring Cloud 2025.0.3 (Northfields) · Gradle 8.14
- 빌드: Gradle 멀티모듈 모노레포 · 실행: 로컬 Docker Compose → 이후 Kubernetes
- 도메인: 전자상거래 "ShopSaga" (order → inventory → payment → shipping)

---

## 현재 상태: Phase 1 — 모놀리스 (주문+재고+결제, 단일 트랜잭션 ACID)

`order-service` 하나가 **의도적 모놀리스**입니다 (**헥사고날 아키텍처** — `docs/HEXAGONAL.md`).
주문 생성 + **재고 차감** + **결제 캡처**가 **하나의 `@Transactional`** 안에서 일어나, 한 단계라도 실패하면 전부 롤백됩니다(ACID).
- REST: `POST /orders`, `GET /orders/{id}`, `GET /orders`, `GET /inventory/{productId}`
- 구조: `domain`(Order·OrderItem·StockItem·Payment, 순수) / `application`(port.in·out, service) / `adapter`(in.web, out.persistence)
- 가짜 결제 게이트웨이: 합계가 **`.99`로 끝나면 거절**(→ 402, 트랜잭션 롤백). 재고 부족 → 409.
- PostgreSQL (자기 DB `orderdb`) + **Flyway**(V1 주문, V2 재고·결제+시드). `ddl-auto=validate`.
- ⚠️ 동시성: 재고 차감에 락이 없어 동시 주문 시 oversell 가능(의도적 한계 — 추후 낙관적 락/Saga 단계 주제).
- **API 문서(Swagger)**: `http://localhost:8080/swagger-ui/index.html` (OpenAPI JSON: `/v3/api-docs`) — springdoc, 웹 어댑터에만 적용.

```
.
├── settings.gradle.kts / build.gradle.kts / gradle.properties
├── gradle/libs.versions.toml          # 버전 핀 단일 출처
├── gradlew (+ wrapper)                # ./gradlew 바로 동작
├── services/order-service/            # 첫 서비스
│   ├── build.gradle.kts
│   └── src/main/{java,resources}, src/test/java   # 헥사고날 패키지 구조
└── deploy/compose/compose.infra.yml   # order-db (postgres:18)
```

---

## 사전 준비 (1회)

이 머신엔 **Docker가 없습니다.** 직접 설치하세요(`!` 프리픽스로 이 세션에서 실행 가능):

```bash
# 1) 컨테이너 런타임 (Apple Silicon)
brew install colima docker docker-compose
colima start --arch aarch64 --cpu 4 --memory 8 --disk 60
docker run --rm --platform linux/arm64 alpine uname -m     # -> aarch64

# 2) JDK 21 — 선택. 없으면 Gradle 툴체인이 자동으로 내려받음.
#    brew install --cask temurin@21
```

> `./gradlew` 빌드는 현재 설치된 JDK 24로 실행되고, 컴파일/테스트 타깃만 21로 고정됩니다.

---

## 실행 & 검증 (Phase 0 "동작 증명")

### 1) 단위 테스트 (Docker 불필요)
```bash
./gradlew test
```
도메인 단위 테스트가 통과하면 빌드 체인이 정상입니다.

### 2) DB 띄우고 앱 실행
```bash
# (a) order-db 기동
docker compose -f deploy/compose/compose.infra.yml up -d

# (b) order-service 실행 (Flyway가 부팅 시 스키마 생성)
./gradlew :services:order-service:bootRun
#   → IntelliJ에서 OrderServiceApplication 을 직접 실행해도 됨(권장 개발 루프)
```

### 3) API 호출
```bash
# 주문 생성
curl -s -X POST localhost:8080/orders \
  -H 'Content-Type: application/json' \
  -d '{
        "customerId": "11111111-1111-1111-1111-111111111111",
        "items": [
          {"productId":"22222222-2222-2222-2222-222222222222","quantity":2,"unitPrice":10.00},
          {"productId":"33333333-3333-3333-3333-333333333333","quantity":1,"unitPrice":5.50}
        ]
      }'
# → 201, totalAmount: 25.50, status: PENDING, 생성된 id 반환

# 조회
curl -s localhost:8080/orders | jq
curl -s localhost:8080/orders/<id> | jq

# Flyway 적용 이력
curl -s localhost:8080/actuator/flyway | jq
```

### 4) ACID 시연 (Phase 1 핵심)
```bash
P2=22222222-2222-2222-2222-222222222222
curl -s localhost:8080/inventory/$P2        # 재고 확인 (시드 100)

# (A) 해피패스: 합계 20.00 → 201 CONFIRMED + payment, 재고 -=2
curl -s -X POST localhost:8080/orders -H 'Content-Type: application/json' \
  -d "{\"customerId\":\"11111111-1111-1111-1111-111111111111\",\"items\":[{\"productId\":\"$P2\",\"quantity\":2,\"unitPrice\":10.00}]}"

# (B) 결제 거절: 합계 9.99 → 402, 재고는 그대로(차감이 롤백됨) ← ACID 증거
curl -s -o /dev/null -w '%{http_code}\n' -X POST localhost:8080/orders -H 'Content-Type: application/json' \
  -d "{\"customerId\":\"11111111-1111-1111-1111-111111111111\",\"items\":[{\"productId\":\"$P2\",\"quantity\":1,\"unitPrice\":9.99}]}"
curl -s localhost:8080/inventory/$P2        # (A) 이후 값에서 변동 없음

# (C) 재고 부족: 9999개 → 409 (아무것도 저장 안 됨)
```

**Phase 1 완료 기준:** 해피패스 201·CONFIRMED·payment / 결제거절 402 **+ 재고 원복** / 재고부족 409 / 거절·부족 주문은 DB에 흔적 없음(완전 롤백).

---

## 다음: Phase 2
- `payment-service`를 별도 모듈·별도 DB·별도 프로세스로 분리 → 동기 REST 호출. **단일 트랜잭션 소멸**을 체감(결제 실패가 재고를 자동 원복 못 함 → Phase 12 Saga의 동기).
- 공통 빌드 설정을 `build-logic/` 컨벤션 플러그인으로 추출. 새 서비스도 헥사고날(`docs/HEXAGONAL.md`).

자세한 단계는 [`MSA-LEARNING-PLAN.md`](./MSA-LEARNING-PLAN.md) §5 참고.
