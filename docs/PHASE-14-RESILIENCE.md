# Phase 14 — 복원력 패턴 (일부러 부수고, 버티는지 본다)

> **한 줄 요약:** 지금까지는 "모두 살아 있을 때"의 정합성을 만들었다. 이번엔 **일부가 죽거나 느려졌을 때**
> 나머지가 살아남게 한다. 동기 구간은 **Resilience4j 5종**으로, 비동기 구간은 **유한 재시도 + DLQ**로 막는다.
> 그리고 Phase 13이 실측으로 남긴 결함 — **고아 결제** — 를 보상으로 갚는다.

초심자(Java/Spring은 알지만 분산 장애 대응은 처음) 기준으로 **왜 → 무엇을 → 어떻게** 순서로 설명합니다.

---

## 0. 이번 단계에서 한 일 (요약)

- **Resilience4j 5종**(RateLimiter·TimeLimiter·CircuitBreaker·Bulkhead·Retry)을 order→inventory **재고 사전 확인** 호출에 적용.
- **게이트웨이 회로차단기 + fallback** — 다운스트림이 죽어도 엣지에서 즉시 503으로 끊고, 회복되면 스스로 닫힌다.
- **엣지 과부하 차단** — Resilience4j RateLimiter/Bulkhead 를 게이트웨이 글로벌 필터로(429/503).
- **장애 주입 스위치**(`POST /inventory/chaos`) — 재시작 없이 지연·오류율을 바꿔 패턴을 눈으로 확인.
- **DLQ / poison 메시지** — `ErrorHandlingDeserializer` + `DefaultErrorHandler` + `DeadLetterPublishingRecoverer`
  → 유한 백오프 후 `<토픽>.DLT`. **파티션이 막히지 않는다.**
- **outbox 격리** — 발행이 계속 실패하는 row 는 `attempts` 상한을 넘기면 조회에서 제외(+ `outbox.stuck` 게이지).
- **★ 고아 결제 보상** — Phase 13에서 발견한 "취소된 주문에 결제만 남는" 결함을 `RefundPaymentCommand` 로 상쇄.

---

## 1. 왜 — 지금까지 쌓인 문제

Phase 13까지 만든 것은 **논리적 정합성**이다. 하지만 실제 장애에서는 아래가 그대로 드러났다.

**① 소비 중 예외 = 무한 재배달 = 파티션 정지.**
Kafka 소비자는 "처리 성공"으로만 오프셋을 넘긴다. 리스너가 예외를 던지면 **같은 레코드가 영원히 다시 온다**.
그 파티션 뒤에 줄 선 정상 메시지는 전부 함께 멈춘다(**head-of-line blocking**).
깨진 메시지 하나가 서비스를 마비시키는 것 — 이런 메시지를 **poison pill**(독약)이라 부른다.

**② 발행이 안 되는 outbox row 도 같은 문제.**
릴레이는 미발행 row 를 오래된 순서로 100건씩 집어 온다. 구조적으로 발행 불가한 row 가 있으면
매 폴링마다 앞자리를 차지해 **뒤의 정상 이벤트가 굶는다**. 소비 쪽 poison pill 의 발행 쪽 쌍둥이다.

**③ 죽은 의존성을 계속 때린다.**
게이트웨이는 order-service 가 죽어도 매 요청을 그쪽으로 보내고 타임아웃까지 기다린다.
요청이 쌓이면 커넥션/스레드가 고갈되고, **멀쩡한 다른 라우트까지 함께 죽는다**(캐스케이드 장애).

**④ Phase 13이 남긴 실측 결함 — 고아 결제.**
타임아웃 sweep 이 결제를 포기하고 주문을 취소한 뒤, 되살아난 payment 가 큐에 남아 있던
`ChargePayment` 를 뒤늦게 수행한다 → **취소된 주문에 결제만 살아남는다**
(`docs/PHASE-13-SAGA-ORCHESTRATION.md` §7.2에 기록).

---

## 2. 핵심 개념 (용어부터)

| 용어 | 뜻 | 없으면 생기는 일 |
|---|---|---|
| **Circuit Breaker**(회로차단기) | 실패율이 임계를 넘으면 회로를 **열어** 이후 호출을 즉시 실패시킨다. 일정 시간 뒤 스스로 시험 호출(HALF_OPEN). | 죽은 상대를 계속 때리며 매 요청이 타임아웃까지 대기 |
| **Retry**(재시도) | 일시적 결함을 몇 번 다시 시도. **반드시 유한하게 + 백오프**. | 한 번 튄 오류가 그대로 실패 / 무한 재시도는 장애를 감춤 |
| **Bulkhead**(격벽) | **동시** 호출 수 상한. 배의 격벽처럼 침수를 한 칸에 가둔다. | 느린 다운스트림 하나가 전체 스레드를 잠식 |
| **RateLimiter** | **초당** 호출 수 상한. | 우리가 상대를 눌러 죽인다 |
| **TimeLimiter** | 응답 시간 상한. | 느린 상대에게 스레드가 묶여 우리도 죽는다 |
| **Fallback**(강등) | 실패 시 돌려줄 **정직한 대체 응답**. | 부가 기능 실패가 본 기능을 끌어내린다 |
| **DLQ / DLT** | 끝내 처리 못 한 메시지를 옮겨 두는 토픽(`<원본>.DLT`). | poison pill 이 파티션을 영구 정지 |
| **Poison pill** | 몇 번을 다시 해도 반드시 실패하는 메시지(깨진 JSON 등). | — |
| **head-of-line blocking** | 앞의 하나가 막혀 뒤가 전부 대기하는 현상. | — |

> 💡 **핵심 감각:** 복원력 패턴은 "실패를 없애는" 장치가 아니다. **실패를 빨리, 국소적으로 끝내는** 장치다.
> 목표는 "죽지 않는 시스템"이 아니라 "**한 곳의 죽음이 전체로 번지지 않는 시스템**"이다.

---

## 3. 구성 (그림)

```
                    ┌──────────────── 엣지(게이트웨이) ────────────────┐
  클라이언트 ──▶   │ RateLimiter(20/s) → Bulkhead(20) → CircuitBreaker │ ──▶ lb://order-service
                    │      429              503            503(fallback) │
                    └──────────────────────────────────────────────────┘
                                                │
                                                ▼
  order-service ── POST /orders ─┬─ [사전 확인] ──▶ inventory-service  (동기·부가 기능)
                                 │     RateLimiter → TimeLimiter → CircuitBreaker
                                 │     → Bulkhead → Retry → RestClient
                                 │     실패하면 UNKNOWN 으로 강등(주문은 계속 진행)
                                 │
                                 └─ 주문 저장 + outbox 기록(한 트랜잭션) ──▶ Saga(비동기)

  [비동기 구간의 방어]
   발행: OutboxRelay ─ 실패 시 attempts++ ─ 상한(5) 초과 → 조회에서 제외(격리) + outbox.stuck 게이지
   소비: ErrorHandlingDeserializer → DefaultErrorHandler
           ├ 일시적 결함  → 지수 백오프 재시도(최대 3회)
           └ 역직렬화 실패 → 재시도 없이 즉시 <토픽>.DLT
```

**Phase 12에서 없앤 동기 호출을 왜 한 군데 다시 넣었나?**
동기 호출은 그 자체가 나쁜 게 아니라 **필수 경로에 있을 때** 나쁘다.
재고 사전 확인은 "지금 화면에 보여 줄 힌트"일 뿐 — 실패해도 Saga 가 정답을 보장한다.
**필수가 아닌 호출 + fallback** 이라는 조건에서 동기 호출이 어떻게 허용되는지를 몸으로 배우는 자리다.

---

## 4. 코드·설정 한 부분씩

### 4.1 복원력 5종을 한 메서드에 (`InventoryStockClient`)

```java
@RateLimiter(name = "inventory")
@TimeLimiter(name = "inventory")
@CircuitBreaker(name = "inventory")
@Bulkhead(name = "inventory")
@Retry(name = "inventory")
CompletableFuture<Integer> availableQuantity(UUID productId, String bearerToken) {
    return CompletableFuture.supplyAsync(() -> fetch(productId, bearerToken), stockPrecheckExecutor);
}
```

- **`CompletableFuture` 반환이 필수다** — `@TimeLimiter` 는 동기 메서드에 걸 수 없다(끊을 방법이 없으므로).
- **토큰을 인자로 받는다** — 본문은 **다른 스레드**에서 돌기 때문에 `SecurityContextHolder` 가 비어 있다.
- **예외를 삼키지 않는다** — 여기서 잡으면 회로차단기가 실패를 배우지 못한다.

### 4.2 fallback 은 애너테이션이 아니라 옆 클래스에서 (`StockAvailabilityRestAdapter`)

```java
try {
    int available = client.availableQuantity(line.productId(), bearerToken).join();
    return available >= line.quantity() ? StockPrecheck.available() : StockPrecheck.insufficient(...);
} catch (Exception e) {
    return StockPrecheck.unknown(describe(unwrap(e)));   // ★ 강등: 주문은 그대로 진행
}
```

**클래스를 둘로 나눈 이유 두 가지 — 둘 다 실수하기 쉬운 지점이다.**

1. **Spring AOP 는 자기 자신 호출에 적용되지 않는다.** 같은 클래스 안에서 `this.availableQuantity(...)` 를
   부르면 프록시를 거치지 않아 **애너테이션이 전부 무시된다**. 복원력 코드가 "조용히 아무것도 안 하는" 대표적 사고.
2. **`fallbackMethod` 를 안쪽 aspect 에 달면 안 된다.** 예를 들어 `@Retry` 에 fallback 을 달면
   바깥의 회로차단기는 언제나 "성공"만 보게 되어 **회로가 영원히 열리지 않는다.**

> ⚠️ **실측으로 밟은 함정:** 강등 사유를 한글("회로 열림")로 만들어 응답 헤더에 실었더니 **헤더가 통째로 사라졌다.**
> HTTP 헤더는 기본이 ISO-8859-1 이다. 그래서 헤더에 실리는 값은 `CIRCUIT_OPEN`·`TIMEOUT` 같은 **ASCII**로만 만들고,
> 한글 설명은 로그에만 남긴다.

### 4.3 ★ aspect 적용 순서 — 문서만 믿으면 틀린다

애너테이션을 **쓴 순서는 아무 의미가 없다.** 실제 중첩은 각 aspect 의 order 값으로 정해진다.
Resilience4j 2.2.0 클래스를 직접 뜯어 확인한 기본값은 다음과 같다(`Integer.MAX_VALUE` = 2147483647).

| aspect | 기본 order | 설정 키 | 변경 가능? |
|---|---|---|---|
| Retry | 2147483642 | `resilience4j.retry.retryAspectOrder` | ✅ |
| CircuitBreaker | 2147483643 | `resilience4j.circuitbreaker.circuitBreakerAspectOrder` | ✅ |
| RateLimiter | 2147483644 | `resilience4j.ratelimiter.rateLimiterAspectOrder` | ✅ |
| TimeLimiter | 2147483645 | `resilience4j.timelimiter.timeLimiterAspectOrder` | ✅ |
| **Bulkhead** | **2147483646** | (없음 — getter 만 존재) | ❌ **고정** |

- **값이 작을수록 바깥**이다(스프링 AOP 표준: 낮은 order = 높은 우선순위 = 바깥).
  Resilience4j 문서의 *"higher value = higher priority"* 표현은 오해를 부른다 — 실제로 5·4·3·2·1 로 넣으면
  의도와 **정반대**로 중첩된다.
- 그래서 기본 중첩은 `Retry(CircuitBreaker(RateLimiter(TimeLimiter(Bulkhead(호출)))))` — **Retry 가 가장 바깥**.
- **`bulkheadAspectOrder` 는 아예 없다.** 넣으면 `No setter found for property` 로 **애플리케이션이 기동조차 못 한다**
  (실제로 게이트웨이가 이걸로 재시작 루프에 빠졌다).

이 프로젝트는 학습 계획의 권장 순서 `RateLimiter → TimeLimiter → CircuitBreaker → Bulkhead → Retry`(바깥→안쪽)로 맞췄다:

```yaml
resilience4j:
  ratelimiter:     { rateLimiterAspectOrder: 2147483643 }   # 가장 바깥
  timelimiter:     { timeLimiterAspectOrder: 2147483644 }
  circuitbreaker:  { circuitBreakerAspectOrder: 2147483645 }
  # bulkhead: 2147483646 (고정)
  retry:           { retryAspectOrder: 2147483647 }         # 가장 안쪽
```

**이 순서가 바꾸는 것 (§7.1에서 실측 확인):**

- Retry 가 가장 안쪽 → 회로차단기는 "재시도까지 끝낸 최종 결과" **1건**만 센다. 재시도가 통계를 부풀리지 않는다.
- TimeLimiter 가 회로차단기보다 **바깥** → **타임아웃은 회로를 열지 못한다.**
  느린 상대에게는 매번 타임아웃만 나고 회로는 CLOSED 로 남는다.
  타임아웃도 회로 판단에 넣고 싶으면 `circuitBreakerAspectOrder` 를 `timeLimiterAspectOrder` 보다 **작게**(더 바깥) 두면 된다.
  (또는 회로차단기의 `slow-call-duration-threshold` 로 "느린 호출"을 실패로 세는 방법도 있다.)

### 4.4 게이트웨이 — 회로차단기 필터 + fallback

```yaml
- id: orders-route
  uri: lb://order-service
  filters:
    - name: CircuitBreaker
      args: { name: ordersCb, fallbackUri: forward:/fallback/order-service }
```

```java
factory.configureDefault(id -> new Resilience4JConfigBuilder(id)
        .circuitBreakerConfig(CircuitBreakerConfig.custom()
                .slidingWindowSize(10).minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(3).build())
        .timeLimiterConfig(TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofSeconds(3)).build())   // 기본 1초는 쓰기에 너무 빡빡
        .build());
```

- **회로 이름을 라우트마다 다르게** 둔다. 하나로 묶으면 order 장애가 inventory 회로까지 연다.
- **기본 TimeLimiter 가 1초**라는 점을 모르면 "이유 없는 간헐적 503"에 시달린다. 명시하는 편이 안전하다.
- fallback 은 **정직한 실패**(503 + ProblemDetail)여야 한다. 성공인 척하면 클라이언트가 더 위험해진다.

### 4.5 엣지 과부하 차단 (`EdgeThrottleFilter`)

```java
return chain.filter(exchange)
        .transformDeferred(BulkheadOperator.of(bulkhead))
        .transformDeferred(RateLimiterOperator.of(rateLimiter))
        .onErrorResume(RequestNotPermitted.class,  e -> reject(exchange, TOO_MANY_REQUESTS, ...))
        .onErrorResume(BulkheadFullException.class, e -> reject(exchange, SERVICE_UNAVAILABLE, ...));
```

Spring Cloud Gateway 기본 `RequestRateLimiter` 필터는 **Redis 를 요구**한다. 학습 플랫폼에 Redis 를 들이는 대신
Resilience4j 리액터 연산자를 직접 붙였다.
⚠️ 대신 **인스턴스별 로컬 카운터**다 — 게이트웨이를 2개 띄우면 한도도 2배. 분산 한도가 필요하면 Redis 기반이 맞다.

### 4.6 소비 실패 → 유한 재시도 → DLQ (`shared/messaging`)

```java
ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(3);   // 유한!
DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
```

```yaml
spring.kafka.consumer:
  key-deserializer:   org.springframework.kafka.support.serializer.ErrorHandlingDeserializer
  value-deserializer: org.springframework.kafka.support.serializer.ErrorHandlingDeserializer
  properties:
    spring.deserializer.value.delegate.class: org.springframework.kafka.support.serializer.JsonDeserializer
```

**꼭 알아야 하는 네 가지**

1. **`ErrorHandlingDeserializer` 로 감싸지 않으면 에러 핸들러가 손도 못 댄다.** 역직렬화 예외는
   리스너에 **도달하기 전** 컨슈머 스레드에서 터지기 때문이다. 감싸면 `DeserializationException` 으로 바뀌어
   `DefaultErrorHandler` 의 **비재시도 목록**에 걸리고 즉시 DLT 로 간다.
2. **DLT 토픽을 선언해야 한다.** auto-create 가 꺼져 있으므로 없으면 **DLT 발행이 실패**해 결국 파티션이 다시 막힌다.
   여러 개를 한 빈으로 낼 땐 `KafkaAdmin.NewTopics` 를 쓴다 — `List<NewTopic>` 빈은 수집되지 않는다.
3. **DLT 발행용 템플릿이 둘이다.** 역직렬화 실패 레코드는 자바 객체가 아니라 **원본 바이트**로 실어야 원문을 볼 수 있다.
   `JsonSerializer` 로는 byte[] 가 base64 문자열로 뭉개진다.
   ⚠️ 그 템플릿을 `@Bean` 으로 내면 안 된다 — Boot 의 `KafkaTemplate` 자동설정이 `@ConditionalOnMissingBean` 이라
   **자동설정 템플릿이 통째로 사라진다**(→ outbox 릴레이 주입 실패).
4. **`messaging.dlq.enabled=false` 토글이 필요하다.** Kafka 자동설정을 제외한 컨텍스트(브로커 없는 통합 테스트)에는
   `KafkaTemplate` 자체가 없어 이 설정이 기동을 깨뜨린다.

### 4.7 outbox 격리 (발행 쪽 poison)

```java
List<OutboxMessage> batch =
        repository.findTop100ByPublishedAtIsNullAndAttemptsLessThanOrderByCreatedAtAsc(maxAttempts);
```

상한을 넘긴 row 는 **조회 자체에서 빠진다**(격리). 지우지는 않는다 — 버려도 되는 이벤트란 없다.
대신 `outbox.stuck` 게이지로 "사람이 봐야 할 것이 몇 건인지"를 남긴다. **0보다 크면 경보 대상.**

### 4.8 ★ 고아 결제 보상 (Phase 13 결함 갚기)

```java
private void onPaymentCharged(SagaInstance saga, SagaReply reply, Instant now) {
    if (!saga.complete(now)) {
        compensateOrphanPayment(saga, reply, now);   // 무시하지 않는다 — 상쇄한다
        return;
    }
    ...
}
```

- 늦게 온 성공을 **무시하면 돈이 빠져나간 채로 남는다.** 이미 일어난 일은 롤백할 수 없으므로 **상쇄(semantic undo)** 한다.
- Saga 상태는 **건드리지 않는다.** 되돌리면 "취소됐다가 다시 진행 중"이 되어 sweep 대상으로 되살아난다.
- payment 는 결제 row 를 **지우지 않고** `REFUNDED` 로 남긴다 — 돈이 움직인 사실은 감사(audit) 대상이다.
- 멱등성은 결정적 커맨드 키 `CommandKeys.of(sagaId, "RefundPayment")` 가 보장한다(청구 키와 다르므로 서로 간섭 없음).
- ⚠️ **관련 결함(감사 2026-08-02):** 이 상쇄가 의존하는 `SagaReply`의 `paymentId`가 **재전송된 리플라이에서 유실**될
  수 있었다 — 유실되면 `confirm`도 실패하고 이 보상 경로도 올바른 결제 id를 못 받았다. `processed_commands`에
  `payment_id`를 저장해 재생하는 방식으로 해결됐다(→ [PHASE-13-SAGA-ORCHESTRATION.md §8](PHASE-13-SAGA-ORCHESTRATION.md)).

---

## 5. 요청 하나가 흐르는 순서 (사전 확인이 실패하는 경우)

```
1. POST /orders                        → 게이트웨이 RateLimiter 통과 → Bulkhead 통과
2. ordersCb(CLOSED) → lb://order-service
3. order-service: 재고 사전 확인 호출
     RateLimiter 통과 → TimeLimiter 800ms 시작 → CircuitBreaker(CLOSED) → Bulkhead(8) → Retry
       ├ 시도 1 실패(500) → 100ms 대기
       ├ 시도 2 실패(500) → 200ms 대기
       └ 시도 3 실패(500) → Retry 소진 → 예외를 위로
     CircuitBreaker 가 '실패 1건'으로 기록(재시도 3회가 아니라 1건!)
4. 어댑터가 예외를 잡아 StockPrecheck.UNKNOWN 으로 강등
5. 주문은 그대로 PENDING 저장 + outbox 기록 → 201 Created
     응답 헤더: X-Stock-Precheck: UNKNOWN (InternalServerError)
6. 실패가 5건 중 50%를 넘으면 회로 OPEN → 이후 요청은 inventory 를 때리지 않고 즉시 UNKNOWN
7. 10초 뒤 HALF_OPEN → 시험 호출 3건 성공 → CLOSED (사람 개입 없음)
8. Saga 는 평소대로 진행 → 재고·결제 판정 → CONFIRMED / CANCELLED
```

**포인트:** 5번에서 주문이 정상 접수된다는 것. 부가 기능이 완전히 죽어도 본 기능은 흐른다.

---

## 6. 원리 / 트레이드오프

### 6.1 "실패를 없애기" vs "실패를 국소화하기"
복원력 패턴은 하나도 성공률을 높이지 않는다. 오히려 **더 빨리 실패시킨다**(fast-fail).
얻는 것은 **자원 보호**와 **장애 격리**다. 회로가 열리면 죽은 상대를 때리느라 묶여 있던 스레드가 풀려
멀쩡한 요청이 계속 처리된다.

### 6.2 동기 호출을 다시 허용한 조건
Phase 2의 동기 결제 호출은 **필수 경로**에 있었기에 payment 가 죽으면 주문이 죽었다.
Phase 14의 사전 확인은 ① 결과가 없어도 되고(UNKNOWN) ② 정답은 다른 곳(Saga)이 보장하고
③ 상한(800ms)이 걸려 있다. **이 세 조건이 갖춰질 때만** 동기 호출을 넣어도 된다.

### 6.3 사전 확인은 왜 기본값이 "거절하지 않음"인가
사전 확인은 **락 없이 읽은 값**이라 확인과 실제 예약 사이에 다른 주문이 재고를 가져갈 수 있다(TOCTOU).
틀린 값으로 거절하면 "살 수 있었는데 못 산" 손해가 난다. 그래서 기본은 참고값이고,
`order.stock-precheck.reject-on-insufficient=true` 로 켜면 뻔한 실패를 즉시 409로 돌려준다 — **업무가 정할 문제**다.

### 6.4 재시도는 왜 반드시 유한해야 하나
무한 재시도는 장애를 **감춘다**. 지표는 정상이고 로그만 흐르는데 실제로는 아무것도 진행되지 않는다.
유한 재시도 + DLQ 는 "여기까지 해 봤고 안 됐다"는 사실을 **드러낸다.** 드러나야 고칠 수 있다.

### 6.5 DLQ 는 쓰레기통이 아니다
DLT 에 쌓인 메시지는 **처리해야 할 일감**이다. 쌓이는데 아무도 안 보면 조용한 데이터 유실과 같다.
운영에서는 DLT lag 에 경보를 건다(이 프로젝트는 kafka-ui 로 눈으로 확인).

### 6.6 회로차단기 파라미터의 감각
- `minimumNumberOfCalls` 가 없으면 **첫 실패 1건에 회로가 열린다**(표본 부족).
- `waitDurationInOpenState` 가 너무 짧으면 열렸다 닫혔다를 반복(플래핑), 너무 길면 회복이 늦다.
- 회로가 **자동으로** HALF_OPEN → CLOSED 가 되는 것이 핵심이다. 사람이 껐다 켜야 한다면 복원력이 아니다.

### 6.7 이번 설계가 포기한 것
- 엣지 한도가 **인스턴스 로컬**이라 스케일아웃 시 총량이 늘어난다(분산 한도는 Redis 필요).
- 현재 순서에서 **타임아웃은 회로를 열지 못한다**(§4.3). 느린 장애는 회로가 아니라 TimeLimiter 만 막는다.
- DLT 재투입(replay) 도구가 없다 — 지금은 kafka-ui 로 보는 것까지.

---

## 7. 검증 (실증)

> 실행: `docker compose -f deploy/compose/compose.yml --profile async up -d --build` (15 컨테이너)
> 장애 주입: `POST /inventory/chaos?failRate=..&delayMs=..` · 해제: `DELETE /inventory/chaos`

### 7.1 Resilience4j 5종 — 실측

| 시나리오 | 조치 | 관측 결과 |
|---|---|---|
| 정상 | `failRate=0` | `201`, `X-Stock-Precheck: AVAILABLE` |
| **TimeLimiter** | `delayMs=3000` (상한 800ms) | `201`, `X-Stock-Precheck: UNKNOWN (TIMEOUT)` — **응답 0.88초**(3초 안 기다림) |
| **Retry → CircuitBreaker** | `failRate=100`, 6회 요청 | 1~4회 `UNKNOWN (InternalServerError)`, **5~6회 `UNKNOWN (CIRCUIT_OPEN)`** |
| 회로 상태 | `/actuator/health` | `state: OPEN`, `failureRate 80%`, `bufferedCalls 5`, `notPermittedCalls 2` |
| **자동 회복** | `failRate=0` + 12초 대기 | `AVAILABLE` 로 복귀 (HALF_OPEN→CLOSED, 사람 개입 없음) |
| **모든 경우** | 위 전부 | **주문은 항상 `201 PENDING`** — 부가 기능 실패가 본 기능을 막지 않음 |

**★ aspect 순서가 실측으로 증명된 지점 두 가지**

- `bufferedCalls=5` = 성공 1건(정상) + 실패 4건(요청 4회). **요청마다 3번 재시도했는데 회로는 1건으로 셌다**
  → `Retry` 가 회로차단기보다 **안쪽**임이 확인된다.
- 타임아웃 시나리오는 **회로 통계에 잡히지 않았다**(bufferedCalls 에 미포함)
  → `TimeLimiter` 가 회로차단기보다 **바깥**임이 확인된다. §4.3에서 예고한 트레이드오프 그대로다.

### 7.2 게이트웨이 회로차단기 + fallback

```
정상   : ordersCb = CLOSED
order-service 정지 후 8회 호출:
         ordersCb = OPEN, failureRate 50.0%, notPermittedCalls 5     ← 5건은 아예 보내지도 않음
         응답 본문: {"title":"일시적으로 사용할 수 없음","status":503,
                    "detail":"order-service 를 지금 사용할 수 없습니다(회로 열림 또는 응답 지연)…"}
재기동 후: ordersCb = CLOSED                                          ← 스스로 회복
```

> ⚠️ 재기동 직후 **약 30~40초**는 Eureka 재등록 전이라 계속 503 이 나온다. 회로 문제가 아니라 디스커버리 지연이다.

### 7.3 엣지 RateLimiter — 40건 동시 폭주 (한도 20/s)

```
  20 × 429   ← RateLimiter 가 차단
  20 × 503   ← 통과했으나 다운스트림(회로 열림) fallback
```
정확히 20건만 통과했다. **초과분이 다운스트림에 도달하지 않는 것**이 핵심이다.

### 7.4 DLQ / poison 메시지

토픽 자동 생성 확인: `order-events.DLT`, `inventory-events.DLT`, `payment-events.DLT`, `saga-commands.DLT`, `saga-replies.DLT`

**① 역직렬화 불가(poison pill) → 재시도 없이 즉시 DLT**
```
$ echo 'THIS-IS-NOT-JSON' | kafka-console-producer.sh --topic order-events

WARN  소비 실패 — 배달 시도 1/4 topic=order-events partition=0 offset=53
      원인=ListenerExecutionFailedException ← IllegalStateException: No type information in headers…
ERROR ★ DLT 이동 topic=order-events → order-events.DLT partition=0 offset=53
```
백오프 재시도가 **한 번도 일어나지 않았다**(0.08초 만에 DLT). 재시도해도 결과가 같기 때문.
DLT 레코드 헤더: `kafka_dlt-exception-fqcn: …DeserializationException`, `kafka_dlt-original-topic: order-events`.

**② 파티션이 막히지 않았는가** — poison 직후 새 주문을 넣자 읽기 모델에 `CONFIRMED 12.0` 으로 정상 투영.
**한 개의 독약이 뒤의 정상 메시지를 막지 못한다.**

**③ 일시적 결함은 재시도로 살아난다** — Mongo 를 정지시킨 채 주문 접수:
```
WARN  소비 실패 — 배달 시도 1/4 … 원인=… ← MongoTimeoutException: … UnknownHostException: order-query-mongo
```
Mongo 재기동 후 **DLT 로 가지 않고 재시도가 성공** → 해당 주문이 읽기 모델에 `CONFIRMED 13.0` 으로 투영됐다.
**"영구 실패는 즉시 격리, 일시 실패는 재시도"** 가 그대로 갈린다.

### 7.5 ★ 고아 결제 보상 (Phase 13 결함 재현 → 해결)

payment-service 를 정지한 채 주문 → sweep 이 재촉 3회 후 포기 → 보상 → 취소. 이후 payment 재기동.

```
[1] saga=AWAITING_INVENTORY/att=0  order=PENDING
[2] saga=AWAITING_PAYMENT/att=0    order=INVENTORY_RESERVED
[4] saga=AWAITING_PAYMENT/att=1    order=INVENTORY_RESERVED     ← sweep 재촉 1
[7] saga=AWAITING_PAYMENT/att=2    order=INVENTORY_RESERVED     ← sweep 재촉 2
[9] saga=AWAITING_PAYMENT/att=3    order=INVENTORY_RESERVED     ← sweep 재촉 3
[12] saga=CANCELLED/att=0          order=CANCELLED              ← 포기 → 보상 → 취소

# payment 재기동 → 큐에 남아 있던 ChargePayment 를 뒤늦게 수행
ERROR ★ 고아 결제 감지 — 환불 지시 sagaId=ba7cea10… paymentId=970ed91f… sagaState=CANCELLED
WARN  [커맨드] RefundPayment 수신 … 사유=Saga 종료(CANCELLED) 후 도착한 결제
WARN  [커맨드] 고아 결제 환불 … amount=33.00
WARN  고아 결제 정리 완료 …
```

**최종 상태 (Phase 13에서는 결제만 살아남았던 자리)**

| 항목 | Phase 13 | **Phase 14** |
|---|---|---|
| order | CANCELLED | CANCELLED |
| saga | CANCELLED | CANCELLED |
| **payment** | **CAPTURED (고아!)** | **REFUNDED** ✅ |
| 재고 | 원복 | 원복(71 → 71) |

`processed_commands` 에 `PAYMENT_REFUNDED` 1건이 기록되어 **재전송돼도 이중 환불이 없다.**
전 과정이 **한 트레이스**(`945fd5e7…`)로 order-service ↔ payment-service 를 가로질러 이어진다.

### 7.6 outbox 격리 (발행 쪽 poison)

Kafka 를 정지시킨 채 주문을 넣고 4분간 방치했다.

```
== 미발행 outbox attempts 분포 ==            == kafka 복구 후 ==
 attempts | count                            attempts | count
 ---------+-------                           ---------+-------
        5 |     6                                   5 |     6      ← 그대로 격리 상태

ERROR Outbox 발행 포기 — 격리 messageId=843c4810… topic=saga-commands type=ReserveStockCommand
      attempts=5 원인=java.util.concurrent.TimeoutException
ERROR Outbox 발행 포기 — 격리 messageId=60381a56… topic=order-events   type=OrderCancelledEvent  attempts=5 …

격리된 주문 1fdf5f39… → CANCELLED     ← 커맨드가 못 나가 sweep 이 포기 → 취소(단, 취소 이벤트도 격리됨)
새 주문   61ca3fb6… → CONFIRMED       ← ★ 격리된 row 가 뒤의 정상 흐름을 막지 않는다
```

핵심은 마지막 두 줄이다. **격리된 6건이 남아 있는 상태에서 새 주문은 끝까지 정상 처리된다.**
격리가 없었다면 이 6건이 매 폴링마다 배치 앞자리를 차지해 뒤의 이벤트가 계속 밀렸을 것이다.

동시에 이것이 **격리의 비용**도 보여 준다 — 격리된 row 는 <b>사라지지 않고 남은 일감</b>이다.
위 예에서 그 주문은 DB 상 CANCELLED 이지만 취소 이벤트가 발행되지 않아 읽기 모델은 모른다.
그래서 `outbox.stuck` 게이지가 0보다 크면 **사람이 개입해야 한다**(한계 #5).

> ⚠️ 이 시나리오는 **재현에 시간이 걸린다.** 실패 1회마다 `max.block.ms`(5초) + send 타임아웃(5초)이 걸려
> 상한 5회에 도달하는 데 수 분이 필요하다. 단위 테스트(`OutboxRelayTest`)가 같은 성질을 빠르게 지킨다.

### 7.7 회귀 가드 (자동 테스트 76개 통과)

| 테스트 | 지키는 것 |
|---|---|
| `StockAvailabilityRestAdapterTest` | 회로 열림·타임아웃 → **예외를 던지지 않고** UNKNOWN 강등 / 확정 부족이 UNKNOWN 보다 우선 |
| `OutboxRelayTest` | 상한 이하만 폴링 / ack 후에만 published / 실패 시 attempts 증가·미발행 유지 |
| `PaymentCommandServiceTest` | 환불 성공·중복 커맨드 무시(리플라이는 재전송)·이미 환불됨·대상 없음 |
| `SagaOrchestratorServiceTest` | 종료된 Saga 뒤 결제 성공 → **환불 지시** / 주문은 되살아나지 않음 |

---

## 8. 알려진 한계 → 해결 Phase

| # | 한계 | 왜 지금은 이대로 | 해결 |
|---|---|---|---|
| 1 | 엣지 한도가 **인스턴스 로컬** — 게이트웨이를 2개 띄우면 한도도 2배 | Redis 를 들이지 않기 위해 | Phase 16(k8s) 이후 Redis 기반 `RequestRateLimiter` 또는 Ingress 레벨 |
| 2 | **타임아웃이 회로를 열지 못한다**(현재 aspect 순서) | 학습 계획의 권장 순서를 그대로 체험하기 위해 | 순서 변경 또는 `slow-call-duration-threshold` 사용 — §4.3 |
| 3 | **DLT 재투입(replay) 도구 없음** — 쌓인 메시지를 사람이 손으로 봐야 함 | 소비 쪽 신뢰성 확보가 우선 | Phase 15(계약/스키마) 이후 관리 API 또는 Kafka Streams |
| 4 | DLT lag **경보 없음** — 쌓여도 대시보드를 봐야 안다 | 관측 파이프라인은 Phase 8에서 끝 | Phase 18 Grafana Alertmanager |
| 5 | outbox 격리 row 를 **자동 복구하지 않는다** | 자동 재시도는 같은 실패의 반복 | 운영 절차 + `outbox.stuck` 경보(Phase 18) |
| 6 | **chaos 엔드포인트가 인증만 통과하면 누구나** 호출 가능 | 학습 전용(`chaos.enabled`) | 운영 프로파일에서 제거 / Phase 15 권한 강화 |
| 7 | 사전 확인이 **트랜잭션 안에서** 원격 호출 | TimeLimiter 800ms 상한이 있어 허용 | 상한이 없다면 트랜잭션 밖으로 빼야 한다 |
| 8 | 환불은 **가짜 PG stub** — 실제 결제망 환불 실패는 다루지 않음 | 결제 게이트웨이가 stub | Phase 18(캡스톤) |
| 9 | 스키마 **깨지는 변경**은 여전히 DLT 폭탄 | 계약 검증 장치가 없음 | **Phase 15**(계약 테스트·스키마 진화) |

---

## 9. 용어사전

| 용어 | 한 줄 정의 |
|---|---|
| **Circuit Breaker** | 실패율이 임계를 넘으면 회로를 열어 호출을 즉시 실패시키고, 일정 시간 뒤 스스로 시험하는 장치 |
| **CLOSED / OPEN / HALF_OPEN** | 정상 통과 / 차단(fast-fail) / 회복 시험 중 |
| **fast-fail** | 기다리지 않고 즉시 실패시켜 자원을 지키는 것 |
| **Bulkhead** | 동시 실행 수 상한. 침수를 한 칸에 가두는 배의 격벽에서 온 이름 |
| **RateLimiter** | 단위 시간당 호출 수 상한 |
| **TimeLimiter** | 응답 시간 상한. `CompletableFuture` 반환이 필요 |
| **Fallback** | 실패 시 돌려주는 대체 응답(성공인 척이 아니라 정직한 실패여야 한다) |
| **Graceful degradation** | 일부 기능을 포기하고 핵심 기능만 살려 계속 서비스하는 것 |
| **Poison pill** | 몇 번을 다시 해도 반드시 실패하는 메시지 |
| **DLQ / DLT** | 끝내 처리 못 한 메시지를 옮겨 두는 큐/토픽(`<원본>.DLT`) |
| **head-of-line blocking** | 앞의 하나가 막혀 뒤가 전부 대기하는 현상 |
| **Exponential backoff** | 재시도 간격을 점점 늘리는 것(상대에게 회복할 틈을 준다) |
| **Chaos engineering** | 일부러 장애를 주입해 시스템이 견디는지 확인하는 방법론 |
| **고아 결제(orphan payment)** | 주문은 취소됐는데 결제만 남은 상태. Phase 14에서 환불 보상으로 해결 |
| **TOCTOU** | Time-Of-Check to Time-Of-Use — 확인 시점과 사용 시점 사이에 상태가 바뀌는 문제 |

---

## 10. 참고 / 상호링크

- 직전 단계: [Phase 13 · Saga 오케스트레이션](PHASE-13-SAGA-ORCHESTRATION.md) — 이번 Phase가 갚은 결함(§7.2)이 여기 있다.
- 신뢰성 척추: [Phase 10 · Outbox + 멱등성](PHASE-10-OUTBOX.md) — `attempts` 컬럼이 여기서 생겼고 이번에 쓰였다.
- 관측성: [Phase 8 · 관측성](PHASE-8-OBSERVABILITY.md) — 회로 상태·재시도는 메트릭으로 봐야 한다.
- 아키텍처 규칙: [HEXAGONAL.md](HEXAGONAL.md) — 복원력은 **어댑터**의 관심사다(도메인은 회로를 모른다).
- 커밋 지도: [PHASE-COMMIT-MAP.md](PHASE-COMMIT-MAP.md) · 로드맵: `MSA-LEARNING-PLAN.md`
- 공식 문서: [Resilience4j Getting Started (Spring Boot 3)](https://resilience4j.readme.io/docs/getting-started-3) ·
  [Spring for Apache Kafka — Handling Exceptions](https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html)

---

**다음 단계 → Phase 15 (플랫폼 강화):** Spring Cloud Bus 로 설정 broadcast, **계약 테스트**(Spring Cloud Contract),
**이벤트 스키마 진화**(tolerant reader). 이번 Phase의 한계 #9(깨지는 스키마 변경 = DLT 폭탄)가 거기서 해결된다.
