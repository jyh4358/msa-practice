# Phase 11 — CQRS 읽기 모델 (이벤트로 채우는 조회 전용 저장소)

> **한 줄 요약:** 새 서비스 **order-query-service**가 `OrderPlaced` 이벤트를 구독해
> **조회 화면 모양 그대로 비정규화한 읽기 모델**(MongoDB `order_views`)을 유지한다.
> 쓰기(주문 생성)는 order-service, 읽기(조회)는 이 서비스 — **커맨드와 쿼리가 서로 다른 서비스·다른 DB**가 된다.
> 읽기 모델은 이벤트로부터 만들어지므로 **지웠다가 처음부터 재생(replay)해도 같은 상태**로 복원된다.

초심자(Java/Spring은 알지만 CQRS는 처음) 기준으로 **왜 → 무엇을 → 어떻게** 순서로 설명합니다.

---

## 0. 이번 단계에서 한 일 (요약)

- **order-query-service 신설**(포트 8083, 패키지 `com.shopsaga.orderquery`, 헥사고날) — **조회 전용**(POST/PUT/DELETE 없음).
- **MongoDB 읽기 모델**(`order_views`) — 주문 1건 + 품목 N줄을 **한 문서**에 비정규화 저장(조인 없음).
- **투영기(projector)** — `@KafkaListener(groupId="order-query-service")`가 `OrderPlaced`를 받아 문서를 **upsert**.
- **조회 API** — `GET /order-views?customerId=`(고객 주문 목록), `GET /order-views/{orderId}`(단건). 게이트웨이 라우트 추가.
- **이벤트 계약 확장** — `OrderPlacedEvent`에 `totalAmount`·`occurredAt` 추가(**투영이 스스로 계산·시계를 읽지 않게** = 결정성).
- compose에 **`mongo:8`** + order-query-service 추가(**`--profile async`**), 중앙설정에 `{cipher}` 비번.

> **범위(Phase 11):** 읽기 모델 하나(`OrderPlaced` → `order_views`)를 end-to-end로. 상태 전이
> (`InventoryReserved`/`PaymentCharged` → reserved/charged)는 그 이벤트들이 생기는 **Phase 12**에서 확장한다. "한 Phase 한 개념".

---

## 1. 왜 — 직전(Phase 10)의 문제

지금까지 조회는 **쓰기 모델을 그대로 읽는 것**이었다. `GET /orders/{id}`는 `orders` + `order_items`를 조인해 만든다.
이 구조의 문제는 규모가 커질 때 드러난다:

- **한 모델이 두 일을 겸한다.** 쓰기는 **정규화**(중복 없음·정합성)를 원하고, 읽기는 **비정규화**(한 번에 다 읽기)를 원한다.
  둘을 한 테이블로 만족시키려면 조회마다 조인·집계를 반복해야 한다.
- **읽기 부하가 쓰기를 방해한다.** "내 주문 목록" 같은 조회가 폭증하면 **주문 생성 트랜잭션과 같은 DB**를 경합한다.
- **독립 확장이 안 된다.** 읽기만 10배 늘려야 하는데 쓰기 DB까지 같이 키워야 한다.

**CQRS**(Command Query Responsibility Segregation)는 이 둘을 **분리**한다: 쓰기는 쓰기 모델에, 읽기는 **읽기 전용 모델**에.
그리고 그 읽기 모델을 최신으로 유지하는 방법이 **이벤트 투영**이다 — 이미 Phase 9~10에서 **신뢰성 있는 이벤트 흐름**
(outbox → Kafka → 멱등 소비)을 깔아 뒀기 때문에, 그 위에 소비자를 하나 더 붙이면 된다.

> 💡 **이게 Phase 10 다음인 이유:** 투영이 이벤트를 놓치거나(유실) 중복 반영하면 읽기 모델은 **조용히 틀린 값**을 보여준다.
> 유실/유령이 없는 이벤트 흐름(Phase 10) 위에서만 투영을 신뢰할 수 있다.

---

## 2. 핵심 개념 (용어부터)

- **CQRS:** 커맨드(상태를 바꾸는 요청)와 쿼리(상태를 읽는 요청)의 **책임을 분리**하는 설계. 극단적으로는 **모델·저장소·서비스까지** 분리(이번 단계).
- **쓰기 모델 / 읽기 모델:** 정합성을 위한 정규화 모델 / 조회 편의를 위한 비정규화 모델. 같은 사실의 **두 표현**이다.
- **비정규화(denormalization):** 조회 시 조인하지 않도록 필요한 데이터를 **미리 한곳에 모아 두는 것**(여기선 품목을 주문 문서 안에).
- **투영(projection):** 이벤트 스트림을 읽어 읽기 모델을 만들어 가는 과정. 그 코드를 **투영기(projector)** 라 한다.
- **투영의 결정성(determinism):** 같은 이벤트 스트림을 다시 재생하면 **항상 같은 읽기 모델**이 나오는 성질.
  투영 안에서 `Instant.now()`·`random()`·외부 API 호출을 하면 깨진다.
- **리플레이(replay)로 재구축:** 읽기 모델을 **지우고** 컨슈머 오프셋을 0으로 되돌려 처음부터 다시 투영 → 읽기 모델 복원.
  읽기 모델은 **파생 데이터**이므로 언제든 버릴 수 있다는 것이 CQRS의 큰 장점이다(스키마를 바꾸고 재구축도 가능).
- **결과적 일관성(eventual consistency):** 주문 생성 직후 아주 짧은 순간, 쓰기 측엔 있고 읽기 측엔 **아직 없다**. 잠시 뒤 수렴한다.
- **컨슈머 그룹이 다르면 오프셋도 다르다:** inventory와 order-query가 **같은 토픽을 각자 독립적으로** 처음부터 읽을 수 있는 이유.

---

## 3. 구성 (그림)

```
                    ┌──────────── 쓰기(Command) ────────────┐
  client ─POST /orders─▶ gateway ─▶ order-service ─▶ orderdb (정규화: orders + order_items)
                                        │ outbox → 릴레이 (Phase 10)
                                        ▼
                              ┌─ Kafka: order-placed ─┐  ← 하나의 이벤트, 여러 소비자
                              └────┬─────────────┬────┘
       group=inventory-service ────┘             └──── group=order-query-service
                    ▼                                        ▼
            inventory-service                        order-query-service
          (재고 예약 · 부수효과)                    (투영 · 읽기 모델 유지)
                    │                                        │
              inventorydb                            order-query-mongo
                                                    order_views (비정규화 문서)
                                                             ▲
  client ─GET /order-views?customerId=─▶ gateway ─────────────┘  (조인 없음, 문서 1개 읽기)
```

- **같은 토픽 두 소비자**: 컨슈머 그룹이 다르므로 재고 예약과 읽기 모델 투영이 **서로 독립**(한쪽이 죽어도 다른 쪽은 계속).
- **읽기 DB가 따로**: `order-query-mongo`는 `orderdb`와 완전히 분리 — 조회 폭주가 주문 처리를 건드리지 않는다.
- **실행**: mongo·order-query-service도 **`--profile async`**(Kafka 필요).

---

## 4. 코드·설정 한 부분씩

### 4.1 이벤트 계약 확장 — 결정성의 출발점
```java
public record OrderPlacedEvent(
        UUID orderId, UUID customerId, List<Item> items,
        BigDecimal totalAmount,   // ★ Phase 11 추가
        Instant occurredAt        // ★ Phase 11 추가 (= 주문 생성 시각)
) { public record Item(UUID productId, int quantity, BigDecimal unitPrice) {} }
```
**왜 시각을 이벤트에 담는가:** 투영기가 `Instant.now()`를 쓰면 리플레이할 때마다 값이 달라져 "지우고 재생하면 같은 상태"가
거짓이 된다. **시각도 이벤트가 실어 오는 사실**이어야 한다. 총액도 마찬가지로 발행자가 확정한 값을 그대로 쓴다.

### 4.2 읽기 모델 문서 (MongoDB) — `@Id`가 멱등성의 근거
```java
@Document(collection = "order_views")
class OrderViewDocument {
    @Id private UUID orderId;          // ★ orderId를 _id로 → save()가 "덮어쓰기(upsert)"
    @Indexed private UUID customerId;  // 고객별 조회 인덱스
    private String status;
    private BigDecimal totalAmount;
    private Instant placedAt;
    private List<LineDocument> lines;  // 품목을 문서 안에 중첩(비정규화) → 조인 없음
}
```
`@Id`가 orderId라서 같은 이벤트를 두 번 투영하면 **같은 문서를 같은 값으로 덮어쓴다** = 투영이 자동으로 멱등.
그래서 이 서비스엔 Phase 10 같은 `processed_messages` dedup 테이블이 **필요 없다**
(재고 예약은 "차감"이라 누적 부수효과가 있었지만, 투영은 "현재 모습 덮어쓰기"라서 반복이 무해하다).

### 4.3 투영기 — 이벤트 → 읽기 모델
```java
@UseCase
class OrderViewProjectionService implements ProjectOrderPlacedUseCase {
    public void project(OrderPlacedEvent event) {
        List<OrderView.Line> lines = event.items().stream()
                .map(i -> new OrderView.Line(i.productId(), i.quantity(), i.unitPrice()))  // lineTotal 미리 계산
                .toList();
        repository.save(new OrderView(
                event.orderId(), event.customerId(), "CONFIRMED",
                event.totalAmount(),   // 이벤트 값(재계산 안 함)
                event.occurredAt(),    // ★ 이벤트의 시각(투영 시점의 시계 아님)
                lines));
    }
}
```
`lineTotal`(단가×수량)을 **저장 시점에 미리 계산**해 넣는 것도 읽기 최적화다 — 조회할 때 계산하지 않는다.

### 4.4 소비 — 컨슈머 그룹이 다르다
```java
@KafkaListener(topics = "order-placed", groupId = "order-query-service")   // inventory와 다른 그룹!
void on(OrderPlacedEvent event) { projectOrderPlacedUseCase.project(event); }
```
그룹이 다르므로 **오프셋이 따로** 관리된다 → 재고 소비와 무관하게 이 서비스만 offset 0으로 되돌려 재구축할 수 있다.

### 4.5 조회 API — 쓰기 엔드포인트가 없다
```java
@RestController @RequestMapping("/order-views")
class OrderViewController {
    @GetMapping        List<OrderSummary> byCustomer(@RequestParam UUID customerId) { ... }
    @GetMapping("/{orderId}") OrderSummary byOrderId(@PathVariable UUID orderId) { ... }
}
```
게이트웨이에 라우트 추가(`/order-views/**` → `lb://order-query-service`) — 클라이언트는 여전히 **:8000만** 안다.
`GET /orders/{id}`(쓰기 모델 직접 조회)는 그대로 남아 있어, **두 방식을 비교**해 볼 수 있다.

### 4.6 금액 표현 — Decimal128 명시
```java
@Bean MongoCustomConversions mongoCustomConversions() {
    return MongoCustomConversions.create(a -> a.bigDecimal(BigDecimalRepresentation.DECIMAL128));
}
```
Spring Data MongoDB 4.5의 기본값은 `BigDecimal` → **문자열** 저장이다. 값은 보존되지만 문자열이라
**숫자 비교·범위 쿼리·집계가 사전순으로 어긋난다**("9" > "10"). 읽기 모델은 조회·집계가 본업이므로
MongoDB 고유 10진 타입 `Decimal128`(= `NumberDecimal`)로 저장한다.

### 4.7 설정·인프라
```yaml
# config-repo/order-query-service.yml — 문서형이라 datasource·Flyway가 없다
spring:
  data:
    mongodb:
      host: localhost            # docker 프로파일이 order-query-mongo 로 오버라이드
      database: orderquerydb
      username: orderquery
      password: '{cipher}5c12e254…'      # 중앙 암호화(Phase 6)
      authentication-database: admin      # mongo 루트 사용자는 admin DB에 생성됨
```
```yaml
# compose — mongo:8 (healthcheck는 mongosh! mongo:6+ 는 레거시 셸 제거됨)
order-query-mongo:
  image: mongo:8
  profiles: [async]
  healthcheck:
    test: ["CMD-SHELL", "mongosh --quiet --eval \"db.adminCommand('ping').ok\" || exit 1"]
```

---

## 5. 요청 하나가 흐르는 순서

1. `POST :8000/orders` → order-service가 주문 저장 + **outbox 기록**(한 트랜잭션, Phase 10) → 201 즉시 응답.
2. **릴레이**가 outbox row를 Kafka `order-placed`로 발행(≤1s).
3. **두 소비자가 각각** 받는다: inventory(재고 예약) · **order-query(읽기 모델 투영)** — 서로 독립.
4. `GET :8000/order-views?customerId=…` → **Mongo 문서 하나**를 읽어 품목까지 한 번에 반환(조인 없음).

> ⏱️ 2~3 사이가 **결과적 일관성 창**이다: 1 직후 4를 호출하면 아직 없을 수 있다(404). 잠시 뒤 나타난다.
> 이 "짧은 지연"을 눈으로 보는 것이 이번 단계의 학습 포인트다.

---

## 6. 원리 / 트레이드오프

- **얻은 것:** ① 읽기/쓰기 **독립 확장**(조회 폭주가 주문에 영향 없음), ② 조회가 **조인 0회**(문서 1개), ③ 읽기 모델은 **파생물**이라 언제든 지우고 재구축·스키마 변경 가능, ④ 저장소를 **용도에 맞게** 고를 수 있다(쓰기 RDB / 읽기 문서형).
- **잃은 것(의도적):** ① **결과적 일관성**(쓰기 직후 읽기에 없을 수 있음 — "내 주문이 안 보여요" UX 문제 → 보통 낙관적 UI·폴링·재조회로 완화), ② **중복 저장**(같은 사실이 두 DB에 존재), ③ **운영 복잡도**(서비스·DB·소비자가 늘고, 투영이 밀리면 lag 모니터링 필요).
- **투영은 반드시 결정적이어야 한다:** 그래야 "지우고 offset 0부터 재생 → 동일 상태"가 성립한다. 시계·랜덤·외부 호출을 투영에 넣는 순간 읽기 모델은 **재구축 불가능한 원본 데이터**로 변질된다(= CQRS의 최대 이점 상실).
- **⚠️ Apple Silicon 함정(실제로 걸림):** `mongo:8`은 arm64 이미지를 제공하지만, Docker가 **amd64 변이를 당겨오면** 에뮬레이션으로 돌면서 `MongoDB 5.0+ requires a CPU with AVX support` 경고만 남기고 **mongod가 아예 뜨지 않는다**(컨테이너는 `running`인데 포트가 열리지 않음 → Testcontainers는 "Timed out waiting for log output"으로 실패). 판별법은 `docker image inspect mongo:8 --format '{{.Architecture}}'`(=`arm64`여야 함) 또는 컨테이너 안에서 `uname -m`(=`aarch64`여야 함; `x86_64`면 잘못된 이미지). 해결: `docker rmi mongo:8 && docker pull --platform linux/arm64 mongo:8`. PostgreSQL·Kafka는 이 문제가 없어서 Phase 11에서 처음 만난다.
- **왜 dedup 테이블이 없나:** 투영은 **덮어쓰기(idempotent upsert)** 라서 반복이 무해하다. 반면 Phase 10의 재고 예약은 **누적 차감**이라 `processed_messages`가 필요했다. → **부수효과의 성질**이 멱등 전략을 결정한다.
- **읽기 모델은 여러 개일 수 있다:** 같은 이벤트로 "고객용 주문 목록", "관리자용 통계" 등을 각각 만들면 된다(그룹만 다르게).

---

## 7. 검증 (실증)

- **빌드/테스트:** `BUILD SUCCESSFUL` — 전체 **15개 테스트 통과**(실패 0). 신규 테스트 4종 —
  - **투영 매핑**(이벤트 → 읽기 모델, `occurredAt`을 그대로 사용),
  - **투영 결정성**(같은 이벤트 2회 투영 → 두 결과 완전 동일; 시계를 썼다면 실패하는 가드),
  - **실제 Mongo(Testcontainers) 멱등 upsert**(같은 이벤트 2회 → 문서 **1개**),
  - **비정규화 조회 + Decimal128 왕복**(금액 `39.98` 정확 보존).
- **라이브 스모크(`--profile async`, 15컨테이너):** 주문 전 조회 `[]` → `POST /orders`(201, total 20.00) → 투영 → `GET /order-views/{orderId}` 200.
- **결과적 일관성(실측):** 주문 직후 조회 **404**(“아직 투영되지 않았을 수 있음”) → 1s·2s 후에도 404 → **3s 시점 200**으로 수렴. (outbox 릴레이 폴링 ≤1s + Kafka + 투영이 합쳐진 지연.)
- **결정성의 증거:** 읽기 모델 `placedAt=2026-07-25T14:56:28.033Z` = 주문의 `created_at`(`…28.033473+00`)과 **동일 시각** → 투영이 자기 시계를 읽지 않았음이 데이터로 증명됨.
- **리플레이 재구축(핵심):** 주문 2건 상태를 기준값으로 저장 → Mongo `order_views` **drop**(0건) → 컨슈머 오프셋 **0으로 리셋** → 재기동 → 2건 재투영 →
  기준값과 **완전 일치(`IDENTICAL: True`)** — 금액(`20.00`/`25.50`)·시각(`…28.033Z`/`…40.017Z`)까지 동일.
- **저장 타입 확인(mongosh):** `_id`=UUID(binary), `totalAmount`·`lineTotal`=**Decimal128**(`20.00` 그대로), `placedAt`=BSON Date.
- **독립성:** 같은 토픽 `order-placed`에 두 그룹(`inventory-service`, `order-query-service`)이 **각자 오프셋**으로 등록(둘 다 LAG 0) — 한쪽만 리셋해 재구축해도 재고 소비는 영향 없음.
- **경계:** 게이트웨이 경유 목록 조회 200(최근 주문 먼저 — `14:58:40` → `14:56:28` 내림차순), **토큰 없으면 401**.
- **발견해서 고친 것:** `@Indexed`만으로는 인덱스가 안 생겼다(Spring Data MongoDB 3.0+ 는 자동 인덱스 생성이 **기본 OFF**) → `spring.data.mongodb.auto-index-creation: true` 추가 후 `indexes=["_id_","customerId"]` 확인.

> ⏱️ 참고: `docker stop/start` 로 서비스를 되살린 직후엔 Eureka 재등록 전이라 게이트웨이가 잠시 **503**을 준다(재시도하면 정상 — Phase 9a와 동일한 현상).

> 실행: `docker compose -f deploy/compose/compose.yml --profile async up -d --build`
> 보는 법: kafka-ui `:8090`(그룹별 오프셋/lag) · Swagger `:8083/swagger-ui/index.html` · `mongosh` 로 문서 직접 확인.

---

## 8. 알려진 한계 → 해결 Phase

| 한계 | 설명 | 해결 |
|---|---|---|
| **상태 전이 없음** | 읽기 모델 status가 항상 `CONFIRMED` — 예약/결제 상태 전이가 없다(그 이벤트가 아직 없음) | **Phase 12**(`InventoryReserved`/`PaymentCharged`/취소 이벤트로 전이) |
| **결과적 일관성 UX** | 주문 직후 읽기 모델 404 가능 | 설계상 트레이드오프(완화: 재조회·낙관적 UI). 근본 해결 없음 |
| **투영 lag 관측 없음** | 소비 지연을 대시보드로 보지 않음(kafka-ui 수동 확인) | **Phase 14~15**(consumer lag 메트릭·알림) |
| **투영 실패 처리 없음** | 투영 중 예외 → 재시도 무한(브로커가 재배달) | **Phase 14**(DLQ·백오프) |
| **재구축 절차 수동** | 컬렉션 삭제 + 오프셋 리셋을 손으로 함 | 운영: 재구축 잡/스크립트(후속) |
| **읽기 모델 단일** | 화면별 최적 모델을 더 만들지 않음 | 필요 시 소비자 추가(패턴은 동일) |
| **Mongo 단일 노드·인증 단순** | 학습용(복제셋 아님, 루트 사용자 사용) | 운영: 복제셋·최소권한 사용자, **Phase 15** |
| **시각 정밀도 손실** | 주문 `created_at`은 마이크로초(`…28.033473`)인데 읽기 모델 `placedAt`은 **BSON Date = 밀리초**(`…28.033`)로 절삭 | 결정적 절삭이라 리플레이엔 무해. 정밀도가 중요하면 문자열/Long 저장(후속) |
| **트레이스 분리(Phase 10 상속)** | outbox 릴레이가 별도 스레드라 주문 요청 트레이스와 투영 트레이스가 끊겨 있음 | **Phase 12**(`traceparent` 저장·재주입) |
| **투영 순서 의존** | 파티션 1이라 지금은 순서가 보장되지만, 파티션을 늘리면 같은 주문 이벤트가 순서를 잃을 수 있음(key=orderId로 완화 중) | 운영: key 파티셔닝 유지 + 버전/시퀀스 검사(후속) |

---

## 9. 용어사전

- **CQRS:** 커맨드/쿼리 책임 분리. 이번 단계에선 모델·저장소·서비스까지 분리.
- **쓰기/읽기 모델:** 정합성용 정규화 모델 / 조회용 비정규화 모델.
- **비정규화:** 조인을 없애기 위해 데이터를 미리 한곳에 모아 두기.
- **투영(projection)/투영기(projector):** 이벤트로 읽기 모델을 만드는 과정/코드.
- **결정성(determinism):** 같은 입력(이벤트 스트림)이면 항상 같은 결과. 리플레이의 전제.
- **리플레이 재구축:** 읽기 모델을 지우고 오프셋 0부터 다시 투영해 복원하는 것.
- **upsert:** 있으면 덮어쓰고 없으면 삽입. `@Id`가 있으면 `save()`가 이 동작.
- **Decimal128:** MongoDB의 10진 타입(`NumberDecimal`) — 금액을 오차 없이 저장·비교.
- **컨슈머 그룹:** 오프셋 관리 단위. 그룹이 다르면 같은 토픽을 독립적으로 읽는다.

---

## 10. 참고 / 상호링크

- 직전: [PHASE-10-OUTBOX](PHASE-10-OUTBOX.md)(이 투영이 신뢰할 수 있는 이유 — 유실/유령 없는 이벤트) · [PHASE-9-ASYNC-KAFKA](PHASE-9-ASYNC-KAFKA.md)(토픽·컨슈머 그룹·리플레이 기초)
- 아키텍처: [HEXAGONAL](HEXAGONAL.md)(투영기=인바운드 어댑터, Mongo=아웃바운드 어댑터) · [PHASE-1-MONOLITH](PHASE-1-MONOLITH.md)(원래의 단일 모델 조회와 비교)
- 다음: **Phase 12**(Saga: 보상 + 상태 전이 이벤트 → 읽기 모델 status 확장 + outbox `traceparent` 복원) → 13 오케스트레이션 → 14 복원력
- 로드맵: [`MSA-LEARNING-PLAN.md`](../MSA-LEARNING-PLAN.md)(Phase 11 §302~) · 큰 그림: [REVIEW-PART-A](REVIEW-PART-A.md)

*각 단계의 “알려진 한계 → 해결 Phase”는 [README](../README.md) 인덱스에서 모아 볼 수 있습니다.*
