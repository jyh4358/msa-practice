# Phase 9 — 비동기 이벤트 with Kafka (9a)

> **한 줄 요약:** order가 재고를 **직접 예약하지 않고** `OrderPlaced` 이벤트를 Kafka로 발행하면,
> 새로 분리한 **inventory-service**가 그 이벤트를 소비해 **비동기로** 재고를 예약한다.
> 두 서비스는 시간적으로 **디커플링**되고(inventory가 죽어도 order는 동작), 요청은 **HTTP→Kafka를 가로질러 하나의 트레이스**로 이어진다.

초심자(Java/Spring은 알지만 이벤트 기반은 처음) 기준으로 **왜 → 무엇을 → 어떻게** 순서로 설명합니다.

---

## 0. 이번 단계에서 한 일 (요약)

- **inventory-service 신설** — order-service가 갖고 있던 재고(StockItem·예약·조회)를 **자기 서비스로 분리**(inventorydb).
- **`shared/events` 모듈** — 서비스 간 이벤트 계약(`OrderPlacedEvent`) 전용(JPA 금지).
- **order → Kafka 발행** — `placeOrder`가 재고 예약 대신 `OrderPlaced`를 발행(`KafkaTemplate`).
- **inventory ← Kafka 소비** — `@KafkaListener`가 `OrderPlaced`를 받아 재고 예약(비관적 락).
- **compose에 Kafka(KRaft) + kafka-ui** 추가(`--profile async`), 토픽은 `KafkaAdmin`+`NewTopic`으로 명시 생성.
- 결과: 주문 한 건이 **gateway→order→(payment 동기) + order→[Kafka]→inventory(비동기)** 로 흐르고, 전 구간이 **한 트레이스**.

> **범위(9a):** 로드맵의 **9a**(“Kafka 기동 + 이벤트 1개 end-to-end”)다. 신뢰성(outbox·멱등), 보상(Saga)은
> 각각 **Phase 10·12**로 뒤따른다(§8). "한 Phase 한 개념" 원칙.

---

## 1. 왜 — 직전(Phase 8)의 문제

Phase 2~8까지 order→payment는 **동기 REST**였다. 재고도 order가 **자기 트랜잭션 안에서 직접** 차감했다.
동기 결합의 문제:

- **시간적 결합:** 재고/결제 중 하나라도 느리면 주문 전체가 느려지고, 하나가 죽으면 주문이 실패한다(장애 전파).
- **강한 의존:** order가 재고 로직을 소유 → 재고 규칙이 바뀌면 order를 건드려야 한다(경계 흐림).

**비동기 이벤트**는 이 결합을 끊는다: order는 "주문이 일어났다"는 **사실만 알리고**(fire-and-forget) 자기 일을 끝낸다.
누가 언제 반응할지는 **소비자(inventory)의 몫**이다. inventory가 잠깐 죽어도 이벤트는 **Kafka에 남아** 있다가
살아나면 **재생(replay)** 된다.

---

## 2. 핵심 개념 (용어부터)

- **이벤트(Event):** "이미 일어난 사실"의 기록(예: `OrderPlaced`). 명령(“예약해라”)과 다르다 — 발행자는 소비자를 모른다.
- **fire-and-forget:** 발행하고 응답을 기다리지 않음. 발행자와 소비자가 **시간적으로 분리**.
- **결과적 일관성(eventual consistency):** 주문 시점엔 재고가 아직 확정 안 됨. 잠시 뒤 일관돼진다(즉시 일관성의 반대).
- **Kafka / 토픽 / 파티션 / 오프셋:**
  - **토픽(topic):** 이벤트가 쌓이는 이름 있는 로그(예: `order-placed`).
  - **파티션(partition):** 토픽을 나눈 병렬 단위(순서는 파티션 내에서만 보장). 여기선 1개.
  - **오프셋(offset):** 파티션 안 각 메시지의 위치 번호. 소비자는 "어디까지 읽었는지"를 오프셋으로 기억.
  - **컨슈머 그룹(consumer group):** 같은 그룹의 소비자들이 파티션을 나눠 읽음. 오프셋은 그룹 단위로 커밋.
  - **재생 가능한 로그(replayable log):** Kafka는 메시지를 지우지 않고 보관 → 소비자가 죽었다 살아나면 못 읽은 지점부터 다시 읽음.
- **KRaft:** Kafka의 새 합의 방식(ZooKeeper 없이 Kafka 자체가 메타데이터 관리). 이 프로젝트의 단일 노드 브로커가 이 모드.
- **컨텍스트 전파(HTTP→Kafka):** traceId를 Kafka 메시지 **헤더(`traceparent`)** 로 실어, 소비자 스팬이 같은 트레이스로 이어지게.

---

## 3. 구성 (그림)

```
  client → gateway(8000) → order(8080) ──http(동기)──▶ payment(8081)
                              │
                              │ publish OrderPlaced (fire-and-forget)
                              ▼
                       ┌─ Kafka topic: order-placed ─┐   ← 이벤트 보관(재생 가능)
                       └──────────────┬───────────────┘
                                      │ @KafkaListener (consumer group: inventory-service)
                                      ▼
                            inventory(8082) ── 재고 예약(비관적 락) → inventorydb
```

- **order**: 주문 생성 → (동기)결제 → 확정·저장 → **OrderPlaced 발행**. 재고는 더 이상 order가 안 만짐.
- **inventory**: `order-placed` 구독 → 상품별 재고 예약. 부족/미등록이면 로그만(9a: 보상 없음).
- **트레이스**: order의 **producer 스팬**과 inventory의 **consumer 스팬**이 `traceparent`로 이어져 **한 트레이스**.
- **실행**: Kafka·kafka-ui·inventory-db·inventory-service는 **`--profile async`** 로만 뜬다(메모리 절약·개념별 on/off).

---

## 4. 코드·설정 한 부분씩

### 4.1 이벤트 계약 (`shared/events`)
```java
public record OrderPlacedEvent(UUID orderId, UUID customerId, List<Item> items) {
    public record Item(UUID productId, int quantity, BigDecimal unitPrice) {}
}
```
- order와 inventory가 **같은 타입**을 공유 → JSON의 `__TypeId__` 헤더가 맞아떨어져 역직렬화가 깔끔.
- ⚠️ 이 모듈엔 **POJO만**. JPA 엔티티/리포지토리를 넣으면 "분산 모놀리스"가 된다(db-per-service 붕괴).

### 4.2 order — 발행 (아웃바운드 포트 + Kafka 어댑터)
```java
// 포트: 애플리케이션은 "알린다"는 의도만. Kafka는 어댑터 뒤로 숨김.
interface PublishOrderEventPort { void orderPlaced(OrderPlacedEvent event); }

// 어댑터: KafkaTemplate 으로 발행. key=orderId(같은 주문은 같은 파티션 → 순서).
kafkaTemplate.send("order-placed", event.orderId().toString(), event);
```
`OrderService.placeOrder`는 이제 **재고 예약 코드가 없다** — 결제·저장 후 `publishOrderPlaced(...)`만 호출.

### 4.3 inventory — 소비 (인바운드 이벤트 어댑터)
```java
@KafkaListener(topics = "order-placed", groupId = "inventory-service")
void on(OrderPlacedEvent event) {
    // 상품별 수량으로 집계 → 재고 예약 유스케이스 호출
    reserveStockUseCase.reserveForOrder(event.orderId(), quantityByProduct);
}
```
재고 예약은 order에서 **그대로 옮겨온** 비관적 락 로직(`SELECT … FOR UPDATE` + load-then-mutate).

### 4.4 토픽 명시 생성 (auto-create 끔)
```java
@Bean NewTopic orderPlacedTopic() {
    return TopicBuilder.name("order-placed").partitions(1).replicas(1).build();  // 단일 노드 → replicas=1
}
```
브로커에 `KAFKA_AUTO_CREATE_TOPICS_ENABLE=false` → 토픽은 이 `NewTopic`으로만 생김. **소비자(inventory)가 토픽을 선언**(브로커 healthy 후 기동하므로 생성 타이밍이 안전).

### 4.5 설정 (`config-repo/application.yml`, 공통)
```yaml
spring:
  kafka:
    bootstrap-servers: localhost:9092          # docker 프로파일이 kafka:19092 로 오버라이드
    producer: { value-serializer: ...JsonSerializer, properties: { max.block.ms: 5000 } }
    consumer:
      value-deserializer: ...JsonDeserializer
      auto-offset-reset: earliest              # 그룹 최초 조인 시 처음부터(리플레이)
      properties: { spring.json.trusted.packages: "com.shopsaga.events" }
    template:  { observation-enabled: true }   # ★ producer traceparent 주입
    listener:  { observation-enabled: true }   # ★ consumer traceparent 추출 (둘 다 켜야 트레이스 안 끊김)
```

### 4.6 Kafka(KRaft 단일 노드) — compose
```yaml
kafka:
  image: apache/kafka:4.3.1            # KRaft, arm64 네이티브
  profiles: [async]
  environment:
    KAFKA_PROCESS_ROLES: broker,controller
    # 리스너 3분리: CONTROLLER · PLAINTEXT(kafka:19092 컨테이너간) · PLAINTEXT_HOST(localhost:9092 호스트)
    KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1   # 단일 노드 → 1 (기본 3이면 내부 토픽 생성 실패)
    KAFKA_AUTO_CREATE_TOPICS_ENABLE: "false"
```
`ghcr.io/kafbat/kafka-ui`(:8090)로 토픽·메시지·컨슈머 lag을 눈으로 본다.

---

## 5. 요청 하나가 흐르는 순서

1. `POST :8000/orders` → gateway → **order**가 주문 생성.
2. order가 payment를 **동기** 호출(아직) → 결제 성공 → 주문 CONFIRMED·저장.
3. order가 `OrderPlaced`를 **Kafka(order-placed)** 로 발행하고 **즉시 응답 반환**(201).
4. **inventory**가 그 이벤트를 소비 → 상품별 재고 예약(inventorydb 차감).
5. 트레이스: order의 `order-placed send`(PRODUCER)와 inventory의 `order-placed receive`(CONSUMER)가 **같은 traceId**.

> 주문 응답(201)은 **재고 예약을 기다리지 않는다.** 재고 확정은 잠시 뒤(결과적 일관성).

---

## 6. 원리 / 트레이드오프

- **얻은 것:** 시간적 디커플링(inventory 장애가 order로 전파 안 됨), 재생 가능성(다운타임 이벤트 보관→재생), 확장성(소비자 독립 스케일).
- **잃은 것(의도적):** 즉시 일관성. 주문 시점엔 재고 미확정 → **재고 부족이어도 주문은 CONFIRMED**(§8). 이 "깨진 정합성"이 Saga(12)의 동기.
- **이중 쓰기 함정:** `save()`와 `send()`가 한 원자 트랜잭션이 아니다 → 저장 후 크래시=이벤트 유실 / 발행 후 롤백=유령 이벤트. **Phase 10 Outbox**가 해결.
- **트레이스가 끊기는 흔한 이유:** `observation-enabled`를 **template·listener 둘 다** 켜야 한다. 한쪽만이면 producer/consumer 스팬이 분리된다.
- **단일 노드 KRaft 함정:** 내부 토픽 `replication-factor`를 1로 낮추지 않으면(기본 3) 브로커가 unhealthy. `NewTopic`도 `replicas(1)`.

---

## 7. 검증 (실증)

- **빌드:** `shared:events` + `inventory-service`(StockItem 단위·**재고 동시성 테스트** 포함) + order 개조 → **BUILD SUCCESSFUL**.
- **비동기 흐름:** `--profile async` 기동 후 주문(수량 3) → order `OrderPlaced 발행` → inventory `OrderPlaced 수신`·`재고 예약 성공` → **inventorydb 100→97**.
- **HTTP→Kafka 단일 트레이스:** 한 traceId(`e123…`)에 **gateway·order·payment·inventory** 4서비스. order `order-placed send`(PRODUCER) ↔ inventory `order-placed receive`(CONSUMER) 연결.
- **내구성/리플레이:** inventory 중단 → 주문 2건(재고 97 그대로, **consumer LAG=2**) → inventory 재시작 → 버퍼된 2건 소비(**97→95**, **LAG=0**).
- **게이트웨이:** `/inventory/{productId}` → inventory-service(재시작으로 라우트 반영).
- **보는 법:** kafka-ui `http://localhost:8090`(토픽/메시지/lag), Grafana `:3000`(트레이스).

> 실행: `docker compose -f deploy/compose/compose.yml --profile async up -d --build` (기본 `up`은 Kafka·inventory 제외).

---

## 8. 알려진 한계 → 해결 Phase

| 한계 | 설명 | 해결 |
|---|---|---|
| **이중 쓰기(dual-write)** | order의 `save()`와 이벤트 `send()`가 비원자적 → 유실/유령 이벤트 | **Phase 10**(Outbox) |
| **멱등성 없음** | 같은 이벤트 2번 소비 → 재고 이중 예약 | **Phase 10**(`processed_messages`) |
| **보상 없음** | 재고 부족이어도 주문은 CONFIRMED(결과적 일관성만) | **Phase 12**(Saga: 재고 해제/주문 취소) |
| **결제 여전히 동기** | order→payment는 아직 REST | **Phase 12**(이벤트 흐름 전환) |
| **기본 프로파일 열화** | `--profile async` 없이는 재고 예약 안 됨(inventory 오프라인·이벤트 유실) | 설계상(관측성·async는 프로파일 게이트) |
| **파티션 1·단일 브로커** | 순서/처리량/내고장성 제한(학습용) | 운영 시 다중 파티션·클러스터 |

---

## 9. 용어사전

- **이벤트/명령:** 일어난 사실 / 시켜야 할 일. 이벤트 발행자는 소비자를 모른다.
- **토픽·파티션·오프셋:** 이름 있는 로그 / 병렬 단위 / 읽은 위치.
- **컨슈머 그룹:** 파티션을 나눠 읽는 소비자 묶음. 오프셋을 그룹 단위로 커밋.
- **결과적 일관성:** 잠시 뒤 일관돼짐(즉시 일관성 아님).
- **fire-and-forget:** 발행 후 응답 안 기다림.
- **재생(replay):** 보관된 로그를 다시 읽어 밀린 걸 따라잡음.
- **KRaft:** ZooKeeper 없는 Kafka 합의/메타데이터 방식.
- **이중 쓰기(dual-write):** DB와 메시지를 한 메서드에서 둘 다 쓰는 안티패턴(원자적일 수 없음 → outbox).
- **멱등성(idempotency):** 같은 입력을 여러 번 처리해도 결과가 한 번과 같음.
- **`traceparent`:** W3C 트레이스 컨텍스트 헤더. Kafka 헤더로 실려 트레이스가 이어짐.

---

## 10. 참고 / 상호링크

- 직전: [PHASE-8-OBSERVABILITY](PHASE-8-OBSERVABILITY.md)(트레이스로 이 비동기 흐름을 눈으로) · [PHASE-1-MONOLITH](PHASE-1-MONOLITH.md)(원래의 동기 재고 예약)
- 아키텍처: [HEXAGONAL](HEXAGONAL.md)(포트/어댑터 — 발행/소비도 어댑터)
- 다음: **Phase 10**(Outbox+멱등 — 이중 쓰기 해결) → **11**(CQRS) → **12**(Saga: 보상)
- 로드맵: [`MSA-LEARNING-PLAN.md`](../MSA-LEARNING-PLAN.md) · 큰 그림: [REVIEW-PART-A](REVIEW-PART-A.md)

*각 단계의 “알려진 한계 → 해결 Phase”는 [README](../README.md) 인덱스에서 모아 볼 수 있습니다.*
