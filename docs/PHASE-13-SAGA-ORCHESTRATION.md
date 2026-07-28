# Phase 13 — Saga: 오케스트레이션 (중앙 조정자로 다시 만들기)

> **한 줄 요약:** Phase 12와 **똑같은 업무**를 이번엔 **중앙 조정자(orchestrator)** 로 다시 만든다.
> 참여 서비스는 이벤트를 해석하지 않고 **지시받은 일만** 하고 결과를 돌려준다.
> 그 대가로 얻는 것: `SELECT state FROM saga_instance WHERE order_id=…` **한 줄로** 진행 상황을 알고,
> **타임아웃 sweep**으로 멈춰 선 Saga를 깨울 수 있다 — Phase 12가 못 하던 일이다.

초심자(Java/Spring은 알지만 분산 워크플로는 처음) 기준으로 **왜 → 무엇을 → 어떻게** 순서로 설명합니다.

---

## 0. 이번 단계에서 한 일 (요약)

- **`saga_instance` 상태 테이블** — 각 주문의 Saga가 지금 어느 단계인지, 마지막으로 언제 움직였는지 기록.
- **오케스트레이터**(`SagaOrchestratorService`) — 리플라이를 받아 다음 커맨드를 결정하는 **하나의 switch문**.
- **커맨드/리플라이 계약** — `ReserveStock`·`ChargePayment`·`ReleaseStock` 커맨드, 모든 결과를 담는 단일 `SagaReply`.
- **참여 서비스가 "멍청한" 핸들러로** — inventory·payment가 남의 이벤트를 해석하지 않고 지시만 수행.
- **★ 타임아웃 sweep**(`@Scheduled`) — 응답 없는 Saga를 재촉(재전송)하고, 한도를 넘으면 **보상 후 종료**.
- **커맨드 멱등성**(`processed_commands`) — 재전송해도 이중 처리되지 않도록 **결정적 커맨드 키** 도입.
- **`saga.mode` 토글** — `orchestration`(기본) ↔ `choreography`(Phase 12)를 **갈아 끼우며 비교**.

---

## 1. 왜 — 직전(Phase 12)의 문제

Phase 12의 코레오그래피는 아름답게 동작했지만, 두 가지가 아팠다.

**① 흐름이 어디에도 없다.** "주문이 지금 어디까지 갔나?"를 알려면 order·inventory·payment 세 서비스의
리스너를 모두 읽고 머릿속에서 순서를 재구성해야 했다. 새로 합류한 개발자가 전체 흐름을 파악하기 어렵고,
장애 시 "어느 단계에서 멈췄나"를 즉시 답할 수 없다.

**② 멈춘 Saga를 아무도 모른다.** payment-service가 죽어 있으면 주문은 `INVENTORY_RESERVED` 상태로
**영원히** 남았다. 재고는 잡힌 채, 결제는 오지 않고, **아무도 "얘가 멈췄다"는 사실을 인지하지 못한다.**
코레오그래피에는 "누가 얼마나 기다렸는지" 아는 주체가 없기 때문이다.

**오케스트레이션**은 조정자를 세워 이 둘을 해결한다. 조정자는 각 Saga의 상태와 마지막 전이 시각을
자기 DB에 들고 있으므로, 흐름을 한눈에 보여줄 수 있고 데드라인이 지나면 개입할 수 있다.

> 💡 **어느 쪽이 옳은가?** 정답은 없다. 단계가 적고 서비스가 자율적이면 코레오그래피가 가볍고,
> 단계가 많거나 **가시성·타임아웃·보상 순서 통제**가 중요하면 오케스트레이션이 낫다.
> 이 프로젝트는 둘 다 만들어 두고 `saga.mode` 로 바꿔 끼우며 체감하는 것이 목적이다.

---

## 2. 핵심 개념 (용어부터)

- **오케스트레이션(orchestration):** 중앙 조정자가 "이제 이걸 해"라고 **지시**하고 결과를 받아 다음을 정하는 방식.
  ↔ **코레오그래피**: 각자 남의 이벤트를 듣고 스스로 반응(Phase 12).
- **커맨드(command) vs 이벤트(event):**
  - 이벤트 = "재고가 예약됐다"(이미 일어난 **사실**). 발행자는 **누가 듣는지 모른다**.
  - 커맨드 = "재고를 예약해라"(특정 수신자에게 시키는 **일**). 발신자는 **누구에게 보내는지 안다**.
- **리플라이(reply):** 커맨드 처리 결과를 조정자에게 돌려주는 응답. 참여 서비스의 **의무**다 —
  응답하지 않으면 그 Saga는 정체된다.
- **Saga 인스턴스(saga instance):** 한 건의 Saga 실행 상태. 여기서는 `saga_instance` 테이블의 한 행.
- **타임아웃 sweep:** 응답을 기다리다 데드라인을 넘긴 Saga를 주기적으로 찾아 재촉하거나 종료하는 배치.
- **결정적 커맨드 키(deterministic command key):** `(sagaId, 커맨드타입)`에서 **계산해서** 만드는 dedup 키.
  재전송해도 같은 값이 나오므로 "이미 한 일"을 알아볼 수 있다(메시지 id로는 불가능 — 재전송하면 새 id가 생긴다).
- **멍청한(dumb) 참여자:** 업무 판단을 하지 않고 지시받은 일만 하는 서비스. 로직이 조정자로 모인다.

---

## 3. 구성 (그림)

```
                          ┌──────────────── order-service ────────────────┐
   POST /orders           │  ① 주문 PENDING 저장                            │
   ─────────────────────▶ │  ② saga_instance(STARTED→AWAITING_INVENTORY)   │  ← 한 커밋
                          │  ③ ReserveStock 커맨드 outbox 기록               │
                          └───────────────────┬───────────────────────────┘
                                              │ saga-commands
                    ┌─────────────────────────┼─────────────────────────┐
                    ▼                                                   ▼
        ┌── inventory-service ──┐                          ┌── payment-service ──┐
        │ ReserveStock 수행      │                          │ ChargePayment 수행   │
        │ ReleaseStock 수행(보상)│                          │                      │
        └───────────┬───────────┘                          └──────────┬───────────┘
                    │  saga-replies (SagaReply 하나로 통일)             │
                    └─────────────────────┬───────────────────────────┘
                                          ▼
                          ┌──────── SagaOrchestratorService ────────┐
                          │  switch (reply.kind) {                   │
                          │    STOCK_RESERVED   → ChargePayment 지시  │
                          │    STOCK_FAILED     → 주문 취소(종료)      │
                          │    PAYMENT_CHARGED  → 주문 확정(종료)      │
                          │    PAYMENT_DECLINED → ReleaseStock 지시   │
                          │    STOCK_RELEASED   → 주문 취소(종료)      │
                          │  }                                       │
                          └──────────────────┬───────────────────────┘
                                             │ 상태 갱신
                                    ┌────────▼────────┐
                                    │  saga_instance   │◀── SagaTimeoutSweeper(@Scheduled)
                                    │  (한 줄로 조회)   │     정체 Saga 재촉 → 한도 초과 시 보상·종료
                                    └──────────────────┘
```

**Phase 12와 비교하면:** 화살표의 방향이 바뀌었다. 거기서는 inventory→payment로 **사실이 흘러가며**
각자 반응했지만, 여기서는 모든 화살표가 **조정자를 거쳐** 나가고 들어온다.

---

## 4. 코드·설정 한 부분씩

### 4.1 Saga 상태 기계 (순수 도메인)
```java
public enum SagaState {
    STARTED, AWAITING_INVENTORY, AWAITING_PAYMENT, COMPENSATING_INVENTORY, COMPLETED, CANCELLED;

    public boolean isTerminal()      { return this == COMPLETED || this == CANCELLED; }
    public boolean isAwaitingReply() { return this == AWAITING_INVENTORY
                                           || this == AWAITING_PAYMENT
                                           || this == COMPENSATING_INVENTORY; }
}
```
`isAwaitingReply()` 가 곧 **타임아웃 sweep의 대상 조건**이다 — "응답을 기다리는 중"인 상태만 정체될 수 있다.

```java
public boolean awaitPayment(Instant now) {
    if (state != SagaState.AWAITING_INVENTORY) return false;   // 중복 리플라이 → 조용히 무시(멱등)
    return moveTo(SagaState.AWAITING_PAYMENT, now);
}
public void recordRetry(Instant now) { this.attempts++; this.updatedAt = now; }  // 재촉: 상태 유지, 시계만 리셋
```
전이가 `boolean` 을 돌려주는 이유는 Phase 12와 같다: at-least-once 배달에서 **재배달은 정상**이므로
예외가 아니라 "할 일 없음"으로 흡수해야 한다.

### 4.2 조정자 — Saga 전체가 이 switch 하나에
```java
switch (reply.kind()) {
    case STOCK_RESERVED           -> onStockReserved(saga, reply, now);   // → ChargePayment 지시
    case STOCK_RESERVATION_FAILED -> onStockReservationFailed(...);       // → 주문 취소(보상 불필요)
    case PAYMENT_CHARGED          -> onPaymentCharged(...);               // → 주문 확정
    case PAYMENT_DECLINED         -> onPaymentDeclined(...);              // → ReleaseStock 지시
    case STOCK_RELEASED           -> onStockReleased(...);                // → 주문 취소(보상 완료)
}
```
**이 파일 하나만 읽으면 흐름을 안다.** 이것이 Phase 13에서 얻는 가장 큰 것이다.

주목할 차이 — 결제 거절 처리:
```java
private void onPaymentDeclined(SagaInstance saga, ...) {
    saga.startCompensation(now);                       // COMPENSATING_INVENTORY
    publishSagaCommandPort.releaseStock(...);          // 재고 해제를 '지시'
    // ⚠️ 여기서 주문을 바로 취소하지 않는다 — 보상이 끝난 뒤(STOCK_RELEASED)에 종료한다.
}
```
Phase 12에서는 `PaymentDeclined` 를 order와 inventory가 **동시에** 듣고 각자 반응했다.
조정자는 **보상 완료를 확인하고 나서** 종료하므로 순서를 통제할 수 있다.

### 4.3 ★ 타임아웃 sweep — Phase 12가 못 하던 일
```java
@Scheduled(fixedDelayString = "${saga.timeout.sweep-interval:5000}")
@Transactional
public void sweep() {
    for (SagaInstance saga : sagaRepository.findStalled(now, deadline, 50)) {
        if (saga.getAttempts() >= maxAttempts) giveUp(saga, now);   // 포기 → 보상/취소
        else                                   resendCommand(saga, now);  // 재촉 → 같은 커맨드 재전송
    }
}
```
정책이 두 단계인 이유: 참여 서비스가 **잠깐 죽었다 살아난 경우**(재전송으로 해결)와
**계속 죽어 있는 경우**(포기해야 함)를 구분해야 하기 때문이다.

포기할 때 중요한 분기:
```java
private void giveUp(SagaInstance saga, Instant now) {
    if (saga.getState() == SagaState.AWAITING_PAYMENT) {
        saga.startCompensation(now);
        publishSagaCommandPort.releaseStock(...);   // ★ 재고를 이미 잡아뒀으므로 먼저 풀어야 한다
        return;
    }
    saga.cancel(now); cancelOrder(saga, now);       // 잡은 게 없으면 바로 종료
}
```
그냥 취소해 버리면 **잡아둔 재고가 영영 샌다**. 포기에도 보상이 필요하다.

### 4.4 재전송이 안전한 이유 — 결정적 커맨드 키
```java
public static UUID of(UUID sagaId, String commandType) {
    return UUID.nameUUIDFromBytes((sagaId + ":" + commandType).getBytes(UTF_8));
}
```
**여기가 Phase 13에서 가장 미묘한 지점이다.** Phase 10의 `processed_messages` 는 **메시지 id**로 중복을 걸렀다.
그런데 sweep이 커맨드를 재전송하면 outbox가 **새 메시지 id**를 발급하므로 dedup을 통과해 버린다
→ 재고를 두 번 잡고, 결제를 두 번 한다.

그래서 `(sagaId, 커맨드타입)` 에서 **계산해서** 키를 만든다. 몇 번을 재전송해도 같은 키다.

```java
Optional<PriorOutcome> prior = transactions.priorOutcome(commandKey);
if (prior.isPresent()) {
    transactions.replayReply(sagaId, orderId, prior.get());   // ★ 무시가 아니라 '재응답'
    return;
}
```
그리고 중복일 때 **조용히 무시하면 안 된다** — 조정자는 리플라이를 기다리고 있으므로,
저장해 둔 결과로 **같은 응답을 다시 보내** 진행시켜야 한다. 그래서 `processed_commands` 는
결과(`reply_kind`·`reason`)까지 보관한다.

### 4.5 참여 서비스는 "멍청한" 핸들러로
```java
// Phase 12: 사실을 듣고 스스로 판단했다
void onPaymentDeclined(UUID messageId, PaymentDeclinedEvent event) { /* 재고를 풀어야겠군 */ }

// Phase 13: 지시받은 일만 한다 — 왜 푸는지는 몰라도 된다
void onReleaseStock(ReleaseStockCommand command) { /* 시킨 대로 푼다 */ }
```
업무 판단의 책임이 참여자에서 조정자로 옮겨갔다. 참여 서비스는 재사용하기 쉬워지지만
(다른 Saga에서도 같은 커맨드를 쓸 수 있다) 조정자 없이는 아무 일도 하지 않는다.

### 4.6 두 방식을 갈아 끼우는 토글
```yaml
saga:
  mode: orchestration          # ↔ choreography (Phase 12)
  timeout:
    sweep-interval: 5000       # ms — 정체 Saga 탐색 주기
    deadline: 15s              # 이 시간 동안 응답 없으면 정체로 판단
    max-attempts: 3            # 이만큼 재촉해도 안 되면 보상·종료
```
```java
@ConditionalOnProperty(name = "saga.mode", havingValue = "choreography")
class InventoryEventListener { … }        // Phase 12 리스너 — 오케스트레이션에선 꺼진다

@ConditionalOnProperty(name = "saga.mode", havingValue = "orchestration", matchIfMissing = true)
class SagaCommandListener { … }           // Phase 13 리스너
```
⚠️ **두 방식이 동시에 켜지면 이중 처리**된다(재고를 두 번 잡는다). 그래서 리스너들을 상호배타로 게이팅했다.

### 4.7 sweep 전용 부분 인덱스
```sql
CREATE INDEX idx_saga_instance_stalled ON saga_instance (updated_at)
    WHERE state IN ('AWAITING_INVENTORY', 'AWAITING_PAYMENT', 'COMPENSATING_INVENTORY');
```
종료된 Saga는 인덱스에서 아예 빠지므로, 누적 데이터가 아무리 늘어도 sweep 비용이 **진행 중인 Saga 수에만**
비례한다(5초마다 도는 배치라 이 차이가 크다).

---

## 5. 요청 하나가 흐르는 순서 (해피패스)

1. `POST :8000/orders` → 주문 **PENDING** 저장 + `saga_instance`(**AWAITING_INVENTORY**) + `ReserveStock` 커맨드를 **한 커밋**으로 → **201 즉시 반환**.
2. 릴레이(≤1s)가 커맨드를 `saga-commands` 로 발행(traceparent 복원 — Phase 12의 장치 그대로).
3. inventory가 재고 차감 + 원장 기록 → `SagaReply(STOCK_RESERVED)`.
4. 조정자가 리플라이를 받아 주문을 **INVENTORY_RESERVED** 로 전이 + **AWAITING_PAYMENT** + `ChargePayment` 지시.
5. payment가 청구 → `SagaReply(PAYMENT_CHARGED)`.
6. 조정자가 주문 **CONFIRMED** + Saga **COMPLETED** + `OrderConfirmed` 사실 발행(읽기 모델용).

**실패 A(재고 부족):** 3에서 `STOCK_RESERVATION_FAILED` → 조정자가 주문 취소 + Saga `CANCELLED`(보상 불필요).
**실패 B(결제 거절):** 5에서 `PAYMENT_DECLINED` → 조정자가 `ReleaseStock` **지시** → `STOCK_RELEASED` → 주문 취소.
**실패 C(무응답):** 참여 서비스가 죽음 → sweep이 15초 뒤 재촉(최대 3회) → 그래도 없으면 보상 후 종료.

---

## 6. 원리 / 트레이드오프

| | 코레오그래피(Phase 12) | 오케스트레이션(Phase 13) |
|---|---|---|
| **흐름 파악** | 여러 서비스 리스너를 읽어야 함 ❌ | 조정자 switch 한 곳 ✅ / `saga_instance` 한 줄 조회 ✅ |
| **결합** | 느슨함 ✅ (사실만 발행) | 참여자가 조정자에 결합 ❌ |
| **단일 고장점** | 없음 ✅ | 조정자가 SPOF ❌ |
| **타임아웃/정체 감지** | 불가능 ❌ | sweep으로 가능 ✅ |
| **보상 순서 통제** | 각자 동시에 반응 ❌ | 조정자가 순서 지정 ✅ |
| **테스트** | Kafka 없이 흐름 검증 어려움 ❌ | 상태 기계 순수 단위 테스트 ✅ |
| **참여자 재사용** | 이벤트 해석 로직이 박혀 있음 | 커맨드 핸들러라 재사용 쉬움 ✅ |

- **언제 무엇을:** 단계 2~3개에 서비스가 자율적이면 코레오그래피가 가볍다. 단계가 늘거나
  **"지금 어디?"에 답해야 하고 타임아웃·보상 순서가 중요하면** 오케스트레이션.
- **조정자가 SPOF라는 말의 실제 의미:** 조정자가 죽어도 **데이터는 안전하다**(상태가 DB에 있고 커맨드는 outbox에).
  다만 그동안 Saga가 **진행되지 않을 뿐**이고, 살아나면 sweep이 밀린 것들을 이어서 처리한다.
- **재전송 = 멱등이 아니다.** 재전송을 안전하게 만드는 것은 **결정적 커맨드 키 + 결과 보관**이다.
  이게 없으면 sweep이 오히려 이중 청구·이중 차감을 만든다(가장 흔한 함정).
- **중복 커맨드에 침묵하면 안 된다.** 조정자는 응답을 기다리므로, 중복이어도 **저장된 결과로 재응답**해야
  Saga가 진행된다. "멱등 = 무시"라고 생각하면 여기서 데드락이 난다.
### 6.1 실제로 걸린 두 함정 (라이브 검증에서 발견)

**① `spring.json.trusted.packages` 는 하위 패키지를 포함하지 않는다.**
```yaml
# ❌ com.shopsaga.events.commands.* 가 거부된다
spring.json.trusted.packages: "com.shopsaga.events"
# ✅ 각각 명시
spring.json.trusted.packages: "com.shopsaga.events,com.shopsaga.events.commands"
```
증상: 소비자가 `IllegalArgumentException: class '…ReserveStockCommand' is not in the trusted packages` 로
**모든 커맨드 소비에 실패**하고, `DefaultErrorHandler` 는 역직렬화 예외를 처리하지 못해 컨슈머가 같은 오프셋에서 계속 막힌다.
(임의 클래스 역직렬화는 원격 코드 실행으로 이어질 수 있어 기본값이 엄격한 것이다 — 편하다고 `*` 로 열면 안 된다.)

**② 공유 라이브러리의 엔티티는 "전부" 스캔된다.**
`@EntityScan("com.shopsaga.outbox")` 는 그 패키지의 엔티티를 모두 등록하므로,
`ProcessedCommand` 를 쓰지 않는 order-service 도 `processed_commands` **테이블이 없으면 기동에 실패**한다
(`ddl-auto=validate` → `SchemaManagementException: missing table`). 실제로 이걸 빠뜨려 크래시 루프에 빠졌다.
→ 라이브러리를 쓰는 서비스는 그 **테이블 세트를 모두** 갖추거나, 라이브러리를 하위 패키지로 쪼개 선택적으로 스캔해야 한다.

- **프레임워크 사이드바(읽기만):** 실무에서는 손으로 만들지 않는다 —
  **Axon**(이벤트소싱+Saga), **Spring Statemachine**(상태 기계), **Temporal**·**Camunda**(워크플로 엔진).
  타임아웃·재시도·보상·가시성을 이미 제공한다. 지금 손으로 만든 이유는 **그것들이 무엇을 해 주는지**를 알기 위해서다.

---

## 7. 검증 (실증)

- **빌드/테스트:** `BUILD SUCCESSFUL` — **61개 테스트 통과**(실패 0, Phase 12의 35개에서 +26). 신규:
  - **Saga 상태 기계**(8): 해피패스, 짧은/긴 보상 경로, **중복 리플라이 멱등**, 종료 상태 불변,
    `isStalled` 판정, `recordRetry` 가 상태는 유지하고 시계만 리셋, 단계 변경 시 attempts 리셋.
  - **조정자 분기**(8): 각 리플라이가 올바른 다음 커맨드를 내보내는지, **결제 거절 시 바로 취소하지 않고 보상을 거치는지**,
    중복/늦은 리플라이·모르는 Saga 처리.
  - **타임아웃 sweep**(6): 각 단계별 재전송, **결제 단계 포기 시 보상으로 전환**(그냥 취소하면 재고가 샌다),
    보상까지 실패해도 종료, 정체 없으면 무동작.
  - **결제 커맨드 멱등**(4): 재전송이 이중 청구를 만들지 않고 **응답은 다시 보내는지**, 커맨드 키 결정성.

### 7.1 라이브 검증 결과 (`--profile async`, 15컨테이너)

**① 해피패스** — 주문(2×10.00), 초기 재고 100:
```
saga=AWAITING_INVENTORY/att=0  order=PENDING
saga=COMPLETED/att=0           order=CONFIRMED      ← 수 초 만에 종료
재고 100→98 · 결제 1건
★ 한 줄 조회:  SELECT state FROM saga_instance WHERE order_id=…  →  COMPLETED
```

**② 짧은 보상**(재고 부족 9999): `AWAITING_INVENTORY` → **`CANCELLED`**, 재고 98 **변화 없음**, 예약 원장 0건.

**③ 긴 보상**(결제 거절 10.99) — **오케스트레이션의 차이가 드러나는 지점**:
```
saga=AWAITING_INVENTORY        order=PENDING
saga=COMPENSATING_INVENTORY    order=INVENTORY_RESERVED   ← ★ 주문은 아직 취소되지 않았다
saga=CANCELLED                 order=CANCELLED            ← 보상 완료를 확인하고 나서 종료
재고 98 원복 · 예약 원장 0건 · 결제 0건
```
Phase 12에서는 order와 inventory가 `PaymentDeclined` 를 **동시에** 듣고 각자 반응했다.
여기서는 조정자가 `COMPENSATING_INVENTORY` 를 거치며 **순서를 통제**한다 — 그 중간 상태가 테이블에 보인다.

**④ ★ 타임아웃 sweep / 크래시 복구** — payment-service를 **중단**한 채 주문(3×10.00):
```
17:32:28  saga=AWAITING_PAYMENT att=0   재고 98→95 (예약됨)
17:32:43  커맨드 재전송 … attempts=1     ← deadline(15s) 초과 → 재촉
17:39:41  커맨드 재전송 … attempts=2
17:39:56  커맨드 재전송 … attempts=3
17:40:11  ERROR 결제 응답 없음 — 재시도 한도 초과, 보상으로 전환   ← 포기
17:40:13  Saga 종료 → CANCELLED (보상 완료)
최종: 재고 95→98 원복 · order=CANCELLED · 멈춘 saga 없음
```
**Phase 12였다면 이 주문은 `INVENTORY_RESERVED` 로 영원히 남고 재고 3개가 잠긴 채였다.**

**⑤ 커맨드 멱등성(재전송 4건)** — payment 복귀 후:
```
[커맨드] ChargePayment 수신 …            ← 큐에 쌓인 4건(최초 1 + 재전송 3)
[커맨드] 중복 ChargePayment — 재청구 없이 리플라이 재전송   ×3
결제 테이블: 해당 주문 1건 (4건 아님)
```
결정적 커맨드 키가 **이중(사중) 청구를 막았다.** 동시에 "무시"가 아니라 **재응답**하는 것도 확인된다.

### 7.2 ⚠️ 검증이 드러낸 결함 — 고아 결제(orphan payment)

④와 ⑤를 이어 보면 **설계상의 구멍**이 드러난다:
```
17:40  조정자가 포기 → 재고 해제 → 주문 CANCELLED
17:58  payment 복귀 → 큐에 있던 ChargePayment 를 수행 → 30.00 CAPTURED
       (리플라이는 도착했지만 Saga가 이미 종료라 조정자가 무시)
결과: 취소된 주문에 결제만 남음
```
**원인:** 타임아웃은 "참여자가 죽었다"와 "참여자가 느리다"를 **구분할 수 없다**.
포기한 뒤 살아난 참여자는 자기가 늦었다는 사실을 모른 채 지시를 수행한다.

**실무의 해법(이 Phase 범위 밖):**
1. **환불 보상** — `PaymentRefunded` 를 Saga 카탈로그에 추가해 고아 결제를 되돌린다(가장 정석).
2. **참여자 측 유효성 확인** — 커맨드 처리 전에 "이 Saga가 아직 살아 있나?"를 조정자에게 묻거나
   커맨드에 만료 시각(deadline)을 실어 보내 지난 커맨드는 스스로 거부하게 한다.
3. **돈에는 포기하지 않기** — 결제 단계는 `max-attempts` 를 크게 두거나 자동 포기 대신 **운영자 개입**으로 돌린다.

> 이 프로젝트에서는 문제를 **드러내 놓는 쪽**을 택했다 — 타임아웃 기반 Saga가 공짜가 아니라는 것이
> 이 Phase에서 배울 가장 값진 교훈이기 때문이다.

---

## 8. 알려진 한계 → 해결 Phase

| 한계 | 설명 | 해결 |
|---|---|---|
| **poison 메시지·무한 재시도** | 커맨드 처리 중 예기치 못한 예외는 계속 재배달된다 | **Phase 14**(DLQ·백오프·`attempts` 격리) |
| **조정자 SPOF** | order-service가 죽으면 모든 Saga가 멈춘다(데이터는 안전) | 운영: 다중 인스턴스 + sweep 중복 실행 방지(리더 선출/락) |
| **sweep 중복 실행** | order-service를 2개 이상 띄우면 두 인스턴스가 같은 Saga를 동시에 재촉할 수 있다 | 운영: `SELECT … FOR UPDATE SKIP LOCKED` 또는 ShedLock |
| **★ 고아 결제(orphan payment)** | 타임아웃으로 포기한 뒤 참여자가 살아나면 지시를 수행해 버린다 → **취소된 주문에 결제만 남음**(§7.2에서 실측). 타임아웃은 "죽음"과 "느림"을 구분 못 한다 | 환불 보상(`PaymentRefunded`) 추가 · 커맨드에 만료시각 · 결제는 자동 포기 대신 운영자 개입 |
| **확정 후 취소 불가** | CONFIRMED 주문은 되돌리지 않는다(환불 보상 미구현) | 후속(환불 보상 체인) |
| **보상 자체의 실패** | 재고 해제가 계속 실패하면 sweep이 3회 후 취소로 종료 — 재고는 잠긴 채 남는다 | **Phase 14** + 운영 알림/수동 개입 |
| **커맨드 토픽 공유** | inventory·payment가 같은 `saga-commands` 를 구독해 남의 커맨드도 받아 무시한다(낭비) | 운영: 서비스별 커맨드 토픽 분리 |
| **두 모드 동시 사용 불가** | `saga.mode` 는 상호배타(동시에 켜면 이중 처리) | 설계상 — 비교 학습용 토글 |

---

## 9. 용어사전

- **오케스트레이션/코레오그래피:** 중앙 조정자가 지시 / 각자 이벤트에 반응.
- **커맨드/이벤트:** 시켜야 할 일(수신자를 앎) / 일어난 사실(수신자를 모름).
- **리플라이:** 커맨드 처리 결과 응답. 참여 서비스의 의무.
- **Saga 인스턴스:** Saga 한 건의 실행 상태(`saga_instance` 한 행).
- **타임아웃 sweep:** 데드라인을 넘긴 Saga를 찾아 재촉/종료하는 주기 배치.
- **결정적 커맨드 키:** (sagaId, 커맨드타입)에서 계산한 dedup 키 — 재전송해도 같다.
- **멍청한 참여자:** 판단하지 않고 지시만 수행하는 서비스.
- **부분 인덱스:** 조건에 맞는 행만 담는 인덱스(여기선 진행 중 Saga만).

---

## 10. 참고 / 상호링크

- 직전: [PHASE-12-SAGA](PHASE-12-SAGA.md)(같은 업무의 코레오그래피 버전 — **나란히 읽으면 차이가 선명하다**)
- 기반: [PHASE-10-OUTBOX](PHASE-10-OUTBOX.md)(커맨드·리플라이도 전부 outbox 로 발행) · [PHASE-11-CQRS](PHASE-11-CQRS.md)(읽기 모델은 두 모드 모두에서 동일하게 동작)
- 아키텍처: [HEXAGONAL](HEXAGONAL.md)(조정자=애플리케이션 서비스, 커맨드 발행·리플라이 수신=어댑터)
- 다음: **Phase 14**(복원력 — Resilience4j·DLQ·poison 메시지 격리)
- 로드맵/부록 코드: [`MSA-LEARNING-PLAN.md`](../MSA-LEARNING-PLAN.md)(Phase 13 §318~, Saga 부록 §466~)

*각 단계의 “알려진 한계 → 해결 Phase”는 [README](../README.md) 인덱스에서 모아 볼 수 있습니다.*
