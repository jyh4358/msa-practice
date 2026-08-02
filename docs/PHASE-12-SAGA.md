# Phase 12 — Saga: 코레오그래피 + 보상 (분산 트랜잭션 없이 일관성)

> **한 줄 요약:** 주문·재고·결제가 **서로 명령하지 않고** 각자 "무슨 일이 일어났는지"만 발행하며
> 이벤트 릴레이로 협력한다. 실패하면 **보상(compensation)** 으로 되돌린다 —
> 결제가 거절되면 재고가 스스로 예약을 풀고, 주문은 CANCELLED 가 된다.
> 그리고 outbox에 `traceparent`를 실어 **Saga 전체가 하나의 트레이스**로 보이게 만든다.

초심자(Java/Spring은 알지만 분산 트랜잭션은 처음) 기준으로 **왜 → 무엇을 → 어떻게** 순서로 설명합니다.

---

## 0. 이번 단계에서 한 일 (요약)

- **동기 결제 호출 제거** — order가 payment를 REST로 부르지 않는다(`PaymentGatewayPort`·RestClient 어댑터 삭제).
- **주문이 상태 기계가 됨** — `PENDING → INVENTORY_RESERVED → CONFIRMED | CANCELLED`, 모든 전이가 **멱등**.
- **이벤트 5종 추가** — `InventoryReserved`/`InventoryFailed`/`InventoryReleased`(보상)/`PaymentCharged`/`PaymentDeclined`
  \+ 주문 종료 사실 `OrderConfirmed`/`OrderCancelled`.
- **payment가 Saga 참여자로 전환** — 아무도 호출하지 않고 `InventoryReserved`를 **스스로 듣고** 청구한다.
- **보상 구현** — 결제 거절 시 inventory가 **예약 원장**(`stock_reservations`)을 보고 재고를 되돌린다.
- **outbox·inbox를 공유 라이브러리로 승격**(`shared/outbox`) — 3개 서비스가 같은 메커니즘을 쓰게 됐으므로.
- **트레이스 복원** — outbox row에 `traceparent`를 저장했다가 릴레이가 **복원해서** 발행 → Phase 10·11의 "끊긴 트레이스" 해결.
- **읽기 모델 상태 전이** — Phase 11의 투영이 Saga 전이를 따라가며, 순서가 뒤바뀌어도 **단조롭게만** 전진.

> **범위:** 로드맵의 Phase 12(코레오그래피). 같은 Saga를 **중앙 조정자**로 다시 구현하는 것은 Phase 13.

---

## 1. 왜 — 직전(Phase 11)의 문제

Phase 2에서 order→payment를 REST로 나눈 뒤, 우리는 계속 **깨진 정합성**을 안고 있었다:

- **재고 부족인데 주문은 성공했다.** Phase 9에서 재고를 비동기로 넘긴 뒤, inventory는 부족하면 **로그만** 남겼다.
  주문은 CONFIRMED 로 남아 있었다 — 아무도 그 주문을 취소해 주지 않았다.
- **결제 거절도 마찬가지.** 결제가 동기였을 땐 402를 반환할 수 있었지만, 그건 **재고를 이미 잡은 뒤**였다.
  거절되면 그 재고는 누가 풀어 주나? 아무도 풀지 않았다.
- **글로벌 트랜잭션은 답이 아니다.** 서비스마다 DB가 다르니 `@Transactional` 하나로 묶을 수 없다.
  2단계 커밋(2PC)은 가능하지만 가용성·성능·운영 복잡도 때문에 MSA에서 사실상 쓰지 않는다.

**Saga**는 이 문제를 다르게 푼다: 하나의 큰 트랜잭션 대신 **여러 개의 로컬 트랜잭션을 이어 붙이고**,
중간에 실패하면 **이미 커밋된 것을 되돌리는 업무 행위**(보상)를 실행한다.

---

## 2. 핵심 개념 (용어부터)

- **Saga:** 여러 서비스에 걸친 업무를 **로컬 트랜잭션의 연쇄**로 수행하는 패턴. 각 단계는 자기 DB에서 커밋된다.
- **코레오그래피(choreography):** 중앙 조정자가 **없고**, 각 서비스가 남의 이벤트를 듣고 **스스로** 다음 일을 한다(이번 Phase).
  ↔ **오케스트레이션(orchestration):** 중앙 조정자가 "이제 이거 해"라고 지시(Phase 13).
- **보상(compensation) = semantic undo:** DB 롤백이 **아니다**. 이미 커밋된 효과를 되돌리는 **새로운 업무 행위**
  (예: 예약 취소, 환불). 그래서 보상은 도메인 연산이며, **실패할 수도 있고**, 순서가 중요하다.
- **짧은 보상 / 긴 보상:** 되돌릴 것이 없는 실패(재고 부족 — 아직 결제 안 함) / 여러 단계를 되돌려야 하는 실패(결제 거절 — 재고를 풀어야 함).
- **이벤트 vs 명령:** "재고가 예약됐다"(사실) ↔ "재고를 예약해라"(지시). 코레오그래피는 **사실만** 주고받는다 —
  발행자는 누가 듣는지 모른다(느슨한 결합).
- **상태 기계(state machine):** 주문이 이벤트에 따라 정해진 경로로만 전이하는 구조. 잘못된 전이를 도메인이 거부한다.
- **W3C `traceparent`:** 트레이스 컨텍스트를 담는 표준 헤더(`00-<traceId>-<spanId>-<flags>`).
  outbox에 저장해 두면 **나중에 다른 스레드에서** 트레이스를 이어붙일 수 있다.

---

## 3. 구성 (그림)

```
  ① POST /orders                      ┌──────────── order-service ────────────┐
  client ─────────▶ gateway ─────────▶│ 주문 PENDING 저장 + outbox(OrderPlaced)│  ← 한 커밋(원자적)
                       ▲              └───────────────────┬───────────────────┘
                       │ 201 (PENDING, 즉시)               │ 릴레이(traceparent 복원)
                       │                                   ▼
                       │                        ┌── order-events ──┐
                       │                        └────────┬─────────┘
                       │                                 ▼
                       │              ┌───────── inventory-service ─────────┐
                       │              │ ② 재고 예약 + 원장 기록              │
                       │              │   성공 → InventoryReserved          │
                       │              │   실패 → InventoryFailed  ─────┐    │
                       │              └──────────────┬─────────────────│────┘
                       │                             ▼                 │
                       │                  ┌── inventory-events ──┐     │
                       │                  └──────┬───────────┬───┘     │
                       │                         ▼           ▼         │
                       │        ┌── payment-service ──┐   (order: 상태 INVENTORY_RESERVED)
                       │        │ ③ 결제 청구          │              │
                       │        │  성공 → PaymentCharged                │
                       │        │  거절 → PaymentDeclined ──┐           │
                       │        └───────────┬──────────────│───────────│
                       │                    ▼              ▼           │
                       │           ┌── payment-events ──────────┐       │
                       │           └──┬──────────────────┬──────┘       │
                       │              ▼                  ▼             ▼
                       │   order: CONFIRMED    inventory: 재고 해제   order: CANCELLED
                       │        (Saga 성공)      (보상·InventoryReleased)   (Saga 실패)
                       │              │                                  │
                       └──────────────┴──── order-query-service (읽기 모델 상태 전이) ──┘
```

- **아무도 서로를 호출하지 않는다.** 세 서비스는 토픽만 공유하고, 각자 자기 DB에서 커밋한다.
- **두 실패 경로**: `InventoryFailed`(짧은 보상 — 주문만 취소) / `PaymentDeclined`(긴 보상 — 재고 해제 + 주문 취소).
- **모든 발행은 outbox 경유**(Phase 10) → 상태 변경과 이벤트가 원자적.

---

## 4. 코드·설정 한 부분씩

### 4.1 주문 = 상태 기계 (전이는 모두 멱등)
```java
public boolean markInventoryReserved() {
    if (status != OrderStatus.PENDING) return false;      // 재배달·순서뒤바뀜·이미취소 → 무시
    this.status = OrderStatus.INVENTORY_RESERVED;
    return true;
}
public boolean confirm(UUID paymentId) {
    if (paymentId == null) return false;                                          // 결제 id 없는 지시는 무시
    if (status != OrderStatus.PENDING && status != OrderStatus.INVENTORY_RESERVED)
        return false;                                                             // CONFIRMED(재배달)/CANCELLED(경합) → 유지
    this.paymentId = paymentId; this.status = OrderStatus.CONFIRMED; return true;
}
public boolean cancel() {
    if (status == OrderStatus.CANCELLED || status == OrderStatus.CONFIRMED) return false;
    this.status = OrderStatus.CANCELLED; return true;
}
```
**핵심: 예외를 던지지 않고 `false`를 반환한다.** at-least-once 배달에서 재배달은 **정상**이므로,
도메인이 "이번엔 할 일이 없다"고 조용히 답해야 한다. 예외로 처리하면 소비자가 무한 재시도에 빠진다.

> ⚠️ **`confirm`이 `PENDING`에서도 확정한다(단조 전이) — 감사 2026-08-02.** 처음 이 Phase를 만들 때는
> `INVENTORY_RESERVED`에서만 확정을 허용했다. 그런데 코레오그래피에서는 `InventoryReserved`와 `PaymentCharged`를
> **서로 다른 리스너 컨테이너**가 받으므로 처리 순서가 보장되지 않는다 — `PaymentCharged`가 먼저 도착하면
> 그 시점의 주문은 아직 `PENDING`이라 확정 지시가 조용히 무시되고, 주문은 "결제는 됐는데 영영 미확정"으로 남았다.
> 지금은 §4.9의 읽기 모델(`OrderViewStatus.rank`)과 같은 원리로 **쓰기 모델도 단조 전이**로 방어한다 — 뒤 단계
> 사건(`PaymentCharged`)은 앞 단계를 건너뛰어 도착해도 받아들이고, 늦게 온 `InventoryReserved`는
> `markInventoryReserved`가 (이미 `PENDING`이 아니므로) 무시한다.

### 4.2 order의 Saga 반응 — 공통 골격
```java
private void handle(UUID messageId, UUID orderId, String eventName,
                    Predicate<Order> transition, Consumer<Order> onTransitioned) {
    if (processedMessagePort.isAlreadyProcessed(messageId)) return;     // ① 멱등 가드
    Order order = loadOrderPort.loadById(orderId).orElse(null);
    if (order == null) { processedMessagePort.markProcessed(messageId); return; }  // 모르는 주문 → 재시도 무의미
    if (transition.test(order)) {                                       // ② 도메인이 전이 판단
        updateOrderPort.update(order);                                  // ③ 상태 저장
        onTransitioned.accept(order);                                   //    + 결과 이벤트를 outbox 에
    }
    processedMessagePort.markProcessed(messageId);                       // ④ 처리 기록
}
```
②~④가 **한 트랜잭션**이라 "상태는 바뀌었는데 이벤트가 안 나감"이 불가능하다.

### 4.3 inventory: 실패도 **사실로 발행**해야 한다 (Phase 9의 구멍)
```java
// StockService (조합) — 트랜잭션 2개로 나뉜다
try {
    transactions.reserve(messageId, event, quantityByProduct);   // 예약+원장+Reserved발행+처리기록 = 한 커밋
} catch (InsufficientStockException | StockNotFoundException e) {
    transactions.recordFailure(messageId, event, e.getMessage()); // 새 트랜잭션: Failed발행+처리기록
}
```
**왜 트랜잭션을 나눴나:** 예약이 중간에 실패하면 **이미 차감된 일부 품목까지 롤백**해야 한다.
그런데 롤백하면 같은 트랜잭션에 쓴 outbox row도 사라진다 → "실패했다"는 사실을 알릴 수 없다.
그래서 **롤백은 롤백대로, 실패 발행은 새 트랜잭션으로** 분리한다.
(같은 클래스 안에서 호출하면 프록시를 우회해 `@Transactional`이 안 걸리므로 빈을 분리했다.)

### 4.4 보상: 무엇을 되돌릴지 **스스로 기억**해야 한다
```sql
CREATE TABLE stock_reservations (            -- 예약 원장
    order_id UUID, product_id UUID, quantity INTEGER,
    PRIMARY KEY (order_id, product_id)
);
```
`PaymentDeclined` 이벤트에는 **품목 정보가 없다**(주문 id와 금액만). 다른 서비스 DB를 조회할 수도 없다.
그래서 inventory는 예약할 때 무엇을 잡았는지 자기 DB에 남긴다. 해제 시 **꺼내며 삭제**하므로
두 번 보상해도 두 번째는 되돌릴 게 없다(자연 멱등).

```java
// 도메인의 보상 연산 — 롤백이 아니라 "다시 더한다"는 새로운 업무 행위
public void release(int quantity) { this.availableQuantity += quantity; }
```

### 4.5 payment: 거절은 **예외가 아니라 결과**
```java
try {
    Payment captured = savePaymentPort.save(Payment.capture(orderId, amount));
    publish.paymentCharged(...);
} catch (PaymentDeclinedException e) {
    publish.paymentDeclined(...);   // 사실로 발행하고 정상 종료 → 재시도 루프에 빠지지 않는다
}
processedMessagePort.markProcessed(messageId);   // 거절도 "처리 완료"
```
HTTP였다면 402를 반환했겠지만, 이벤트 흐름에서 예외를 던지면 브로커가 **영원히 재배달**한다.
업무적 실패는 **성공적으로 처리된 실패 이벤트**가 되어야 한다.

### 4.6 ★ 트레이스 복원 — Phase 10·11의 숙제
```java
// 기록 시점(요청 스레드): 현재 컨텍스트를 W3C traceparent 로 캡처해 outbox row 에 함께 저장
propagator.inject(tracer.currentSpan().context(), carrier, Map::put);
String traceparent = carrier.get("traceparent");   // "00-<32hex>-<16hex>-01"

// 발행 시점(@Scheduled 스레드): 저장된 컨텍스트를 원격 부모로 복원한 스코프 안에서 send
Span span = propagator.extract(Map.of("traceparent", traceparent), Map::get)
                      .name("outbox-relay").start();
try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
    kafkaTemplate.send(message).get(5, TimeUnit.SECONDS);
} finally { span.end(); }
```
릴레이는 요청 스레드가 아니어서 트레이스 컨텍스트가 **없다**. 그대로 보내면 새 트레이스가 시작되어
"주문 요청"과 "Saga의 나머지"가 **끊긴 두 트레이스**로 보인다(계측이 망가진 것처럼 오해하게 된다).
컨텍스트를 복원하면 observation 계측이 만드는 producer 스팬이 원래 트레이스의 자식이 되고,
Kafka 헤더에도 같은 traceId가 실려 **Saga 전체가 한 트레이스**가 된다.

### 4.7 outbox·inbox를 공유 라이브러리로 (`shared/outbox`)
3개 서비스가 같은 메커니즘을 필요로 하게 되어 코드를 한곳으로 모았다.
**⚠️ 공유 라이브러리 ≠ 공유 데이터베이스:** 테이블은 여전히 **서비스별 자기 DB**에 있고(Flyway도 서비스별),
공유되는 것은 "그 테이블을 어떻게 쓰는가"라는 기술 코드뿐이다. 도메인 데이터·업무 규칙은 절대 넣지 않는다.

각 서비스는 이렇게 켠다(스캔 범위를 명시해야 하는 이유는 §6):
```java
@SpringBootApplication
@EntityScan({"com.shopsaga.order", "com.shopsaga.outbox"})
@EnableJpaRepositories({"com.shopsaga.order", "com.shopsaga.outbox"})
@Import(OutboxConfiguration.class)
```

### 4.8 한 토픽에 여러 이벤트 타입 — `@KafkaHandler` 분기
```java
@Component
@KafkaListener(topics = Topics.INVENTORY_EVENTS, groupId = "order-service")
class InventoryEventListener {
    @KafkaHandler void onReserved(InventoryReservedEvent e, @Header("messageId") String id) { ... }
    @KafkaHandler void onFailed(InventoryFailedEvent e, @Header("messageId") String id) { ... }
    @KafkaHandler(isDefault = true) void onUnknown(Object e) { /* 관심 없는 타입 무시 */ }
}
```
토픽은 **발행 서비스 단위**로 묶는다(`order-events`·`inventory-events`·`payment-events`).
분기는 JSON 직렬화 시 붙는 타입 헤더(`__TypeId__`)로 spring-kafka가 처리한다.
`isDefault` 핸들러가 있어야 **발행자가 새 이벤트를 추가해도 소비자가 깨지지 않는다**.

### 4.9 읽기 모델: 단조 전이 (순서 뒤바뀜 방어)
```java
public enum OrderViewStatus {
    PENDING(0), INVENTORY_RESERVED(1), CONFIRMED(2), CANCELLED(2);   // rank
}
// 현재 상태가 "더 앞선 것"일 때만 갱신 — 조건과 갱신을 DB 한 연산으로(원자적)
mongoTemplate.updateFirst(
    query(where("_id").is(orderId).and("status").in(overwritable)),
    new Update().set("status", newStatus), OrderViewDocument.class);
```
읽기 모델은 **세 토픽을 각각 다른 스레드**로 소비하므로 도착 순서가 보장되지 않는다.
순진하게 덮어쓰면 `CONFIRMED` → `INVENTORY_RESERVED` 로 **거꾸로** 갈 수 있다.
상태에 순위를 주고 **전진만** 허용하면 도착 순서와 무관하게 최종 상태가 같아진다(리플레이 결정성도 유지).
같은 이유로 `OrderPlaced` 재배달이 상태를 되돌리지 않도록, 본문은 `$set`·상태는 `$setOnInsert` 로 나눴다.

> **쓰기 모델(`Order.confirm`)도 같은 원리 적용(감사 2026-08-02).** 여기서는 순위를 Mongo 쿼리 조건으로 걸었지만,
> §4.1의 `confirm`은 같은 아이디어를 도메인 메서드 안의 상태 체크로 구현한다 — 접근 방식은 다르지만
> "뒤 단계 사건은 앞 단계를 건너뛰어도 받아들이고, 앞 단계 사건이 늦게 오면 무시한다"는 원리는 동일하다.

---

## 5. 요청 하나가 흐르는 순서 (해피패스)

1. `POST :8000/orders` → order가 주문 **PENDING** 저장 + outbox(OrderPlaced) **한 커밋** → **201 즉시 반환**.
2. 릴레이(≤1s)가 traceparent를 복원해 `order-events` 로 발행.
3. inventory가 소비 → 재고 차감 + 원장 기록 + `InventoryReserved` 발행(한 커밋).
4. order가 `InventoryReserved` 를 듣고 **INVENTORY_RESERVED** 로 전이. **동시에** payment도 같은 이벤트를 듣고 결제 청구.
5. payment가 `PaymentCharged` 발행 → order가 **CONFIRMED** + `OrderConfirmed` 발행.
6. order-query가 전 과정을 보며 읽기 모델 상태를 따라 전이시킨다.

**실패 경로 A(짧은 보상):** 3에서 재고 부족 → `InventoryFailed` → order **CANCELLED**(되돌릴 것 없음).
**실패 경로 B(긴 보상):** 5에서 거절 → `PaymentDeclined` → **inventory가 재고 해제**(`InventoryReleased`) + order **CANCELLED**.

> 주문 응답(201)은 **아무것도 기다리지 않는다.** 최종 결과는 조회로 확인한다(결과적 일관성).

---

## 6. 원리 / 트레이드오프

- **얻은 것:** ① 정합성 구멍 제거(모든 실패에 대응하는 보상이 있다), ② 서비스 간 **완전한 시간적 디커플링**
  (payment가 죽어도 주문은 접수되고, 살아나면 이어서 진행), ③ 2PC 없이 일관성, ④ Saga 전체가 **한 트레이스**로 관측 가능.
- **잃은 것(의도적):**
  - **원자성의 착각이 사라진다.** 중간 상태(`INVENTORY_RESERVED`)가 **외부에 보인다** — 사용자는 "결제 중"을 본다.
  - **HTTP로 실패를 알릴 수 없다.** 402/409가 사라졌다 → 클라이언트는 **조회(폴링/푸시)** 로 결과를 확인해야 한다.
  - **흐름이 코드에 없다.** 전체 순서를 보려면 여러 서비스의 리스너를 읽어야 한다 —
    이것이 코레오그래피의 최대 단점이고, **Phase 13 오케스트레이션**의 동기다.
  - **보상은 완벽하지 않다.** 확정된 주문은 취소하지 않는다(환불이라는 다른 보상이 필요 — 범위 밖).
- **왜 예외 대신 `false`/이벤트인가:** at-least-once 배달에서 재배달·순서뒤바뀜은 **정상 상황**이다.
  이를 예외로 다루면 무한 재시도가 되고, 브로커가 같은 메시지를 계속 되돌려준다.
  (`confirm`이 `PENDING`도 허용하는 단조 전이로 바뀐 것도 같은 맥락 — 순서뒤바뀜을 예외가 아니라
  정상 입력으로 다룬다. 감사 2026-08-02, §4.9 참고.)
- **`@EntityScan` 함정(실제로 걸림):** 공유 라이브러리의 `@Configuration` 에 `@EntityScan` 을 넣으면
  Boot의 기본 스캔(앱 패키지)을 **대체**해 버려 그 서비스 자신의 엔티티가 사라진다.
  그래서 스캔 범위는 각 서비스가 **자기 패키지 + outbox** 를 함께 명시한다.
- **트레이싱은 선택 의존성으로:** outbox는 `Tracer`/`Propagator` 가 없어도 동작해야 한다
  (`@SpringBootTest` 는 관측성을 비활성화한다) → `ObjectProvider` 로 받아 없으면 건너뛴다.
  신뢰성(원자성·at-least-once)은 트레이싱과 무관한 관심사다.

---

## 7. 검증 (실증)

- **빌드/테스트:** `BUILD SUCCESSFUL` — **35개 테스트 통과**(실패 0, Phase 11의 15개에서 +20). 신규:
  - **주문 상태 기계**(7): 해피패스 전이, 확정은 재고 예약을 먼저 요구, **재배달 멱등**, 두 보상 경로, 종료 상태 불변.
  - **order Saga 반응**(7): 확정/취소 발행, `InventoryReserved` 는 아무것도 발행하지 않음(payment가 알아서 함),
    중복 배달 무시, 이미 취소된 주문은 확정되지 않음, 모르는 주문은 재시도 루프 방지.
  - **inventory 보상**(4): 실패 시 **롤백 후 별도 트랜잭션으로 InventoryFailed 발행**, 보상 호출, 중복 흡수.
  - **payment Saga**(3): 성공 청구, **거절이 예외가 아니라 이벤트**, **중복 배달이 이중 청구를 만들지 않음**.
  - **읽기 모델 단조 전이**(5+5): 순서 뒤바뀐 이벤트가 종료 상태를 되돌리지 못함, `OrderPlaced` 재배달이 상태를 리셋하지 않음.
- **라이브 스모크**(`--profile async`): §7.1 참조.

### 7.1 라이브 검증 결과 (깨끗한 상태 `down -v` → `--profile async`, 15컨테이너)

**① 해피패스** — 주문(2개 × 10.00), 초기 재고 100:
```
응답 즉시: status=PENDING            ← 아무것도 기다리지 않는다
폴링:      PENDING → INVENTORY_RESERVED → CONFIRMED   (약 3초)
결과:      재고 100→98, payments 1건, payment_id 기록됨, 읽기 모델 CONFIRMED
```
서비스 로그가 한 traceId(`a0d367e3…`)를 공유: `주문 접수` → `재고 예약 성공` → `결제 성공` → `Saga 전이 …→CONFIRMED`.

**② 짧은 보상(재고 부족)** — 수량 9999 주문:
```
inventory: 재고 예약 실패 → InventoryFailed 발행
           reason=Insufficient stock … requested 9999, available 98
order:     Saga 전이 event=InventoryFailed → status=CANCELLED
결과:      재고 98 그대로(부분 차감 없음), 예약 원장 0건, 결제 시도조차 없음
```

**③ 긴 보상(결제 거절)** — 합계 10.99:
```
inventory: 재고 예약 성공 (98→97)
order:     Saga 전이 event=InventoryReserved → status=INVENTORY_RESERVED
payment:   결제 거절 → PaymentDeclined 발행 (amount=10.99)
inventory: 보상 완료 — 재고 해제        ← ★ semantic undo (97→98)
order:     Saga 전이 event=PaymentDeclined → status=CANCELLED
결과:      재고 98로 원복, 예약 원장 0건(해제 시 삭제), payments 0건
```

**④ Saga 한 트레이스(Phase 10·11의 숙제 해결)** — 거절 케이스의 traceId 하나를 Tempo에서 조회:
```
서비스별 스팬: gateway 6 · order 13 · inventory 7 · payment 4 · order-query 5   (총 35 스팬)
outbox-relay 스팬 5개 = 비동기 홉을 이어 붙인 지점
스팬 이름: order-events send/receive, inventory-events send/receive, payment-events receive …
```
→ **5개 서비스가 하나의 트레이스**로 묶였다. traceparent 복원이 없다면 홉마다 트레이스가 끊겨 5~6개로 쪼개진다.

**⑤ 멱등성(전체 리플레이)** — 3개 서비스를 멈추고 **모든 컨슈머 그룹 오프셋을 0으로 리셋**한 뒤 재기동:
```
before: stock=98, payments=1, statuses=CONFIRMED,CANCELLED,CANCELLED
after : stock=98, payments=1, statuses=CONFIRMED,CANCELLED,CANCELLED   ← 완전 동일
멱등 가드 작동: inventory 4건 / payment 2건 / order 5건 "이미 처리된 메시지" skip
미발행 outbox: order 0 + inventory 0 + payment 0
```
→ 과거 이벤트를 전부 다시 소비해도 **이중 청구·이중 차감·상태 변동이 없다**(effectively-once).

> 실행: `docker compose -f deploy/compose/compose.yml --profile async up -d --build`
> **Phase 12부터 전체 흐름은 `--profile async` 가 필수다**(Kafka 없이는 Saga가 진행되지 않는다).
> 보는 법: kafka-ui `:8090`(3개 토픽·그룹별 lag) · Grafana `:3000`(Saga 트레이스) · Swagger 각 서비스.

---

## 8. 알려진 한계 → 해결 Phase

| 한계 | 설명 | 해결 |
|---|---|---|
| **흐름이 흩어져 있다** | 전체 Saga 순서를 한곳에서 볼 수 없다(각 서비스 리스너에 분산) | **Phase 13**(오케스트레이션 + `saga_instance` 상태 테이블) |
| **정체된 Saga를 못 깨운다** | payment가 계속 죽어 있으면 주문은 `INVENTORY_RESERVED` 로 영원히 남는다(타임아웃 없음) | **Phase 13**(`@Scheduled` 타임아웃 sweep) |
| **확정 후 취소 불가** | CONFIRMED 주문은 취소하지 않는다 — 환불(`PaymentRefunded`) 보상 미구현 | 후속(로드맵 부록의 환불 보상) |
| **poison 메시지·무한 재시도** | 투영/소비 중 예기치 못한 예외는 계속 재배달된다 | **Phase 14**(DLQ·백오프·`attempts` 격리) |
| **보상 자체의 실패** | 재고 해제가 실패하면 되돌릴 장치가 없다(로그만) | **Phase 14**(재시도·DLQ) + 운영 알림 |
| **전이 유실 가능** | `InventoryReserved` 가 `OrderPlaced` 보다 먼저 읽기 모델에 도착하면 그 전이는 버려진다(문서 없음) | 종료 상태는 자기치유됨. 엄격히는 지연 재처리 필요(후속) |
| **다품목 예약의 락 범위** | 여러 상품을 한 트랜잭션에서 순차 락 → 처리량 제한(교착은 정렬로 회피) | 운영: 락 범위·파티셔닝 재설계(후속) |
| **`--profile async` 필수** | Kafka 없이는 주문이 `PENDING` 에서 멈춘다 | 설계상(이벤트 기반의 본질) |
| **코레오그래피 `PaymentCharged` 선행 도착 시 주문 영구 미확정** | 서로 다른 리스너 컨테이너가 두 이벤트를 처리해 순서가 뒤바뀌면, 확정 지시가 `PENDING`에서 무시돼 주문이 "결제됐는데 미확정"으로 영영 남았다 | ✅ **감사(2026-08-02)에서 단조 전이로 해결** — `confirm`이 `PENDING`에서도 확정을 허용(§4.1·§4.9) |

---

## 복습 포인트 (스스로 답해보기)

<details>
<summary>Q1. 코레오그래피에서 이벤트의 <b>도착 순서</b>와 <b>인과 순서</b>는 왜 다를 수 있는가? <code>confirm</code>이 <code>PENDING</code>에서도 확정을 허용하도록 바뀐 것(단조 전이, §4.1)이 왜 이 문제의 답이 되는가?</summary>

`InventoryReserved`와 `PaymentCharged`는 서로 다른 리스너 컨테이너가 처리하므로, 인과적으로는 재고 예약이 결제보다 먼저 일어나도 **처리 순서까지 보장되지는 않는다**. 확정을 `INVENTORY_RESERVED`에서만 허용하면, `PaymentCharged`가 먼저 도착했을 때 그 시점 주문은 아직 `PENDING`이라 확정 지시가 조용히 무시되고 — 뒤늦게 `InventoryReserved`가 와도 지나간 `PaymentCharged`는 다시 오지 않으므로 주문은 영영 미확정으로 남는다(감사 2026-08-02, H2). 단조 전이는 "뒤 단계 사건은 앞 단계를 건너뛰어도 받아들이고, 앞 단계 사건이 늦게 오면 무시한다"는 규칙으로 이를 해결한다 — 도착 순서와 무관하게 최종 상태가 같아진다.
</details>

<details>
<summary>Q2. 전이 메서드(<code>markInventoryReserved</code>·<code>confirm</code>·<code>cancel</code>)가 왜 예외 대신 <code>boolean</code>을 반환하는가?</summary>

at-least-once 배달에서 재배달·순서뒤바뀜은 **정상 상황**이다. 예외를 던지면 트랜잭션이 롤백되고 브로커가 같은 메시지를 계속 재배달하다 결국 DLT로 격리된다 — 정상적으로 무시해도 될 메시지가 poison 취급을 받는다. 도메인이 "이번엔 할 일이 없다"고 조용히 `false`로 답해야 재시도 루프에 빠지지 않는다.
</details>

<details>
<summary>Q3. 보상(compensation)이 DB 롤백과 다른 점은 무엇인가?</summary>

롤백은 커밋 전 상태로 되돌리는 기술적 연산이다. 보상은 **이미 커밋된 효과**를 되돌리는 새로운 업무 행위(재고 해제, 환불 등)다. 그래서 보상 자체가 실패할 수 있고, 순서가 중요하며(예: Phase 13의 오케스트레이션은 보상 완료를 확인한 뒤에 종료한다), 도메인이 새로 정의해야 하는 연산이다.
</details>

<details>
<summary>Q4. 재고 해제(<code>InventoryReleased</code>)는 "무엇을 되돌려야 하는지"를 어떻게 아는가?</summary>

`PaymentDeclined` 이벤트에는 품목 정보가 없고(주문 id·금액만), inventory는 다른 서비스의 DB를 조회할 수 없다. 그래서 예약 시점에 `stock_reservations`(예약 원장)에 무엇을 잡았는지 스스로 기록해 두고, 해제할 때 그 원장을 **꺼내며 삭제**한다 — 두 번 보상해도 두 번째는 되돌릴 게 없어 자연히 멱등하다.
</details>

## 9. 용어사전

- **Saga:** 로컬 트랜잭션의 연쇄로 분산 업무를 수행하는 패턴(글로벌 트랜잭션 대체).
- **코레오그래피/오케스트레이션:** 각자 이벤트에 반응 / 중앙 조정자가 지시.
- **보상(compensation)/semantic undo:** 커밋된 효과를 되돌리는 새로운 업무 행위(롤백이 아니다).
- **짧은/긴 보상:** 되돌릴 게 없는 실패 / 여러 단계를 되돌려야 하는 실패.
- **이벤트/명령:** 일어난 사실 / 시켜야 할 일. 코레오그래피는 사실만 주고받는다.
- **상태 기계:** 정해진 경로로만 전이하는 구조. 잘못된 전이를 도메인이 거부한다.
- **`traceparent`:** W3C 트레이스 컨텍스트 헤더. outbox에 저장해 두면 다른 스레드에서 트레이스를 이어붙일 수 있다.
- **예약 원장(reservation ledger):** 무엇을 얼마나 잡아뒀는지 기록 — 보상의 근거.
- **멱등(idempotent)/멱등 소비자(idempotent consumer):** 같은 메시지가 여러 번 도착해도(at-least-once 배달) 결과가 한 번 처리된 것과 같게 만드는 성질/그렇게 동작하는 소비자. 이 Phase의 모든 전이 메서드가 예외 대신 `false`를 반환하는 이유가 이것이다.
- **단조 전이(monotonic transition):** 상태를 앞으로만 진행시켜 도착 순서에 무관하게 만드는 기법.

---

## 10. 참고 / 상호링크

- 직전: [PHASE-11-CQRS](PHASE-11-CQRS.md)(읽기 모델 — 이번에 상태 전이가 추가됨) · [PHASE-10-OUTBOX](PHASE-10-OUTBOX.md)(이 Saga의 모든 발행이 의존하는 신뢰성 장치, `traceparent` 컬럼을 여기서 채웠다)
- 되짚어 보기: [PHASE-1-MONOLITH](PHASE-1-MONOLITH.md)(하나의 `@Transactional` 로 끝났던 시절) · [PHASE-2-SPLIT-PAYMENT](PHASE-2-SPLIT-PAYMENT.md)(원자성이 처음 깨진 순간) · [PHASE-8-OBSERVABILITY](PHASE-8-OBSERVABILITY.md)(보상 흐름을 트레이스로 보기)
- 아키텍처: [HEXAGONAL](HEXAGONAL.md)(리스너=인바운드 어댑터, outbox 발행=아웃바운드 어댑터)
- 다음: **Phase 13**(같은 Saga를 오케스트레이션으로 — 흐름을 한곳에서 보기 + 타임아웃 sweep) → **14**(복원력·DLQ)
- 로드맵/부록 코드: [`MSA-LEARNING-PLAN.md`](../MSA-LEARNING-PLAN.md)(Phase 12 §310~, Saga 부록 §402~)

*각 단계의 “알려진 한계 → 해결 Phase”는 [README](../README.md) 인덱스에서 모아 볼 수 있습니다.*
