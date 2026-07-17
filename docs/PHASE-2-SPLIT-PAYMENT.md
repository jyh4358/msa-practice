# 결제 서비스 분리 — payment-service 신설 & 원격 결제 전환

> **이 문서는 Phase 2 작업을 설명합니다.** 처음 보는 사람이 "왜 결제를 떼어냈고, 무엇이,
> 어떻게 동작하는지"를 끝까지 이해하도록 개념 → 그림 → 이 프로젝트의 실제 코드/설정 →
> 동작 원리 → 검증 → 한계 순으로 정리했습니다. Phase 2는 두 커밋(**2-1 껍데기 분리**,
> **2-2 실제 원격 연결**)으로 나뉩니다.

---

## 0. 한 줄 요약

> **주문 안에 붙어 있던 "결제"를 독립 서비스로 떼어내고, order가 payment를 네트워크 너머로 호출하게 바꿨다.**
> 결제 데이터의 **소유권**이 order-service에서 payment-service(자기 DB `paymentdb`)로 넘어갔고,
> order는 결제 결과를 `paymentId`(참조)로만 들고 있는다. 그 대가로 **하나의 트랜잭션으로 묶여 있던
> 재고+결제의 ACID가 사라졌다** — 이 잃어버린 원자성이 이후 Phase 10(신뢰성)·12(Saga 보상)의 동기다.

---

## 1. 왜 이 단계인가? (직전 Phase 1까지의 문제)

Phase 1까지 **결제는 주문(Order) 애그리거트의 일부**였다. 결제 도메인이 order-service 안에 있었고,
결제 데이터도 order-service의 DB(`orderdb`)에 `payments` 테이블로 함께 저장됐다.

> **애그리거트(aggregate)**: 하나의 트랜잭션·불변식 단위로 함께 다뤄지는 도메인 객체 묶음. 묶음의
> 대표(진입점)를 **애그리거트 루트**라 한다. 여기서는 Order가 루트이고 결제·주문항목이 그 안에 속했다
> — 즉 주문을 저장하면 결제도 한 트랜잭션으로 함께 저장됐다. (DDD 용어. 자세히는 → HEXAGONAL.md)

```java
// (Phase 1) services/order-service/.../domain/Payment.java — Order 애그리거트의 일부
/** 결제 — Order 애그리거트의 일부(주문당 1건). ...
 *  Phase 1에서는 가짜 게이트웨이를 거쳐 캡처되며, 거절은 Order.capturePayment()에서 예외로 처리한다. */
public class Payment {
    private final BigDecimal amount;
    private final PaymentStatus status;
    private final Instant capturedAt;
    public static Payment capture(BigDecimal amount) { ... }
}
```

한 서비스·한 DB·한 트랜잭션 안이라 편했다. 하지만 MSA의 원칙과 어긋난다.

- **서비스별 DB 소유(database-per-service).** 결제 데이터를 order가 들고 있으면 결제 정책이
  바뀔 때마다 order를 건드려야 하고, 두 관심사가 한 배포 단위에 묶인다.
- **독립 배포·독립 확장 불가.** 결제만 스케일하거나 결제만 재배포할 수 없다.
- **경계 모호.** "결제는 누구 책임인가?"가 코드로 드러나지 않는다.

Phase 2는 이 경계를 **물리적으로** 긋는다. 결제 도메인과 결제 데이터를 payment-service로 옮기고,
order는 결제가 필요할 때 payment-service를 **호출**한다.

---

## 2. 핵심 개념 (초심자용)

### 2.1 서비스 경계 = 데이터 소유권 경계
MSA에서 "서비스를 나눈다"의 핵심은 **어느 서비스가 어떤 데이터를 소유하느냐**다.
Phase 2에서 결제 데이터(`payments` 테이블)의 소유권이 `orderdb` → `paymentdb`로 넘어간다.
order는 이제 결제 테이블을 **직접 못 본다.** 남의 데이터를 알고 싶으면 그 서비스의 API를 불러야 한다.

### 2.2 껍데기 분리(2-1) vs 실제 연결(2-2)
- **2-1**: payment-service를 **새로 만들되 order와는 아직 연결하지 않는다.** payment-service는
  단독으로 `POST /payments`를 받는 완성된 서비스지만, order는 여전히 (Phase 1의) 로컬 결제를 쓴다.
  = "새 집만 지어 두고 아직 이사 안 함."
- **2-2**: order의 **로컬 결제를 제거**하고 payment-service를 **원격 호출**로 바꾼다. `payments`
  테이블을 orderdb에서 drop하고 `orders.payment_id` 컬럼만 남긴다. = "실제 이사 + 옛 집 철거."

이렇게 두 단계로 쪼갠 이유: 신규 서비스 도입과 기존 서비스 리팩터링을 분리해 **각 커밋을 작게, 리뷰 가능하게** 유지.

### 2.3 동기 REST 호출 (블로킹)
order가 payment를 부르는 방식은 **동기(synchronous) HTTP 호출**이다. order는 요청을 보내고
**응답이 올 때까지 스레드가 대기**한다(블로킹). 이 프로젝트는 Spring의 `RestClient`를 쓴다.
(나중 Phase에서 Kafka 기반 **비동기 이벤트**로 바뀌지만, Phase 2는 가장 단순한 동기 호출부터 시작한다.)

### 2.4 분산 트랜잭션의 부재 (Phase 2의 핵심 교훈)
Phase 1에서는 재고 차감과 결제가 **같은 DB·같은 `@Transactional`** 안이라, 하나라도 실패하면
전부 롤백됐다(ACID). Phase 2에서 결제가 **원격**이 되는 순간 이 마법이 깨진다.
로컬 트랜잭션(재고+주문 저장)은 롤백할 수 있어도, **이미 성공한 원격 결제는 롤백되지 않는다.**
이 문제(고아 결제·원자성 소실·응답 유실)가 이 문서의 §6의 주제다.

---

## 3. 이 단계의 구성

```
[Phase 1] ─── 한 서비스, 한 트랜잭션 ────────────────
   ┌──────────────── order-service (8080) ─────────────────┐
   │  Order 애그리거트  +  Payment(내부)                     │
   │  재고차감 ─┐                                            │
   │           ├─ 하나의 @Transactional (ACID) ── orderdb   │
   │  결제캡처 ─┘                        (payments 테이블 포함)│
   └───────────────────────────────────────────────────────┘

[Phase 2] ─── 두 서비스, 트랜잭션 분리 ───────────────
   ┌───────── order-service (8080) ─────────┐        ┌──── payment-service (8081) ────┐
   │  Order 애그리거트 (paymentId 참조만)      │        │  Payment 애그리거트             │
   │  ┌ @Transactional(로컬) ───────────┐    │  HTTP  │  POST /payments → 캡처         │
   │  │ 재고 차감(비관적 락)              │    │───────▶│  가짜 PG(.99 거절)             │
   │  │ ── 여기서 원격 결제 호출 ─────────┼────┤  (동기) │  ┌ @Transactional(로컬) ─┐     │
   │  │ order 저장(paymentId)           │    │◀───────│  │ payments INSERT      │     │
   │  └────────────────────────────────┘    │ 201/402│  └──────────────────────┘     │
   │            orderdb (5432)               │  /502  │        paymentdb (5433)        │
   └─────────────────────────────────────────┘        └────────────────────────────────┘
        ↑ 로컬 트랜잭션과 원격 결제는 서로 다른 트랜잭션 = 글로벌 ACID 없음
```

- **order-service**: 여전히 재고를 소유(orderdb). 결제 결과는 `orders.payment_id`로만 보관.
- **payment-service**: 결제를 소유(paymentdb@5433). 가짜 PG 거절 규칙(`.99`)이 여기로 이동.
- 둘 사이는 **동기 HTTP**. 이때 order 주소는 아직 `http://localhost:8081`로 **하드코딩**
  (Phase 4에서 서비스 디스커버리 이름 `http://payment-service`로 대체됨).

---

## 4. 코드/설정 — 한 부분씩 해설

### 4.1 [2-1] payment-service 도메인 — 가짜 게이트웨이가 여기로 이사

`domain/Payment.java` — Phase 1에서 order 안에 있던 `.99` 거절 규칙이 그대로 옮겨왔다.
Phase 1과 달리 `orderId`를 갖고, id는 저장 시 채워지도록 nullable이다.

> **가짜 PG(Payment Gateway) stub이란?** PG는 카드사·간편결제 같은 **외부 결제 대행 서비스**를 말한다.
> 아직 실제 PG를 붙이지 않았으므로, 그 자리를 대신하는 자리표시자(stub)를 둔다 — 여기서는 "금액 소수부가
> `.99`면 거절, 아니면 성공"이라는 규칙 하나로 실제 PG의 승인/거절 응답을 흉내 낸다.

```java
public class Payment {
    /** 가짜 게이트웨이 stub: 합계가 .99로 끝나면 거절. 실제로는 외부 PG 호출이 들어갈 자리. */
    private static final BigDecimal DECLINE_REMAINDER = new BigDecimal("0.99");

    public static Payment capture(UUID orderId, BigDecimal amount) {
        if (orderId == null) throw new IllegalArgumentException("orderId must not be null");
        if (amount == null || amount.signum() <= 0)
            throw new IllegalArgumentException("amount must be positive: " + amount);
        if (amount.remainder(BigDecimal.ONE).compareTo(DECLINE_REMAINDER) == 0)
            throw new PaymentDeclinedException(amount);      // .99 → 거절
        return new Payment(null, orderId, amount, PaymentStatus.CAPTURED, Instant.now());
    }
}
```
> `remainder(BigDecimal.ONE)`은 정수부를 버리고 **소수부만** 남긴다(예: 9.99 → 0.99). 즉 금액의
> 소수부가 `0.99`면 거절한다. `.99`는 외부 PG 연동 전까지 **거절 경로를 테스트하려고 임의로 정한 트리거
> 값**일 뿐, 특별한 의미는 없다(마법 값 아님).
> `PaymentStatus`는 아직 `CAPTURED` 하나뿐이다(거절은 저장 전에 예외로 끝나므로). 환불(`REFUNDED`)은
> **Phase 12 Saga 보상**에서 추가될 예정이다.

### 4.2 [2-1] payment-service 인바운드 — `POST /payments`

`adapter/in/web/PaymentController.java`
```java
@RestController @RequestMapping("/payments")
class PaymentController {
    @PostMapping @ResponseStatus(HttpStatus.CREATED)      // 성공 → 201
    PaymentView capture(@Valid @RequestBody CapturePaymentRequest request) {
        return capturePaymentUseCase.capture(request.toCommand());
    }
}
```
`adapter/in/web/ApiExceptionHandler.java` — 상태코드 매핑(`ProblemDetail`: 에러 응답을 표준
형식으로 담는 스프링 타입. RFC 7807/9457 "Problem Details" JSON 규격을 따른다):
```java
@ExceptionHandler(PaymentDeclinedException.class)   // 거절 → 402 Payment Required
ProblemDetail handlePaymentDeclined(PaymentDeclinedException ex) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.PAYMENT_REQUIRED, ex.getMessage());
}
@ExceptionHandler(IllegalArgumentException.class)   // 금액 ≤ 0 등 → 400
ProblemDetail handleBadRequest(IllegalArgumentException ex) { ... }
```
> **402 Payment Required**를 거절 신호로 고른 게 중요하다. 아래 4.6에서 order가 이 402를 잡아
> `PaymentDeclinedException`으로 되살린다.

### 4.3 [2-1] payment-service 영속 & 설정 — 자기 DB `paymentdb@5433`

`adapter/out/persistence/PaymentJpaEntity.java` — id는 **DB/JPA가 생성**:
```java
@Id @GeneratedValue(strategy = GenerationType.UUID)   // payment가 paymentId를 발급
private UUID id;
```
`resources/db/migration/V1__init.sql`:
```sql
-- Phase 2: payment-service가 결제를 소유한다(order-service에서 분리).
CREATE TABLE payments ( id UUID PRIMARY KEY, order_id UUID NOT NULL,
    amount NUMERIC(12,2) NOT NULL, status VARCHAR(20) NOT NULL, captured_at TIMESTAMPTZ NOT NULL );
CREATE INDEX idx_payments_order_id ON payments (order_id);
```
`resources/application.yml` — **다른 포트, 다른 DB**:
```yaml
spring:
  application: { name: payment-service }
  datasource: { url: jdbc:postgresql://localhost:5433/paymentdb, username: payment, password: paymentpw }
server: { port: 8081, shutdown: graceful }
```
`deploy/compose/compose.infra.yml`에 `payment-db` 컨테이너 추가(호스트 포트 `5433:5432`) — order-db(5432)와 **물리적으로 분리**.

### 4.4 [2-2] order의 로컬 결제 제거 — 마이그레이션 V3

`order-service/.../db/migration/V3__remove_local_payment.sql`
```sql
-- Phase 2: 결제를 payment-service로 분리. order-service는 paymentId(참조)만 보관하고
-- 로컬 payments 테이블을 제거한다.
ALTER TABLE orders ADD COLUMN payment_id UUID;
DROP TABLE payments;
```
동시에 order 코드에서 `domain/Payment.java`, `domain/PaymentStatus.java`, 그리고 로컬
`PaymentJpaEntity`가 **삭제**된다. 결제 도메인이 통째로 payment-service로 이주했기 때문이다.

### 4.5 [2-2] 주문 id를 앱에서 생성 — 왜?

`domain/Order.java`
```java
public static Order create(UUID customerId) {
    return new Order(UUID.randomUUID(), ...);   // ← 저장 전에 id 확정(앱 생성)
}
/** 원격 결제 성공 후 주문 확정. payment-service가 발급한 paymentId를 보관. */
public void confirm(UUID paymentId) {
    if (status != OrderStatus.PENDING) throw new IllegalStateException(...);
    this.paymentId = paymentId;
    this.status = OrderStatus.CONFIRMED;
}
```
**핵심 이유:** payment-service를 호출할 때 `orderId`를 넘겨줘야 하는데, 주문을 아직 DB에 저장하지
않은 상태다. DB가 id를 발급하는 방식(`@GeneratedValue`)이면 저장 전에 id가 없다. 그래서 order는
**애플리케이션에서 UUID를 미리 만든다.**

> **그 대가:** Spring Data JPA의 `save()`는 엔티티에 id가 **이미 있으면** 신규인지 확신하지 못해,
> INSERT 전에 SELECT로 존재 여부를 한 번 확인한다(이를 JPA의 **merge 경로**라 한다. 반대로 id가
> 비어 있으면 곧장 INSERT하는 `persist` 경로). 즉 앱에서 id를 미리 만든 대가로 신규 저장에도
> SELECT 1회가 더 붙는다 — 정확성엔 문제없고 약간의 오버헤드일 뿐(`OrderJpaEntity` 주석에 명시).

### 4.6 [2-2] 아웃바운드 포트 + RestClient 어댑터 — 헥사고날의 정석

**포트(애플리케이션 계층, 기술 무관)** `application/port/out/PaymentGatewayPort.java`
```java
/** 아웃바운드 포트: 결제 게이트웨이(원격 payment-service). 통신 수단(REST 등)은 어댑터의 구현 세부.
 *  성공 시 paymentId 반환. 거절 → PaymentDeclinedException, 통신 실패 → PaymentGatewayException. */
public interface PaymentGatewayPort {
    UUID capture(UUID orderId, BigDecimal amount);
}
```
**어댑터(인프라 계층, RestClient 사용)** `adapter/out/payment/PaymentGatewayRestAdapter.java`
```java
PaymentResponse response = paymentRestClient.post().uri("/payments")
    .contentType(MediaType.APPLICATION_JSON)
    .body(new PaymentRequest(orderId, amount))
    .retrieve()
    .onStatus(s -> s.value() == HttpStatus.PAYMENT_REQUIRED.value(),   // 402 →
        (req, res) -> { throw new PaymentDeclinedException(amount); }) //   거절 예외
    .body(PaymentResponse.class);
// ...
} catch (PaymentDeclinedException e) {
    throw e;                                        // 거절은 그대로 위로
} catch (RestClientException e) {
    // 연결 거부·타임아웃·4xx/5xx 등 → 통신 실패로 변환(주문 트랜잭션 롤백 유발)
    throw new PaymentGatewayException("payment-service 호출 실패: " + e.getMessage(), e);
}
```
> **왜 포트/어댑터로 나눴나:** 애플리케이션(OrderService)은 "결제해줘"라는 **의도**(`PaymentGatewayPort`)만
> 안다. "그게 REST냐 gRPC냐 Kafka냐"는 어댑터의 세부사항이다. 나중에 통신 방식이 바뀌어도
> OrderService는 그대로다. 이게 헥사고날 아키텍처가 원격 호출을 다루는 방식이다(→ HEXAGONAL.md).

`adapter/out/payment/PaymentClientConfig.java` — baseUrl은 **설정값**:
```java
@Bean
RestClient paymentRestClient(RestClient.Builder builder, @Value("${payment.service.url}") String baseUrl) {
    return builder.baseUrl(baseUrl).build();   // Phase 4에서 디스커버리로 대체
}
```
`order-service/application.yml`:
```yaml
payment:
  service:
    url: http://localhost:8081   # Phase 2: 하드코딩. Phase 4에서 서비스 디스커버리로 대체.
```
> ⚠️ **이 하드코딩 주소가 Phase 4에서 사라진다.** `@LoadBalanced RestClient.Builder` + `http://payment-service`
> (Eureka 이름 해석)로 바뀐다. Phase 2에서는 아직 디스커버리가 없으니 host:port를 직접 적는다.

### 4.7 [2-2] order의 에러 매핑 — 402는 402로, 통신실패는 502로

`adapter/in/web/ApiExceptionHandler.java`
```java
@ExceptionHandler(PaymentDeclinedException.class)   // 원격 결제 거절 → 402
ProblemDetail handlePaymentDeclined(...) { return ProblemDetail.forStatusAndDetail(PAYMENT_REQUIRED, ...); }

@ExceptionHandler(PaymentGatewayException.class)    // payment-service 통신 실패 → 502
ProblemDetail handleGatewayError(...) { return ProblemDetail.forStatusAndDetail(BAD_GATEWAY, ...); }
```
> **의미 구분:** 402는 "결제 자체가 거절됨(비즈니스 결과)", 502는 "payment-service에 못 닿음/응답
> 이상(인프라 문제)". 클라이언트는 이 둘에 다르게 대응해야 하므로 상태코드를 분리한다.

---

## 5. 요청 흐름 (`POST /orders` 한 방)

```
1) 클라이언트 ──POST :8080/orders──▶ order-service
2) OrderService.placeOrder() 시작 [로컬 @Transactional 열림]
3) Order.create() → 앱에서 orderId(UUID) 생성
4) 재고 예약(비관적 락) — 상품ID 정렬로 교착 회피 (로컬 트랜잭션 안)
5) paymentGatewayPort.capture(orderId, totalAmount)
      → RestClient: POST http://localhost:8081/payments  {orderId, amount}   [블로킹 대기]
6) payment-service: Payment.capture() → .99면 402 / 정상이면 payments INSERT → 201 {paymentId}
7-a) 성공: order.confirm(paymentId) → order 저장 → [로컬 트랜잭션 커밋] → 201 CONFIRMED
7-b) 402 거절: PaymentDeclinedException → [로컬 롤백: 재고 원복] → 402
7-c) 통신 실패: PaymentGatewayException → [로컬 롤백: 재고 원복] → 502
```

- **4번(재고)과 6번(결제)은 서로 다른 트랜잭션**이다. 5번의 원격 호출 동안 order 스레드는 대기하고,
  그동안 **재고 비관적 락이 계속 잡혀 있다**(원격 지연만큼 락 점유 → 처리량 저하).
- 7-b/7-c에서 재고는 **로컬 트랜잭션이라 롤백**되지만, 만약 6번이 성공한 뒤 7-a의 order 저장이
  실패하면? → §6의 고아 결제 문제.

> **비관적 락(pessimistic lock)이란?** 재고 행을 읽을 때 DB 레벨에서 그 행을 잠가 다른 트랜잭션이
> 못 건드리게 하는 방식이다(Phase 1에서 도입, 상세는 PHASE-1-MONOLITH.md). 락이 잡혀 있는 동안 같은
> 상품을 사려는 다른 주문은 **대기**하므로, 5번의 원격 결제가 느리면 그만큼 락이 오래 점유돼
> **처리량이 떨어진다** — 이것이 §6에서 말하는 "동기 블로킹의 비용"이다.

---

## 6. 동작 원리 더 깊게 / 트레이드오프 — 사라진 ACID

Phase 2의 진짜 학습은 코드가 아니라 **분산 트랜잭션이 없다는 사실**이다.
`OrderService`의 주석이 이 위험을 코드에 박아 두었다.

```java
// (2) 결제 = 원격 호출(payment-service). ⚠️ 단일 트랜잭션 소멸: 결제는 이 로컬 @Transactional 에 묶이지 않는다.
//     · 재고 비관적 락이 이 원격 호출 구간 내내 유지됨(원격 지연만큼 락 점유 → 처리량 저하).
//     · 결제 성공 후 (3) 저장이 실패하면 결제는 원격에 남아 자동 원복 불가(= orphaned payment).
//     이 잃어버린 원자성이 Phase 12 Saga(보상 트랜잭션)의 동기다.
UUID paymentId = paymentGatewayPort.capture(order.getId(), order.getTotalAmount());
order.confirm(paymentId);
```

세 가지 구멍:

1. **고아 결제(orphaned payment) / 원자성 소실.** payment-service가 결제를 **커밋**한 직후,
   order-service가 order를 저장하다 죽으면(DB 다운, JVM 크래시 등) → paymentdb에는 결제가 남지만
   orderdb에는 주문이 없다. 로컬 트랜잭션 롤백은 **원격 결제를 되돌리지 못한다.** 돈은 잡혔는데 주문은 없는 상태.
2. **응답 유실의 모호성(마지막 확인 불가 문제).** 5번 호출이 타임아웃 났을 때 order는
   "결제가 안 된 건가, 됐는데 응답만 못 받은 건가"를 **구분할 수 없다.** 실패로 보고 재고를
   롤백했지만, 실제로는 결제가 성공했을 수 있다(→ 또 다른 고아 결제).
3. **중복 결제 위험.** 위 2번에서 order가 "실패했으니 다시"라며 재시도하면, payment는 같은 주문에
   결제를 두 번 만들 수 있다(멱등성 부재).

**Phase 2에서는 이걸 일부러 해결하지 않는다.** 대신 문제를 **드러내는** 게 목적이다.
- 정상/거절/통신실패의 **행복하지 않은 경로**는 로컬 트랜잭션 롤백으로 최대한 방어(재고 원복).
- 하지만 "결제 성공 후 저장 실패", "응답 유실"은 **근본적으로 로컬 트랜잭션으로 못 막는다.**

> **트레이드오프 요약:** 서비스 경계를 얻은 대가로 글로벌 ACID를 잃었다. 이건 MSA의 본질적 비용이지
> 버그가 아니다. 해결은 "분산 트랜잭션을 되살리기"가 아니라 "**최종 일관성 + 보상**"으로 방향을 튼다.

---

## 7. 검증 (그 당시 어떻게 확인했나)

**빌드/테스트**
```bash
./gradlew :services:payment-service:build     # 2-1: 새 서비스 단독 빌드
./gradlew :services:order-service:test        # 2-2: StockConcurrencyTest 등
```
> 테스트에서 실제 payment-service를 띄우지 않으려고, 아웃바운드 포트 구현을 **가짜(mock, 테스트 더블)**
> 로 갈아끼운다. `@MockitoBean`은 스프링 컨텍스트의 특정 빈을 목 객체로 교체하는 애너테이션이고,
> **목(mock)** 은 정해진 값을 돌려주도록 흉내 낸 가짜 구현이다. 즉 `StockConcurrencyTest`는 원격 결제를
> 실제로 부르는 대신 **`@MockitoBean PaymentGatewayPort`** 로 항상 성공
> (`when(paymentGateway.capture(any(), any())).thenReturn(UUID.randomUUID())`)하게 두고 **재고 동시성에만
> 집중**한다. §4.6처럼 포트/어댑터로 나눠 둔 덕에 이런 교체가 쉽다. `webEnvironment=NONE`(비-웹) 테스트다.

**수동 스모크 (curl)** — 인프라 먼저: `docker compose -f deploy/compose/compose.infra.yml up -d`

```bash
# (A) payment-service 단독 (2-1): 정상 캡처 → 201 + paymentId
curl -i -X POST http://localhost:8081/payments -H 'Content-Type: application/json' \
  -d '{"orderId":"11111111-1111-1111-1111-111111111111","amount":10.00}'
# (B) 가짜 PG 거절: 합계 .99 → 402 Payment Required
curl -i -X POST http://localhost:8081/payments -H 'Content-Type: application/json' \
  -d '{"orderId":"11111111-1111-1111-1111-111111111111","amount":9.99}'

# (C) order → payment 원격 연결 (2-2): 주문 성공 → 201 CONFIRMED + payment_id
curl -i -X POST http://localhost:8080/orders -H 'Content-Type: application/json' \
  -d '{"customerId":"...","items":[{"productId":"...","quantity":1,"unitPrice":10.00}]}'
# (D) 결제 거절 경로: unitPrice로 합계를 .99로 → order가 402 반환 + 재고 롤백
# (E) payment-service를 끈 상태로 (C) → order가 502 Bad Gateway + 재고 롤백
```
> 핵심 확인: (D)에서 **재고가 원복**됐는지(로컬 롤백), (E)에서 502가 나오는지(통신 실패 매핑),
> 그리고 orderdb의 `orders.payment_id`가 payment-service가 발급한 값과 일치하는지.

---

## 8. 알려진 한계 → 해결 Phase

| 한계 / 트레이드오프 | 성격 | 해결 Phase |
|---|---|---|
| **고아 결제** — 원격 결제 성공 후 order 저장 실패 시 결제만 남음(자동 원복 불가) | 원자성 소실 | **Phase 12** (Saga 보상 = 환불) |
| **응답 유실 모호성** — 타임아웃 시 결제 성공/실패 구분 불가 | 최종 확인 불가 | **Phase 10** (outbox+멱등) / **13** (타임아웃 sweep) |
| **중복 결제 위험** — 재시도 시 같은 주문 결제 2번 가능 | 멱등성 부재 | **Phase 10** (멱등 소비자·messageId) |
| **동기 블로킹 호출** — 원격 지연 동안 재고 락 점유 → 처리량 저하 | 결합/성능 | **Phase 12** (이벤트 비동기화), **14** (서킷·타임아웃) |
| **결제 주소 하드코딩** `http://localhost:8081` | 배치 결합 | **Phase 4** (`http://payment-service`, Eureka) |
| **무인증 평문 호출** — 누구나 `/payments` 직접 호출 가능 | 보안 | **Phase 5** (JWT 리소스 서버·토큰 전파) |
| **단일 진입점 없음** — 클라가 8080/8081을 각각 앎 | 라우팅 | **Phase 3** (API Gateway) |
| **분산 추적 없음** — order→payment 흐름 추적 불가 | 관측성 | **Phase 8** (관측성) |

---

## 9. 용어 사전

- **애그리거트(aggregate) / 애그리거트 루트**: 하나의 트랜잭션·불변식 단위로 함께 다뤄지는 도메인 객체 묶음, 그리고 그 대표(진입점)가 루트. Phase 1에선 Order가 루트, Payment가 그 안. DDD 용어(→ HEXAGONAL.md).
- **데이터 소유권(data ownership)**: 한 테이블/데이터를 오직 한 서비스만 읽고 쓰는 원칙. Phase 2에서 결제 소유권이 payment로 이전.
- **database-per-service**: 서비스마다 자기 DB를 갖는 규칙(orderdb 5432 / paymentdb 5433).
- **동기 호출(synchronous)**: 응답이 올 때까지 호출 스레드가 대기(블로킹)하는 호출. 여기선 `RestClient` HTTP.
- **아웃바운드 포트(outbound port)**: 애플리케이션이 바깥(다른 서비스·DB)에 뭔가 요청할 때 쓰는 기술 무관 인터페이스(`PaymentGatewayPort`).
- **어댑터(adapter)**: 포트의 구현. 기술 세부(RestClient)를 담당(`PaymentGatewayRestAdapter`).
- **글로벌 ACID / 분산 트랜잭션**: 여러 서비스에 걸친 하나의 원자적 트랜잭션. MSA에선 사실상 포기.
- **고아 결제(orphaned payment)**: 주문 없이 홀로 남은 결제. 원자성 소실의 결과.
- **보상 트랜잭션(compensation)**: 롤백 대신 "반대 동작"(예: 환불)으로 되돌리기. 롤백 ≠ 보상. (Phase 12)
- **멱등성(idempotency)**: 같은 요청을 여러 번 해도 결과가 한 번과 같음. 중복 결제 방지의 열쇠. (Phase 10)
- **402 Payment Required / 502 Bad Gateway**: 각각 "결제 거절(비즈니스)" / "상류 서비스 통신 실패(인프라)".
- **비관적 락(pessimistic lock)**: 행을 읽을 때 DB 레벨에서 잠가 다른 트랜잭션이 못 건드리게 하는 방식. 락이 잡힌 동안 다른 요청은 대기 → 원격 지연이 길면 처리량 저하(Phase 1 도입, → PHASE-1-MONOLITH.md).
- **테스트 더블 / 목(mock)**: 테스트에서 실제 협력 객체 대신 끼우는 가짜 구현. 목은 정해진 값을 돌려주도록 흉내 낸다. 여기선 `@MockitoBean`으로 `PaymentGatewayPort`를 교체(§7).
- **JPA persist vs merge**: `persist`는 id 없는 신규를 곧장 INSERT, `merge`는 id가 이미 있어 신규 확신이 안 될 때 INSERT 전 SELECT로 존재 확인. 앱에서 id를 만들면 `save()`가 merge 경로가 된다(§4.5).
- **가짜 PG(Payment Gateway) stub**: 외부 결제 대행(PG) 자리를 대신하는 자리표시자. 여기선 `.99` 거절 규칙 하나로 승인/거절을 흉내 냄. 실제 연동은 이후 Phase 대상.
- **`ProblemDetail` / RFC 7807(=RFC 9457)**: HTTP 에러 응답을 표준 JSON 형식으로 담는 규격과 그 스프링 타입. `type/title/status/detail` 필드를 가진다.
- **`BigDecimal.remainder`**: 나눗셈의 나머지. `remainder(BigDecimal.ONE)`은 1로 나눈 나머지 = 소수부만 추출(9.99 → 0.99).
- **RestClient 플루언트 API**: `post().uri(...).body(...).retrieve().onStatus(...).body(...)`처럼 메서드를 이어붙여(체이닝) HTTP 요청을 조립하는 스프링 방식. `onStatus`로 특정 상태코드를 잡아 예외로 바꾼다(§4.6).

---

## 10. 더 알아보기

- Spring `RestClient`: https://docs.spring.io/spring-framework/reference/integration/rest-clients.html#rest-restclient
- Microservices 데이터 소유(Database per Service): https://microservices.io/patterns/data/database-per-service.html
- Saga 패턴(보상 트랜잭션): https://microservices.io/patterns/data/saga.html
- 트랜잭셔널 Outbox: https://microservices.io/patterns/data/transactional-outbox.html
- RFC 9110 — HTTP 상태코드(402/502): https://www.rfc-editor.org/rfc/rfc9110

---

*관련 문서: [HEXAGONAL.md](HEXAGONAL.md)(포트/어댑터 아키텍처), [SERVICE-DISCOVERY.md](SERVICE-DISCOVERY.md)(Phase 4 — 하드코딩 주소를 이름으로), [SECURITY.md](SECURITY.md)(Phase 5 — 서비스 간 호출 보안). 전체 로드맵: 루트 `MSA-LEARNING-PLAN.md`.*
