# Phase 10 — 신뢰성 척추: 트랜잭셔널 Outbox + 멱등 소비자

> **한 줄 요약:** order가 주문을 저장할 때 이벤트를 **같은 트랜잭션에 outbox 테이블로 기록**하고,
> 별도 **릴레이(@Scheduled)** 가 그걸 Kafka로 내보낸다. inventory는 **messageId로 중복을 걸러**
> 같은 메시지를 여러 번 받아도 재고를 **정확히 한 번만** 예약한다.
> 결과: **이벤트 유실·유령·이중 예약이 사라지고**, Kafka가 잠깐 죽어도 주문은 성공한다.

초심자(Java/Spring은 알지만 분산 신뢰성은 처음) 기준으로 **왜 → 무엇을 → 어떻게** 순서로 설명합니다.

---

## 0. 이번 단계에서 한 일 (요약)

- **order에 `outbox` 테이블 + 발행 어댑터** — `placeOrder`가 Kafka로 직접 보내는 대신, 주문 저장과 **같은 트랜잭션**에 outbox row만 남긴다(원자적).
- **`OutboxRelay`(@Scheduled)** — 미발행 outbox row를 폴링해 Kafka로 발행하고, **브로커 ack를 받은 뒤에만** `published_at`을 채운다(at-least-once).
- **inventory에 `processed_messages` 테이블 + 멱등 가드** — 소비한 `messageId`를 기록하고, 이미 처리한 메시지는 부수효과 없이 **건너뛴다**(effectively-once).
- **messageId(=outbox.id)** 를 Kafka 헤더로 실어, 발행자와 소비자가 **같은 상관 ID**로 중복을 식별한다.
- 결과: 주문 한 건이 **저장·발행이 원자적**으로 기록되고, 브로커 장애·재배달에도 **재고는 정확히 한 번** 예약된다.

> **범위(Phase 10):** 로드맵의 “신뢰성 척추”. CQRS(11)·Saga(12) **이전**에 까는 이유는, 이게 없으면
> 투영도 보상도 전부 비결정적(유실·유령·중복)이 되기 때문이다. "한 Phase 한 개념" 원칙.

---

## 1. 왜 — 직전(Phase 9a)의 문제

Phase 9a에서 order는 이렇게 했다(요지):

```java
@Transactional
public OrderView placeOrder(...) {
    order.confirm(paymentId);
    saveOrderPort.save(order);            // ① DB 커밋
    publishOrderEventPort.orderPlaced(e); // ② Kafka send  ← 서로 다른 저장소, 한 원자 단위가 아님
}
```

이 **이중 쓰기(dual-write)** 는 원자적일 수 없다. DB와 Kafka는 **서로 다른 시스템**이라 하나의 트랜잭션으로 묶이지 않는다:

- **① 커밋 후 ② 직전에 크래시** → 주문은 저장됐는데 이벤트는 안 나감 = **이벤트 유실**(재고가 영원히 안 줄어듦).
- **② 발행 후 ① 롤백**(뒤늦은 예외) → 이벤트는 나갔는데 주문은 없음 = **유령 이벤트**(있지도 않은 주문의 재고 예약).

게다가 소비 측엔 **중복 방어가 없었다.** Kafka는 **at-least-once**(최소 한 번) 배달이라, 재시도·리밸런스로 **같은 메시지가 두 번** 올 수 있다. 그러면 inventory는 **재고를 두 번 예약**한다(이중 차감).

> 이 두 구멍(**유실/유령**, **중복**)이 남아 있으면 그 위에 쌓는 CQRS 투영·Saga 보상이 전부 어긋난다.
> 그래서 Kafka(9) 바로 다음, 다른 모든 것 **이전에** 이 “척추”를 세운다.

---

## 2. 핵심 개념 (용어부터)

- **이중 쓰기(dual-write):** 한 메서드에서 DB row와 메시지를 **둘 다** 쓰는 것. 두 저장소를 한 트랜잭션으로 묶을 수 없어 **부분 실패**가 난다.
- **트랜잭셔널 Outbox:** 이벤트를 곧바로 브로커에 보내지 않고, **업무 데이터와 같은 DB·같은 트랜잭션**에 `outbox` 테이블로 적어둔다. DB 커밋 하나로 “주문 저장 + 발행 의도”가 **원자적**이 된다.
- **릴레이(relay):** outbox의 **미발행 row를 주기적으로 읽어** 브로커로 내보내는 별도 프로세스. 여기선 `@Scheduled` 폴링.
- **at-least-once(최소 한 번):** 릴레이는 브로커 ack를 받은 뒤에만 “발행됨”으로 표시한다. 표시 직전에 죽으면 그 row는 **다시 발행**된다 → 다운스트림에 **중복**이 생길 수 있다(그래서 소비자가 멱등해야 함).
- **멱등성(idempotency):** 같은 입력을 여러 번 처리해도 결과가 **한 번 처리한 것과 같음**. 여기선 “같은 메시지 두 번 → 재고 예약 한 번”.
- **effectively-once(효과적 1회):** *at-least-once 배달* + *멱등 처리* = 부수효과는 **정확히 한 번**. (Kafka만으로 “exactly-once”를 achieved 하는 게 아니라, **소비자의 멱등**으로 달성한다.)
- **상관 ID(correlation id) / messageId:** 메시지를 **고유 식별**하는 값. 여기선 `outbox.id`를 messageId로 쓰고 Kafka 헤더로 실어, 소비자가 이 값으로 중복을 판별한다.

---

## 3. 구성 (그림)

```
  [order-service]                              [Kafka]            [inventory-service]
  ┌───────────────────────────┐
  │ placeOrder() @Transactional│
  │   orders   INSERT ─┐        │   ← 한 커밋(원자적)
  │   outbox   INSERT ─┘        │
  └───────────────────────────┘
              │ (커밋 완료. 아직 Kafka엔 아무것도 안 보냄)
              ▼
  ┌───────────────────────────┐  send + ack   ┌──────────────┐   @KafkaListener   ┌───────────────────────────┐
  │ OutboxRelay @Scheduled(1s) │ ────────────▶ │ order-placed │ ─────────────────▶ │ onOrderPlaced()@Transactional│
  │  미발행 row 폴링            │  header:       │   (topic)    │   header:messageId │  if processed(messageId) skip│
  │  ack 후 published_at 기록   │  messageId     └──────────────┘                    │  else reserve + mark(messageId)│
  └───────────────────────────┘                                                     └───────────────────────────┘
        (별도 스레드)                                                                   processed_messages(PK=messageId)
```

- **order**: 주문 저장과 outbox 기록이 **한 트랜잭션**. Kafka 호출은 요청 경로에 **없다**.
- **relay**: outbox를 폴링해 발행. `messageId`(=outbox.id)를 헤더로 실음. ack 후에만 발행완료 표시.
- **inventory**: `messageId`로 dedup → 예약 → `processed_messages`에 기록. **dedup·예약·기록이 한 트랜잭션**.

---

## 4. 코드·설정 한 부분씩

### 4.1 order — `outbox` 테이블 (Flyway `V5__outbox.sql`)
```sql
CREATE TABLE outbox (
  id            UUID PRIMARY KEY,            -- = messageId(소비자 dedup 키)
  aggregate_id  UUID NOT NULL,               -- 주문 id → Kafka 메시지 key
  event_type    VARCHAR(200) NOT NULL,       -- 이벤트 FQCN → 릴레이가 역직렬화에 사용
  topic         VARCHAR(100) NOT NULL,
  payload       JSONB NOT NULL,              -- 직렬화된 이벤트
  traceparent   VARCHAR(64),                 -- ★ Phase 12 예약(현재 미사용)
  attempts      INTEGER NOT NULL DEFAULT 0,  -- ★ Phase 14 예약(발행 실패 누적). Phase 10부터 카운트
  created_at    TIMESTAMPTZ NOT NULL,
  published_at  TIMESTAMPTZ                  -- NULL = 미발행
);
CREATE INDEX idx_outbox_unpublished ON outbox (created_at) WHERE published_at IS NULL;  -- 폴링용 부분 인덱스
```
- `payload`는 `jsonb`. Hibernate 매핑은 `@JdbcTypeCode(SqlTypes.JSON)` + `String` → `ddl-auto=validate`가 jsonb 컬럼과 정확히 맞아떨어진다(기동 시 검증 통과 확인).

### 4.2 order — 발행 어댑터: **Kafka로 안 보내고 outbox에만 쓴다**
```java
@Component
class OutboxOrderEventPublisher implements PublishOrderEventPort {   // 포트는 그대로 — 애플리케이션은 여전히 "알린다"만 앎
    public void orderPlaced(OrderPlacedEvent event) {
        String payload = objectMapper.writeValueAsString(event);
        repository.save(new OutboxMessageJpaEntity(
            UUID.randomUUID(), event.orderId(), event.getClass().getName(), "order-placed", payload, Instant.now()));
        // ⚠️ 여기서 Kafka.send() 안 함! 현재 트랜잭션(주문 save와 동일)에 row만 기록 → 원자적.
    }
}
```
`OrderService.placeOrder`는 **한 글자도 안 바뀌었다** — 여전히 `publishOrderEventPort.orderPlaced(...)`만 호출한다. 구현이 “직접 Kafka”에서 “outbox 기록”으로 바뀌었을 뿐(포트/어댑터의 힘).

> (현재는 이름이 다르다 — Phase 12에서 outbox·inbox가 3개 서비스 공유 라이브러리(`shared/outbox`)로 승격되며,
> 이 클래스는 `OrderEventOutboxPublisher`로 이름이 바뀌었고 `OutboxWriter`(공유 라이브러리)에 위임하는 형태로
> 다시 쓰였다. 토픽 이름도 `order-placed`에서 **`order-events`**로 바뀌었다(order가 발행하는 여러 사실 이벤트를
> 한 토픽에 모으는 원칙으로 정리됨). 아래·위 코드의 `OutboxOrderEventPublisher`·`order-placed`는 Phase 10 시점
> 그대로의 역사적 기록이다. → [PHASE-12-SAGA.md](PHASE-12-SAGA.md) §0·§2.)

### 4.3 order — 릴레이(@Scheduled): ack 후에만 발행완료
```java
@Component
class OutboxRelay {
    @Scheduled(fixedDelayString = "${outbox.relay.fixed-delay:1000}")
    @Transactional
    public void relay() {
        for (OutboxMessageJpaEntity msg : repository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()) {
            try {
                Object event = objectMapper.readValue(msg.getPayload(), Class.forName(msg.getEventType()));
                Message<Object> m = MessageBuilder.withPayload(event)
                    .setHeader(KafkaHeaders.TOPIC, msg.getTopic())
                    .setHeader(KafkaHeaders.KEY, msg.getAggregateId().toString())  // 같은 주문 → 같은 파티션(순서)
                    .setHeader("messageId", msg.getId().toString())                // 소비자 dedup 키
                    .build();
                kafkaTemplate.send(m).get(5, TimeUnit.SECONDS);   // ★ ack까지 블로킹 — 확인 후에만
                msg.markPublished(Instant.now());
            } catch (Exception e) {
                msg.recordFailedAttempt();   // published_at 안 채움 → 다음 폴링에서 재시도(at-least-once)
            }
        }
    }
}
```
- `OrderServiceApplication`에 `@EnableScheduling` 추가로 이 폴링이 돈다.
- 발행 도중 크래시 → 그 row는 미발행으로 남아 **재시작 후 다시 발행** → 다운스트림 중복 → 소비자 멱등이 흡수.

### 4.4 inventory — `processed_messages` 테이블 (Flyway `V2__processed_messages.sql`)
```sql
CREATE TABLE processed_messages (
  message_id UUID PRIMARY KEY,        -- 발행자(order outbox)의 id. 헤더 messageId 로 전달됨
  consumer   VARCHAR(100) NOT NULL,
  handled_at TIMESTAMPTZ NOT NULL
);
```
`message_id`가 **PK** — 같은 메시지의 두 번째 INSERT는 PK 위반으로 막힌다(경합 시 진짜 방어벽).

### 4.5 inventory — 멱등 가드(dedup·예약·기록을 한 트랜잭션에) (요지 — 실패 처리 생략)
```java
@Transactional
public void reserveForOrder(UUID messageId, UUID orderId, Map<UUID,Integer> qty) {
    if (processedMessagePort.isAlreadyProcessed(messageId)) {   // 이미 처리 → 부수효과 없이 skip
        log.info("이미 처리된 메시지 — 재고 예약 건너뜀 messageId={} ...", messageId);
        return;
    }
    new TreeMap<>(qty).forEach((product, q) -> reserveStockPort.reserve(product, q));  // 진짜 부수효과
    processedMessagePort.markProcessed(messageId);   // 처리 기록 — 예약과 같은 트랜잭션
}
```
> **실제 동작(위 스니펫에서 생략됨):** `reserveStockPort.reserve()`가 재고 부족으로 `InsufficientStockException`을
> 던지면, 이 시점(Phase 10)의 실제 코드는 그 예외를 **catch해 로그만 남기고 그대로 `markProcessed()`를 호출**한다
> — 재시도도 실패 이벤트 발행도 없다. 즉 재고가 부족해도 주문은 그대로 `CONFIRMED`로 남는다(§8 "보상 없음" 참고).
> 이 구멍은 실패를 **사실 이벤트**(`InventoryFailed`)로 발행하는 **Phase 12**에서 메워진다.

소비 어댑터(`OrderPlacedListener`)는 `@Header("messageId")`로 헤더를 읽어 이 유스케이스에 넘긴다(헤더 없으면 orderId로 대체 dedup).

### 4.6 설정 (`config-repo/application.yml`)
```yaml
outbox:
  relay:
    fixed-delay: 1000    # ms. 미발행 outbox row를 Kafka로 내보내는 폴링 간격(order-service만 사용).
```

---

## 5. 요청 하나가 흐르는 순서

1. `POST :8000/orders` → order가 주문 생성 → (동기)결제 → **주문 저장 + outbox INSERT를 한 커밋**으로. 즉시 201 응답(Kafka 안 기다림).
2. `OutboxRelay`가 다음 폴링(≤1s)에서 그 row를 읽어 **Kafka `order-placed`로 발행**, ack 후 `published_at` 기록.
3. inventory가 소비 → `messageId`가 **처음이면** 재고 예약 + `processed_messages` 기록, **재방문이면** 건너뜀.
4. 상관 ID로 추적: `outbox.id` == 헤더 `messageId` == `processed_messages.message_id`.

> 핵심 대비: **①단계에 Kafka가 없다.** 브로커가 죽어 있어도 주문은 성공하고, 이벤트는 outbox에 **버퍼링**된다.

---

## 6. 원리 / 트레이드오프

- **왜 outbox가 원자성을 주나:** 이벤트를 **같은 DB의 테이블**에 쓰므로 업무 row와 **하나의 로컬 트랜잭션**으로 커밋된다. 브로커라는 “두 번째 저장소”를 요청 경로에서 제거했다.
- **effectively-once = at-least-once + 멱등:** Kafka/릴레이는 중복을 만들 수 있음을 **인정**하고, 소비자가 `messageId`로 **정확히 한 번**만 반영한다. “정확히 한 번 배달”을 브로커에 요구하지 않는다.
- **얻은 것:** ① 이벤트 유실/유령 제거, ② 중복 소비 무해화, ③ **브로커 가용성과 주문의 분리**(Kafka 다운에도 주문 성공·이벤트 보존), ④ `messageId`라는 추적 가능한 상관 축.
- **비용(의도적):** 발행 지연이 폴링 간격만큼 생김(≤1s), outbox 테이블이 커짐(운영 시 발행완료 row 정리 필요), 릴레이가 트랜잭션을 열고 Kafka I/O를 블로킹(단일 노드 학습 규모에선 무해; 대량은 Phase 14에서 배치·비동기 ack로 개선).
- **트레이스 끊김(중요 한계):** 릴레이는 **요청 스레드가 아닌 @Scheduled 스레드**에서 발행하므로 원 요청의 트레이스 컨텍스트가 없다 → **HTTP→Kafka 단일 트레이스가 여기서 끊긴다**(9a에선 이어졌음). 복원(= `traceparent`를 outbox에 저장했다 재주입)은 **Phase 12**의 몫이다(`traceparent` 컬럼을 미리 만들어 둠).
- **예약 컬럼:** `attempts`(발행 실패 누적)는 Phase 10부터 카운트하지만 “N회 초과 → 격리/DLT”는 **Phase 14**. `traceparent`는 **Phase 12**.

---

## 7. 검증 (실증)

깨끗한 상태(`down -v` → `--profile async up`, 재고 100/100)에서 실측:

- **빌드/테스트:** `BUILD SUCCESSFUL`. 신규 단위테스트 — 발행 어댑터가 **Kafka 대신 outbox row만** 기록(`OutboxOrderEventPublisherTest`), 릴레이가 **ack 후에만 발행완료·실패 시 미발행 유지**(`OutboxRelayTest`, at-least-once), 소비자 **중복 배달 → 예약 1회**(`IdempotentReservationTest`, effectively-once) + 기존 재고 동시성(Testcontainers) 통과.
- **마이그레이션·검증:** order `V5 outbox`·inventory `V2 processed_messages` 적용, order-service가 **healthy**로 기동 = **Hibernate `validate`가 jsonb 컬럼까지 통과**.
- **Outbox 원자성·릴레이:** 주문(qty 3) → outbox row 1건(`created_at 10:08:59.137` → `published_at 10:08:59.694`, ~0.56s, `attempts=0`). **상관 ID 일치**: `outbox.id == processed_messages.message_id == 352f7374…`. `payload`는 jsonb라 `payload->>'orderId'`로 조회됨.
- **비동기 소비:** inventory `OrderPlaced 수신` → `재고 예약 성공` → **재고 100→97**, `processed_messages` 1건.
- **멱등성(라이브 리플레이):** 컨슈머 오프셋을 1 되돌려 **같은 메시지 재배달** → 로그 `이미 처리된 메시지 — 재고 예약 건너뜀` → **재고 97 그대로**, `processed_messages` **여전히 1건**.
- **브로커 장애 내구성(라이브):** Kafka 중단 상태에서 주문(qty 2) → **여전히 201 CONFIRMED**(요청 경로에 Kafka 없음), outbox에 **버퍼링**(미발행). Kafka 재기동 → 릴레이가 **자동 드레인**(`attempts=4`로 장애 중 재시도 흔적 → `published_at` 기록) → 재고 **97→95**, `processed_messages` 2건, **미발행 0건**.
- **적대적 리뷰:** 5개 관점(원자성·멱등성·헥사고날·JPA/검증·리소스·트랜잭션) 병렬 리뷰 + 검증 → **Phase 10 범위 내 결함 0건**(나머지는 Phase 12/14로 의도적 이연 또는 유지보수 nit).

> 실행: `docker compose -f deploy/compose/compose.yml --profile async up -d --build`
> 보는 법: kafka-ui `:8090`(토픽/오프셋/lag), Grafana `:3000`(트레이스; 릴레이 구간은 Phase 12 전까지 분리 트레이스).

---

## 8. 알려진 한계 → 해결 Phase

| 한계 | 설명 | 해결 |
|---|---|---|
| **트레이스 끊김** | 릴레이가 @Scheduled 스레드에서 발행 → HTTP→Kafka 단일 트레이스가 끊김 | **Phase 12**(`traceparent` outbox 저장·재주입) |
| **보상 없음** | 재고 부족이어도 주문은 CONFIRMED(멱등은 “한 번”만 보장, 정합성은 아님) | **Phase 12**(Saga: 재고 해제/주문 취소) |
| **결제 여전히 동기** | order→payment는 아직 REST | **Phase 12**(이벤트 흐름 전환) |
| **poison 메시지·무한 재시도** | 계속 실패하는 outbox row가 매 폴링 재시도(`attempts`만 증가) | **Phase 14**(임계치 초과 격리 + DLQ/`*.DLT`) |
| **outbox 청소 없음** | 발행완료 row가 계속 쌓임 | 운영: 주기적 아카이브/삭제 잡(후속) — [BACKLOG.md](BACKLOG.md) §8(감사 2026-08-02에서 미수정 결함으로 재확인) |
| **릴레이가 트랜잭션 열고 Kafka I/O 블로킹** | 단일 노드 학습 규모엔 무해하나 대량엔 커넥션 점유 | **Phase 14**(배치·비동기 ack·풀 튜닝) |
| **폴링 지연** | 발행이 폴링 간격(≤1s)만큼 지연 | 설계상(트레이드오프); 필요 시 CDC/Debezium(로드맵 밖) |

---

## 복습 포인트 (스스로 답해보기)

1. `orders` INSERT와 Kafka `send()`를 하나의 `@Transactional` 안에 넣으면 왜 안 되나? 실패 조합 두 가지는?
   <details><summary>답</summary>Kafka는 DB 트랜잭션에 참여하지 않는 <b>별도 시스템</b>이라 한 커밋으로 묶을 수 없다. ① 커밋 성공 + 발행 실패 = <b>이벤트 유실</b>(재고가 영원히 안 줄어듦). ② 발행 성공 + 롤백 = <b>유령 이벤트</b>(있지도 않은 주문의 재고가 깎임)(§1).</details>
2. 트랜잭셔널 outbox는 "정확히 한 번(exactly-once)" 전달을 보장하나? 실제로 무엇을 보장하고 무엇은 보장하지 않나?
   <details><summary>답</summary><b>아니다.</b> outbox+릴레이는 <b>at-least-once</b>만 준다 — ack 표시 직전에 릴레이가 죽으면 같은 row가 다시 발행돼 <b>중복</b>이 생길 수 있다. "정확히 한 번처럼 보이는" 효과(effectively-once)는 outbox가 아니라 <b>소비자의 멱등 처리</b>가 만든다(§2·§6).</details>
3. inventory가 같은 메시지를 두 번 받으면 재고가 두 번 깎이나? 무엇이 막나?
   <details><summary>답</summary>안 깎인다. <code>processed_messages.message_id</code>가 <b>PK</b>라서 두 번째 처리 시도가 <code>isAlreadyProcessed</code> 체크에 걸려 부수효과 없이 건너뛴다. 핵심은 dedup 체크·예약·기록이 <b>한 트랜잭션</b>이라는 것 — 기록만 되고 예약이 롤백되면 그 메시지는 영영 처리 안 된 것으로 남는다(§4.5).</details>
4. `messageId`는 어디서 나와서 어디까지 같은 값으로 흐르나?
   <details><summary>답</summary><code>outbox.id</code>(발행자가 만든 PK)가 Kafka 헤더 <code>messageId</code>로 실려, 소비자의 <code>processed_messages.message_id</code>까지 <b>같은 값</b>으로 이어진다. 이 상관 ID 하나로 발행-소비 전 구간을 추적할 수 있다(§2·§7).</details>

---

## 9. 용어사전

- **이중 쓰기(dual-write):** DB와 메시지를 한 메서드에서 둘 다 쓰는 안티패턴(원자적일 수 없음).
- **트랜잭셔널 Outbox:** 이벤트를 업무 데이터와 같은 트랜잭션에 테이블로 적어 원자성을 얻는 패턴.
- **릴레이(relay):** outbox 미발행 row를 브로커로 내보내는 별도 프로세스(여기선 @Scheduled 폴링).
- **at-least-once:** 최소 한 번 배달 — 중복 가능(그래서 멱등 필요).
- **멱등성/effectively-once:** 여러 번 처리해도 결과는 한 번 / at-least-once + 멱등 = 부수효과 정확히 1회.
- **상관 ID(messageId):** 메시지를 고유 식별하는 값(여기선 outbox.id). 헤더로 실려 소비자 dedup에 쓰임.
- **poison 메시지:** 계속 처리 실패하는 메시지(무한 재시도 유발 → DLQ로 격리).
- **DLQ(Dead Letter Queue):** 처리 실패 메시지를 보내는 별도 토픽(`*.DLT`).
- **부분 인덱스(partial index):** 조건을 만족하는 행만 걸어 두는 인덱스(`WHERE published_at IS NULL`). 릴레이가 매번 훑는 대상이 "미발행 row"뿐이라 인덱스도 그만큼만 있으면 된다 — 발행완료 row가 쌓여도 폴링 성능이 안 떨어지는 이유.
- **JSONB:** PostgreSQL의 이진 저장 JSON 타입. 텍스트 JSON과 달리 파싱된 형태로 저장돼 `payload->>'orderId'`처럼 필드 단위 조회가 가능하다.

---

## 10. 참고 / 상호링크

- 직전: [PHASE-9-ASYNC-KAFKA](PHASE-9-ASYNC-KAFKA.md)(이 outbox가 고치는 이중 쓰기/중복을 남겨둔 단계) · [PHASE-8-OBSERVABILITY](PHASE-8-OBSERVABILITY.md)(트레이스 — 릴레이 구간 끊김은 Phase 12에서 복원)
- 아키텍처: [HEXAGONAL](HEXAGONAL.md)(포트는 그대로, 어댑터만 outbox로 교체 — 포트/어댑터의 힘)
- 다음: **Phase 11**(CQRS 읽기 모델 — outbox→Kafka 위에 안전하게) → **12**(Saga: 보상 + 트레이스 복원) → **14**(복원력·DLQ)
- 로드맵/부록 코드: [`MSA-LEARNING-PLAN.md`](../MSA-LEARNING-PLAN.md)(Phase 10 §294~, outbox 부록 §404~) · 큰 그림: [REVIEW-PART-A](REVIEW-PART-A.md)

*각 단계의 “알려진 한계 → 해결 Phase”는 [README](../README.md) 인덱스에서 모아 볼 수 있습니다.*
