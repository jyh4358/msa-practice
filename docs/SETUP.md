# SETUP — 설치부터 Phase 0 실행·검증까지 (Apple Silicon)

이 문서는 **빈 머신에서 ShopSaga `order-service`(Phase 0)를 실제로 띄워 API가 동작하는 것을 확인**하기까지의 전 과정을 정리한다.
실제로 이 환경(macOS / Apple Silicon arm64 / 18GB / JDK 24·Node 24 설치, Docker 미설치)에서 **검증 완료**했고, 도중에 만난 함정과 해결책을 §8 트러블슈팅에 모았다.

검증 시점 설치 버전: Colima 0.10.3 · Docker 29.6.0(client)/29.5.2(server) · Compose 5.1.4 · `postgres:18-alpine` · Gradle 8.14 · Spring Boot 3.5.15 · Java 21 toolchain(자동 프로비저닝, 빌드 실행은 JDK 24).

---

## 0. 사전 체크

```bash
uname -m          # arm64 여야 함
brew --version    # Homebrew 필요 (없으면 https://brew.sh 에서 먼저 설치)
java -version     # 24 (없어도 됨 — Gradle 툴체인이 21을 자동 프로비저닝)
```

---

## 1. 컨테이너 런타임 설치 (Colima)

Docker Desktop 대신 **Colima**를 쓴다(무료·경량·CLI, 18GB 머신에 적합, sudo 불필요).

```bash
brew install colima docker docker-compose
```
- `colima` : Lima/Virtualization.framework 기반 Linux VM 매니저
- `docker` : Docker CLI (서버는 VM 안)
- `docker-compose` : Compose v2 (standalone)

설치 후 `docker compose`(하위 명령) 인식을 위해 CLI 플러그인을 연결한다(한 번만):
```bash
mkdir -p ~/.docker/cli-plugins
ln -sf /opt/homebrew/lib/docker/cli-plugins/docker-compose ~/.docker/cli-plugins/docker-compose
docker compose version    # Docker Compose version 5.x 확인
```

---

## 2. VM 기동

```bash
colima start --arch aarch64 --cpu 4 --memory 8 --disk 60
```
- 첫 기동은 VM 이미지를 받아 1~3분 걸린다.
- `--arch aarch64` 로 **arm64 네이티브** 보장(에뮬레이션 회피).

검증:
```bash
colima status                                          # "running ... arch: aarch64"
docker run --rm --platform linux/arm64 alpine uname -m # -> aarch64
```

> **VM 관리**
> - `colima stop` : VM 정지(8GB RAM 즉시 회수). 개발 안 할 때 권장.
> - `colima start` : 재기동(이미지 캐시됨, 빠름).
> - `colima delete` : VM·디스크 완전 삭제.

---

## 3. JDK 21 (선택)

이 프로젝트는 **Java 21**을 타깃한다. 빌드 자체는 설치된 JDK 24로 돌아가고, Gradle 툴체인이 컴파일/테스트만 21로 고정한다.
로컬에 JDK 21이 없으면 **foojay resolver가 자동으로 내려받으므로 아무것도 안 해도 된다.** 직접 깔고 싶으면:
```bash
brew install --cask temurin@21      # 또는 SDKMAN: sdk install java 21-tem
```

---

## 4. 빌드 & 단위 테스트 (Docker 불필요)

레포 루트(`/Users/younho/IdeaProjects/msa`)에서:
```bash
./gradlew :services:order-service:test
```
- 첫 실행은 Gradle 8.14 배포본 + (필요시)JDK 21 + 의존성을 받아 수 분 걸린다.
- `BUILD SUCCESSFUL` + 도메인 단위 테스트(`OrderTest`) 통과를 확인한다.

---

## 5. DB 기동 (PostgreSQL)

`order-service`는 자기 DB(`orderdb`)를 가진다(database-per-service).
```bash
docker compose -f deploy/compose/compose.infra.yml up -d
```
healthy 대기 + 확인:
```bash
docker compose -f deploy/compose/compose.infra.yml ps        # STATUS: Up (healthy)
docker exec shopsaga-infra-order-db-1 pg_isready -U order -d orderdb
```

> ⚠️ **PostgreSQL 18 마운트 주의 (실제로 겪은 함정 — §8.3):**
> PG18부터 데이터 디렉터리가 버전별 하위 경로(`/var/lib/postgresql/18/docker`)로 바뀌었다.
> 볼륨을 옛 규칙 `/var/lib/postgresql/data`에 마운트하면 컨테이너가 **즉시 종료**된다.
> 그래서 `compose.infra.yml`은 `order-db-data:/var/lib/postgresql`(상위 경로)에 마운트한다.

---

## 6. 앱 실행

### 방법 A — CLI (Flyway가 부팅 시 스키마 생성)
```bash
./gradlew :services:order-service:bootRun
```
`Started OrderServiceApplication` 로그가 뜨면 `:8080` 준비 완료.

### 방법 B — IntelliJ (권장 개발 루프)
`OrderServiceApplication` 의 main을 실행(또는 디버그). 핫 리로드·디버거가 편하다.
DB(§5)는 컨테이너로 띄워 두고 앱만 IDE에서 돌리는 게 표준 개발 방식(계획 §10 하이브리드).

준비 확인:
```bash
curl -s localhost:8080/actuator/health      # {"status":"UP", ...}
```

---

## 7. API 검증 (Phase 0 "동작 증명")

```bash
# 1) 주문 생성 → 201
curl -s -X POST localhost:8080/orders -H 'Content-Type: application/json' -d '{
  "customerId":"11111111-1111-1111-1111-111111111111",
  "items":[
    {"productId":"22222222-2222-2222-2222-222222222222","quantity":2,"unitPrice":10.00},
    {"productId":"33333333-3333-3333-3333-333333333333","quantity":1,"unitPrice":5.50}
  ]}'
# -> {"id":"<UUID>","status":"PENDING","totalAmount":25.50,"items":[...]}

# 2) 단건 조회 (위 응답의 id 사용) → 200
curl -s localhost:8080/orders/<UUID>

# 3) 목록 조회 → 200
curl -s localhost:8080/orders

# 4) 없는 주문 → 404
curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/orders/00000000-0000-0000-0000-000000000000

# 5) 잘못된 요청(items 비어 있음) → 400
curl -s -o /dev/null -w '%{http_code}\n' -X POST localhost:8080/orders \
  -H 'Content-Type: application/json' -d '{"customerId":"11111111-1111-1111-1111-111111111111","items":[]}'

# 6) Flyway 적용 이력
curl -s localhost:8080/actuator/flyway | python3 -m json.tool
```

### DB에서 직접 확인
```bash
docker exec shopsaga-infra-order-db-1 psql -U order -d orderdb \
  -c "SELECT id, status, total_amount FROM orders;" \
  -c "SELECT order_id, product_id, quantity, unit_price FROM order_items;" \
  -c "SELECT version, description, success FROM flyway_schema_history;"
```

### 스키마 재현성 (스키마는 코드)
```bash
docker compose -f deploy/compose/compose.infra.yml down -v   # 볼륨까지 삭제
docker compose -f deploy/compose/compose.infra.yml up -d     # 재기동
# 앱 재시작 → Flyway가 V1__init.sql 을 다시 적용해 스키마를 재생성한다.
```

**Phase 0 완료 기준:** 위 1~6 통과 + DB에 row + `down -v` 후에도 스키마 자동 재생성.

---

## 8. 트러블슈팅 (이번에 실제로 겪고 해결한 것들)

### 8.1 `no repositories are defined`
`./gradlew test`가 의존성을 못 받고 실패.
**원인:** 의존성 저장소 미선언. **해결:** `settings.gradle.kts`에 중앙 선언.
```kotlin
dependencyResolutionManagement {
    repositories { mavenCentral() }
}
```

### 8.2 `Configuration cache state could not be cached … DefaultLegacyConfiguration`
**원인:** `io.spring.dependency-management` 플러그인이 Gradle configuration-cache와 비호환.
**해결:** `gradle.properties`에서 `org.gradle.configuration-cache`를 끔(빌드 캐시·병렬은 유지).

### 8.3 postgres:18 컨테이너가 시작 직후 Exit(1)
로그: `in 18+, these Docker images are configured to store database data in a … major-version-specific directory … there appears to be PostgreSQL data in /var/lib/postgresql/data (unused mount/volume)`.
**원인:** PG18부터 데이터가 `/var/lib/postgresql/<버전>/docker`로 이동. 옛 `/data` 마운트는 "unused"로 간주되어 부팅 거부.
**해결:** 볼륨을 `/var/lib/postgresql`(상위)에 마운트. 기존 볼륨은 `down -v`로 제거 후 재생성.

### 8.4 `GET /orders`·`GET /orders/{id}` 가 500 (POST는 정상)
스택: `LazyInitializationException` — `Order.items`(LAZY) 직렬화 시점에 세션이 닫혀 있음.
**원인:** `open-in-view: false`라서, 서비스 트랜잭션이 끝난 뒤 컨트롤러에서 엔티티의 LAZY 컬렉션을 직렬화하려다 실패. (POST는 엔티티를 메모리에서 만들어 items가 이미 로드돼 통과.)
**해결:** 엔티티→DTO 변환을 `@Transactional` 서비스 메서드 **안**에서 수행(세션 열린 동안). 엔티티를 웹 계층으로 노출하지 않는 이점도 있음.
> 다른 선택지: `@EntityGraph(attributePaths = "items")`로 즉시 로딩, 또는 `open-in-view: true`(비권장 — 지연 로딩이 뷰까지 새어 N+1·커넥션 점유 유발).

### 8.5 Gradle이 JDK 24에서 도는데 Java 21 타깃?
Gradle 8.14는 JDK 24 실행을 지원한다. 툴체인(`languageVersion = 21`)이 컴파일/테스트만 21로 고정하며, 로컬에 JDK 21이 없으면 foojay resolver가 자동으로 내려받는다.

---

## 9. 정지 / 정리

```bash
# 앱 정지 (CLI로 bootRun 한 경우 포그라운드면 Ctrl+C; 백그라운드면:)
lsof -ti tcp:8080 | xargs kill

# DB 정지(데이터 유지)
docker compose -f deploy/compose/compose.infra.yml stop
# DB 정지 + 데이터 삭제
docker compose -f deploy/compose/compose.infra.yml down -v

# VM 정지(RAM 회수)
colima stop
```

---

## 10. 다음 단계
- **Phase 1:** payment·inventory 로직을 order-service 안에 넣어 모놀리스 + 단일 트랜잭션 ACID 체험.
- **Phase 2:** `payment-service` 분리(동기 REST), 공통 빌드 설정을 `build-logic/`로 추출.

전체 로드맵: [`../MSA-LEARNING-PLAN.md`](../MSA-LEARNING-PLAN.md)
