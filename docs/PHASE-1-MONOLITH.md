# 모놀리스 — 주문+재고+결제 단일 트랜잭션(ACID)

> **이 문서는 Phase 1 작업을 설명합니다.** 트랜잭션이 처음이어도 끝까지 이해하도록 개념 → 그림 →
> 이 프로젝트의 실제 코드/설정 → 동작 원리 → 검증 → 알려진 한계 순으로 정리했습니다.
> 뒤 Phase가 여기서 만든 것을 바꾼 지점은 그때마다 명시합니다(예: "Phase 2-2에서 제거됨").
> 본문은 이미 만든 코드를 되짚는 **회고형 해설**이며, 코드 스니펫은 요점만 보인 **발췌**입니다
> (`...`로 생략한 곳은 실제 파일에 더 있습니다). 처음 보는 용어는 첫 등장 지점의 괄호 정의와
> 문서 끝 **§9 용어 사전**에서 확인할 수 있습니다.

---

## 0. 한 줄 요약

> **주문 생성 + 재고 차감 + 결제 캡처를 하나의 `@Transactional` 안에서 처리해, 한 단계라도 실패하면
> 전부 롤백되게 만들었다.** 세 가지가 한 DB의 한 트랜잭션을 공유하므로 "다 성공하거나 다 없던 일"이
> 공짜로 보장된다(ACID). 여기에 동시 주문이 재고를 초과 판매하지 못하도록 **비관적 락**을 걸었다.

---

## 1. 왜 이 단계인가? (직전까지의 문제)

MSA를 배우는데 **왜 모놀리스부터** 시작할까? "나중에 분산되면 잃어버릴 것을 먼저 눈으로 보기 위해서"다.

주문 처리는 사실 세 가지 일이 묶여 있다.
1. **주문**을 만든다.
2. 주문한 만큼 **재고**를 뺀다.
3. 결제를 **캡처**한다(돈을 받는다).

이 셋은 **전부 성공하거나 전부 없던 일이 되어야** 한다. 재고만 빠지고 결제가 실패하면 상품은
사라졌는데 돈은 못 받은 상태다. 결제만 되고 재고가 모자라면 팔 수 없는 걸 팔아버린 것이다.

Phase 1(이 단계)에서는 세 가지가 **모두 order-service 한 곳, 한 데이터베이스 안**에 있다.
그래서 **DB 트랜잭션 하나로 원자성을 공짜로 얻는다**(아래 §2). 이게 기준선(baseline)이다.

> ⚠️ **이 공짜는 곧 사라진다.** Phase 2-1에서 payment-service를 분리하고, Phase 2-2에서 order가
> 결제를 **원격 호출**로 바꾸는 순간, 결제는 다른 서비스·다른 DB로 나간다. 그러면 이 `@Transactional`은
> 원격 결제를 더 이상 감싸지 못한다 → "결제 거절 시 재고 자동 롤백"이 깨진다. **이 잃어버린 원자성이
> Phase 12 Saga의 존재 이유**다. Phase 1은 "잃기 전의 모습"을 코드로 박제해 두는 단계다.

---

## 2. 핵심 개념

### 2.1 트랜잭션과 ACID
**트랜잭션**은 "여러 DB 작업을 하나의 단위로 묶는 것"이다. 은행 계좌이체(출금+입금)처럼 중간에
멈추면 안 되는 작업에 쓴다. 성질을 머리글자로 **ACID**라 부른다.

| 글자 | 뜻 | 이 단계에서의 의미 |
|---|---|---|
| **A**tomicity(원자성) | 다 되거나 다 안 됨 | 결제 거절 → 앞서 뺀 재고도 원복 |
| **C**onsistency(일관성) | 규칙을 깨지 않음 | 재고는 음수가 될 수 없음(불변식) |
| **I**solation(격리성) | 동시 작업이 서로 안 밟음 | 동시 주문이 같은 재고를 초과 차감 못 함 |
| **D**urability(지속성) | 커밋되면 남음 | 커밋된 주문은 재시작해도 유지 |

`@Transactional`이 붙은 메서드는 **정상 종료하면 커밋, 런타임 예외가 나가면 롤백**된다.
Phase 1의 핵심은 세 작업을 이 한 메서드 안에 넣어 **원자성**을 얻는 것이다.

### 2.2 동시성 문제 — lost update / oversell
재고 5개인 상품에 **20명이 동시에** "1개 주문"을 넣으면 어떻게 될까? 순진한 코드
(`읽기 → 계산 → 쓰기`)는 이렇게 깨진다.

```
스레드A: 재고 읽음(5)  ─┐
스레드B: 재고 읽음(5)  ─┘  둘 다 "5"를 봤다
스레드A: 4로 씀
스레드B: 4로 씀   ← A가 뺀 걸 못 봐서 덮어씀 → 2개 팔았는데 재고는 1만 줄어듦
```

이게 **lost update**이고, 결과가 **oversell**(재고보다 많이 팔림)이다.

### 2.3 비관적 락(PESSIMISTIC_WRITE)
해결책은 "읽는 순간부터 그 행을 잠가서, 내가 끝날 때까지 남이 못 읽게" 하는 것이다.
SQL로는 `SELECT … FOR UPDATE`. 이걸 **비관적 락**이라 한다("충돌이 날 거라 비관하고 미리 잠근다").
잠긴 행에 접근하려는 다른 트랜잭션은 **줄 서서 기다린다**(직렬화). 그래서 위 그림에서 B는 A가
커밋할 때까지 멈춰 있다가, A가 뺀 **4를 보고** 3으로 뺀다. oversell이 사라진다.

> 반대는 **낙관적 락**(`@Version`+충돌 시 재시도)이다. Phase 1은 확실·단순한 비관적 락을 택했다.

### 2.4 헥사고날 출력 모델(*View)과 순수 도메인
이 단계는 헥사고날 아키텍처 위에 얹혀 있다(세부는 `HEXAGONAL.md`). Phase 1에서 새로 지킨 규칙:
- **인바운드 포트가 가변 도메인(Order)이 아니라 불변 뷰(`OrderView`/`StockView`)를 반환**한다 →
  도메인 애그리거트가 웹 어댑터로 새지 않는다.
- **커스텀 조회는 QueryDSL**로 한다. 리포지토리 인터페이스에 JPQL `@Query`/`@Lock`/`@EntityGraph`를
  쓰지 않는다(쿼리 로직을 어댑터의 QueryDSL 클래스에 모은다).
- **Lombok은 컴파일타임 전용**(`@Getter`/`@NoArgsConstructor`/`@RequiredArgsConstructor`만).
  JPA에서 위험한 `@ToString`/`@EqualsAndHashCode`/`@Data`는 쓰지 않는다
  (JPA 양방향 연관·지연 로딩에서 `@ToString`/`@EqualsAndHashCode`가 서로 참조하는 엔티티를
  무한 순회하거나 불필요한 쿼리를 유발하기 때문).

### 2.5 헥사고날 최소 지도 (§4 코드를 읽기 전에)
§4의 코드가 어느 계층인지 헷갈리지 않도록, 헥사고날 어휘를 최소한으로 정리한다(세부는 `HEXAGONAL.md`).
- **유스케이스(UseCase)**: 한 트랜잭션 단위의 비즈니스 로직(예: `OrderService.placeOrder`).
- **포트(Port)**: 유스케이스가 바깥과 대화하는 인터페이스. **인바운드 포트**는 들어오는 요청의
  진입점 계약(`PlaceOrderUseCase`), **아웃바운드 포트**는 DB·외부로 나가는 계약(`ReserveStockPort` 등 `*Port`)이다.
- **어댑터(Adapter)**: 포트의 실제 구현(기술 세부). **인바운드 어댑터**는 웹 컨트롤러(`OrderController`),
  **아웃바운드 어댑터**는 JPA 영속화(`StockPersistenceAdapter`)처럼 특정 기술로 포트를 구현한다.
- **애그리거트(Aggregate)**: 일관성 경계를 가진 도메인 객체 묶음(여기선 `Order`가 `Payment`·`Item`을 소유).
- 의존성은 항상 **안쪽 도메인**을 향한다: 어댑터 → 포트 → 유스케이스 → 도메인.

---

## 3. 이 단계의 구성

```
  클라이언트
     │ HTTP :8080
     ▼
  ┌──────────────────────────────────────────────────────────────────┐
  │ order-service (8080)                                               │
  │                                                                    │
  │  OrderController  ──▶  PlaceOrderUseCase  ──▶  OrderService        │
  │  [인바운드 어댑터]     [인바운드 포트]          [유스케이스]       │
  │                                                     │              │
  │                        ┌────────────────────────────┴───────────┐ │
  │                        │ @Transactional (하나)                   │ │
  │                        │  1) 재고 차감(비관적 락)                │ │
  │                        │  2) 결제 캡처(.99 거절)                 │ │
  │                        │  3) 주문 저장                           │ │
  │                        └────────────────────────────┬───────────┘ │
  │                                                      │             │
  │  ReserveStockPort / SaveOrderPort  ──▶  StockPersistenceAdapter    │
  │  [아웃바운드 포트]                       OrderPersistenceAdapter   │
  │                                          [아웃바운드 어댑터]       │
  └──────────────────────────────────────────────────────┬───────────┘
                                                          ▼
                              ┌───────────────────────────┐
                              │   orderdb (PostgreSQL 18)  │
                              │   orders / order_items     │
                              │   stock_items / payments   │  ← 넷 다 한 DB (Phase 1)
                              └───────────────────────────┘
```
> 위 흐름이 §4 코드의 지도다: 컨트롤러(인바운드 어댑터) → 유스케이스(인바운드 포트/`OrderService`) →
> 아웃바운드 포트(`*Port`) → 아웃바운드 어댑터(`*PersistenceAdapter`) → DB. §4.1~4.4가 이 상자들이다.
> ([...]는 헥사고날 계층 이름, →는 호출 방향. 세부 정의는 §2.5.)

- **한 서비스, 한 DB**다. 결제·재고가 별도 서비스로 나가 있지 않다.
- `payments` 테이블은 **이 단계에만** 존재한다. **Phase 2-2(`72bc785`)의 V3 마이그레이션에서 drop**되고
  `orders`에 `payment_id` 컬럼만 남는다.
- 게이트웨이/Eureka/보안은 아직 없다. 클라이언트가 8080을 직접 부른다.

---

## 4. 코드/설정 — 한 부분씩 해설

> 아래는 리팩터 커밋(`c34f1be`)과 문서 커밋(`b543669`) 시점, 즉 **Phase 1이 끝난 상태**의 코드다.
> 초기 커밋(`374dc47`)과 달라진 부분은 그때마다 표시한다.

### 4.1 유스케이스 — 단일 트랜잭션 (`OrderService.placeOrder`)
```java
@Override
@Transactional
public OrderView placeOrder(PlaceOrderCommand command) {
    Order order = Order.create(command.customerId());
    command.items().forEach(i -> order.addItem(i.productId(), i.quantity(), i.unitPrice()));

    // (1) 재고 예약 — 상품ID 정렬 순서로 차감(어댑터가 비관적 락 적용)
    Map<UUID, Integer> quantityByProduct = new TreeMap<>();
    command.items().forEach(i ->
            quantityByProduct.merge(i.productId(), i.quantity(), Integer::sum));
    quantityByProduct.forEach(reserveStockPort::reserve);

    // (2) 결제 캡처 — 거절되면 PaymentDeclinedException → (1)의 재고 차감까지 롤백
    order.capturePayment();

    // (3) 주문 저장(주문+항목+결제 한 번에). 같은 트랜잭션이므로 위 단계와 원자적
    return OrderView.from(saveOrderPort.save(order));
}
```
읽을 포인트 세 가지.
- **`@Transactional` 하나가 (1)(2)(3)을 감싼다.** 어느 단계든 런타임 예외가 나가면 전부 롤백.
- **`TreeMap`으로 상품ID를 정렬**한 뒤 차감한다 → 다중 상품 주문이 서로 다른 순서로 락을 잡다
  생기는 **교착(deadlock)을 회피**한다(모든 트랜잭션이 항상 같은 순서로 락을 잡음). `merge`로 같은
  상품 중복 수량을 합산해 **한 번만** 차감한다.
- **반환은 `OrderView`**(불변 뷰)다. 가변 도메인 `Order`를 그대로 내보내지 않는다.

> **초기 커밋(`374dc47`)과의 차이**: 그때는 `placeOrder`가 도메인 `Order`를 직접 반환했고, 재고
> 차감이 `loadStockPort.load → stock.reserve → saveStockPort.save`(락 없음, `LinkedHashMap`)였다.
> 원본 코드의 주석 스스로 *"락/@Version 이 없어 동시 주문 시 lost-update(oversell) 가능"* 이라고
> 인정하고 있었다. `c34f1be`이 이 한계를 비관적 락으로 닫았고, `SaveStockPort`를 **제거**하고
> `ReserveStockPort`로 대체했다.

### 4.2 아웃바운드 포트 — 의도만 표현 (`ReserveStockPort`)
```java
public interface ReserveStockPort {
    /** 재고를 차감한다. 부족하면 InsufficientStockException, 미등록 상품이면 StockNotFoundException. */
    void reserve(UUID productId, int quantity);
}
```
애플리케이션 계층은 **"예약한다"는 의도만** 안다. **락 전략(비관적/낙관적)은 어댑터의 구현 세부**로
숨겼다. 그래서 나중에 락 방식을 바꿔도 유스케이스 코드는 그대로다(포트=계약, 어댑터=방법).

### 4.3 어댑터 — load-then-mutate (`StockPersistenceAdapter.reserve`)
> **(배경 — JPA 영속성 컨텍스트)** JPA는 트랜잭션 동안 조회한 엔티티를 **영속성 컨텍스트**(persistence
> context, 트랜잭션 내 엔티티 캐시)가 **관리(managed)** 한다. 이 managed 엔티티의 필드를 바꾸면 JPA가
> 변경을 감지(**dirty checking**)해 커밋 시 자동으로 `UPDATE`를 날린다 — 그래서 별도의 `save` 호출이
> 필요 없다. 반면 **`merge`** 는 관리 밖(detached) 엔티티를 다시 컨텍스트에 붙이는 별도 API다.
```java
@Override
public void reserve(UUID productId, int quantity) {
    // 비관적 쓰기 락으로 행을 잠근 채 '관리(managed)' 엔티티를 로드 → 도메인 규칙으로 차감 →
    // 같은 managed 엔티티를 직접 수정해 dirty checking 으로 UPDATE(락 걸린 그 행에 그대로 반영).
    StockItemJpaEntity managed = queryRepository.findByProductIdForUpdate(productId)
            .orElseThrow(() -> new StockNotFoundException(productId));
    StockItem stock = toDomain(managed);
    stock.reserve(quantity);   // 부족하면 InsufficientStockException → 트랜잭션 롤백
    managed.setAvailableQuantity(stock.getAvailableQuantity());
}
```
핵심은 **load-then-mutate, merge 금지**다.
- `findByProductIdForUpdate`로 **락 걸린 상태의 관리(managed) 엔티티**를 가져온다.
- 도메인 규칙(`stock.reserve`)으로 수량을 검증·계산한다.
- **같은 managed 엔티티를 직접 수정**한다 → JPA **dirty checking**이 트랜잭션 커밋 시 그 행에
  `UPDATE`를 낸다. **새 엔티티를 `merge`하지 않는다.**

왜 merge를 금지할까? `merge(새 엔티티)`는 락과 무관한 별도 경로라, "락 잡은 행"과 "쓰는 행"이
분리될 수 있다. load-then-mutate는 **락 ↔ UPDATE 결합이 구조적으로 보장**된다(잠근 그 행을 그대로 씀).

### 4.4 락 쿼리 — QueryDSL `SELECT … FOR UPDATE` (`StockQueryRepository`)
```java
Optional<StockItemJpaEntity> findByProductIdForUpdate(UUID productId) {
    QStockItemJpaEntity stock = QStockItemJpaEntity.stockItemJpaEntity;
    StockItemJpaEntity result = query.selectFrom(stock)
            .where(stock.productId.eq(productId))
            .setLockMode(LockModeType.PESSIMISTIC_WRITE)   // = SELECT … FOR UPDATE
            .fetchOne();
    return Optional.ofNullable(result);
}
```
`QStockItemJpaEntity`(Q타입)는 QueryDSL 애노테이션 프로세서가 `StockItemJpaEntity`로부터
**컴파일 타임에 생성**하는 타입세이프 쿼리용 클래스다(그래서 손으로 만들거나 import할 필요가 없다).
`LockModeType.PESSIMISTIC_WRITE`가 곧 `SELECT … FOR UPDATE`다. **읽는 시점부터** 행을 잠가
동시 차감의 lost update를 막는다. 리포지토리 인터페이스에 `@Lock`을 붙이는 대신 **QueryDSL로
명시적**으로 표현했다(§2.4 규칙). `JPAQueryFactory` 빈은 `QuerydslConfig`가 현재 트랜잭션의
`EntityManager`로 만든다.

### 4.5 순수 도메인 — 재고/결제 불변식
**`StockItem.reserve`** (음수 재고 원천 차단):
```java
public void reserve(int quantity) {
    if (quantity <= 0) throw new IllegalArgumentException(...);
    if (quantity > availableQuantity)
        throw new InsufficientStockException(productId, quantity, availableQuantity);
    this.availableQuantity -= quantity;
}
```
**`Order.capturePayment`** (가짜 게이트웨이 stub — 롤백 시연용):
```java
private static final BigDecimal DECLINE_REMAINDER = new BigDecimal("0.99");
...
public void capturePayment() {
    if (status != OrderStatus.PENDING) throw new IllegalStateException(...);
    if (items.isEmpty())               throw new IllegalStateException(...);
    if (totalAmount.remainder(BigDecimal.ONE).compareTo(DECLINE_REMAINDER) == 0)
        throw new PaymentDeclinedException(totalAmount);   // 합계가 .99로 끝나면 거절
    this.payment = Payment.capture(totalAmount);
    this.status = OrderStatus.CONFIRMED;
}
```
> `remainder(BigDecimal.ONE)`은 금액을 1로 나눈 나머지, 즉 **소수부**를 뽑는다(예: 9.99 → 0.99).
> 그 소수부가 정확히 `DECLINE_REMAINDER`(=0.99)면 거절 → 따라서 **합계가 X.99인 모든 주문
> (9.99, 19.99, 29.99…)** 이 결제 거절 시연을 트리거한다. §5.2·§7의 "9.99 → 402"가 여기서 나온다.
> **`.99 거절`은 진짜 불변식이 아니라 외부 PG를 흉내낸 stub**이다(주석이 명시). "결제가 거절되면
> 어떻게 되나(재고 롤백)"를 결정론적으로 시연하기 위한 것이며, **Phase 2에서 원격 payment-service로
> 빠져나갈 seam**이다. 이 결제는 Phase 1에서 **Order 애그리거트가 직접 소유**한다(`payments` 테이블).

### 4.6 출력 모델 (`OrderView`)
```java
public record OrderView(UUID id, UUID customerId, OrderStatus status, BigDecimal totalAmount,
                        Instant createdAt, List<Item> items, PaymentView payment) {
    public record Item(UUID productId, int quantity, BigDecimal unitPrice) {}
    public record PaymentView(BigDecimal amount, PaymentStatus status, Instant capturedAt) {}
    public static OrderView from(Order order) { ... }   // 도메인 → 뷰 매핑(애플리케이션 책임)
}
```
불변 `record`다. 컨트롤러(`OrderController`)는 이 뷰를 그대로 응답하고, **도메인 타입은 웹 어댑터에
들어오지 않는다.** 도메인→뷰 매핑은 애플리케이션 계층 책임(`OrderView.from`).

### 4.7 예외 → HTTP (`ApiExceptionHandler`)
```java
@ExceptionHandler(InsufficientStockException.class)   // 재고 부족 → 409 Conflict
@ExceptionHandler(PaymentDeclinedException.class)      // 결제 거절 → 402 Payment Required
@ExceptionHandler({OrderNotFoundException, StockNotFoundException})  // → 404
@ExceptionHandler(IllegalArgumentException.class)      // 도메인 불변식 위반 → 400
```
도메인 예외를 Spring `ProblemDetail`로 매핑한다. 이 매핑이 §7의 상태코드(201/402/409)를 만든다.

### 4.8 스키마 — Flyway가 소유 (`ddl-auto=validate`)
> **Flyway**는 `V1__…`, `V2__…`처럼 번호 붙은 SQL 파일을 **순서대로 한 번씩** 적용해 DB 스키마를
> 버전 관리하는 도구다. 여기선 **V1**이 `orders`/`order_items`를, **V2**가 `stock_items`/`payments`를 만든다.

`application.yml`:
```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate     # 스키마는 Flyway가 소유. Hibernate는 검증만(불일치 시 부팅 실패)
    open-in-view: false
  flyway:
    enabled: true
server:
  port: 8080
  shutdown: graceful
```
**V1**(`orders`, `order_items`)과 **V2**(`stock_items`, `payments` + 시드 재고 각 100개)가 스키마를
만든다. Hibernate는 **검증만** 한다(엔티티≠스키마면 부팅 실패 → 빠른 피드백).

> **V2가 이 단계의 지문**이다. `stock_items`와 `payments`가 order-service DB에 있다는 것 자체가
> "재고·결제를 이 서비스가 직접 소유"한다는 증거다. **Phase 2-2의 V3(`72bc785`)에서
> `DROP TABLE payments; ALTER TABLE orders ADD COLUMN payment_id UUID;`** 로 결제가 빠져나간다.

### 4.9 빌드 — Lombok · QueryDSL · Testcontainers (`build.gradle.kts`)
```kotlin
// Lombok(compile-time only). annotationProcessor 순서상 QueryDSL APT 보다 먼저 선언.
compileOnly("org.projectlombok:lombok"); annotationProcessor("org.projectlombok:lombok")
// QueryDSL — jakarta classifier 필수
implementation(variantOf(libs.querydsl.jpa) { classifier("jakarta") })
annotationProcessor(variantOf(libs.querydsl.apt) { classifier("jakarta") })
// 동시성(oversell) 회귀 가드 — 실제 PostgreSQL 컨테이너 필요
testImplementation("org.springframework.boot:spring-boot-testcontainers")
testImplementation("org.testcontainers:junit-jupiter"); testImplementation("org.testcontainers:postgresql")
```
버전은 `gradle/libs.versions.toml`이 관리한다(Spring Boot **3.5.15**, QueryDSL **5.1.0**, 컴파일 타깃 **Java 21**).

> **(빌드 세부 — 처음이면 참고만)** `annotationProcessor` 선언 순서에서 Lombok을 QueryDSL APT보다 먼저
> 두는 이유는 Lombok이 생성한 코드(예: getter)를 QueryDSL이 Q타입 생성 시 읽을 수 있게 하기 위함이다.
> `jakarta` classifier가 필수인 이유는 Spring Boot 3 / Jakarta EE 9+가 패키지를 `javax.*` → `jakarta.*`로
> 옮겼고, QueryDSL이 그 네임스페이스에 맞춘 별도 아티팩트를 `jakarta` classifier로 제공하기 때문이다.

---

## 5. 동작 흐름

### 5.1 해피 패스 `POST /orders`
```
1) 클라이언트 ──POST :8080/orders {items:[...]}──▶ OrderController.place
2) @Transactional 시작
3) Order.create → addItem(합계 계산)                       (status=PENDING)
4) reserveStockPort.reserve : SELECT … FOR UPDATE 로 재고 행 잠금
                              → stock.reserve(수량) → dirty checking UPDATE
5) order.capturePayment() : 합계가 .99 아님 → Payment CAPTURED, status=CONFIRMED
6) saveOrderPort.save : orders/order_items/payments INSERT (cascade = 부모 order 저장 시 order_items·payment 함께 INSERT)
7) 커밋  → OrderView(201 CREATED, CONFIRMED, payment 포함) 반환
```

### 5.2 결제 거절(롤백) — 합계가 `.99`
```
4) 재고 이미 차감(트랜잭션 안, 아직 커밋 전)
5) order.capturePayment() → 합계 .99 → PaymentDeclinedException  ↑ 던짐
   → @Transactional 롤백 → 4)에서 뺀 재고 UPDATE 도 함께 원복
   → ApiExceptionHandler → 402 Payment Required
```
**여기가 Phase 1의 핵심 시연**이다. 재고 차감과 결제가 **한 트랜잭션**이라, 결제가 거절되면
재고가 **자동으로** 되돌아온다. (Phase 2-2 이후엔 이게 안 되므로 Saga가 필요해진다.)

### 5.3 재고 부족
```
4) stock.reserve(수량>재고) → InsufficientStockException → 롤백 → 409 Conflict
```

---

## 6. 동작 원리 더 깊게 / 트레이드오프

- **왜 `@Transactional` 하나로 원자성이 공짜인가**: 세 작업이 **같은 DB의 같은 커넥션·같은 트랜잭션**을
  쓰기 때문. DB가 커밋/롤백을 원자적으로 보장한다. 서비스가 나뉘면 **DB가 나뉘어** 이 전제가 깨진다.
- **비관적 락의 대가**: 잠긴 행을 노리는 트랜잭션은 **직렬화(줄서기)** 된다 → 정확하지만 **처리량이
  떨어진다**. 인기 상품에 주문이 몰리면 병목이 될 수 있다. 대안(원자적 조건부 UPDATE
  `SET qty=qty-? WHERE qty>=?`, 낙관적 락)은 더 빠르지만 로직/재시도가 복잡하다. Phase 1은
  **정확성·단순성**을 우선했다.
- **교착 회피는 정렬로만**: 여러 상품을 살 때 두 주문이 상품A·B를 **엇갈린 순서**로 잠그면 교착이
  생긴다. `TreeMap` 정렬로 **항상 같은 순서**로 잠가 원천 차단했다(락을 더 쓰지 않고 순서만 통제).
- **`open-in-view: false`**: 뷰 렌더링까지 영속성 세션(영속성 컨텍스트)을 열어두지 않는다는 설정이다.
  세션이 트랜잭션 종료와 함께 닫히므로, **지연 로딩**(lazy loading — 연관 엔티티를 실제 접근 시점에
  뒤늦게 DB에서 가져오는 방식)을 트랜잭션 밖에서 시도하면 `LazyInitializationException`이 난다. 그래서
  조회는 **QueryDSL fetch join**으로 필요한 걸 트랜잭션 안에서 다 로드해야 한다(지연 로딩 예외 방지).
  `OrderPersistenceAdapter`가 `findByIdWithDetails`/`findAllWithDetails`로 처리한다.
- **저장은 INSERT 전용(Phase 1)**: `save`는 `id=null`인 신규 주문을 넣고 `@GeneratedValue(UUID)`
  (JPA가 INSERT 시 UUID 식별자를 자동 부여하는 애노테이션)가 식별자를 부여한다. 상태 전이
  UPDATE(load-then-mutate)는 Phase 12/13에서 도입된다.

---

## 7. 검증 (그때 어떻게 확인했나)

**단위 테스트(도메인, Spring/DB 불필요)** — `OrderTest`/`StockItemTest`:
- 합계 계산, 수량/단가 ≤ 0 거부, 캡처 시 CONFIRMED, **합계 9.99 → `PaymentDeclinedException`(상태 미변경)**.

**동시성 통합 테스트** — `StockConcurrencyTest` (Testcontainers, 실제 PostgreSQL, Docker 필요):
```java
@SpringBootTest(webEnvironment = NONE) @Testcontainers
// 재고 5개에 20스레드가 동시에 1개씩 주문(CountDownLatch로 동시 출발)
assertThat(confirmed.get()).isEqualTo(initialStock);                 // 정확히 5건만 성공
assertThat(getStock.getStock(product).availableQuantity()).isZero(); // 재고 0, 음수(oversell) 없음
```
이 테스트가 **비관적 락이 oversell을 막는다는 실증적 회귀 가드**다.

**수동 스모크(curl)** — 인프라 DB 띄우고 앱 실행 후:
```bash
docker compose -f deploy/compose/compose.infra.yml up -d       # orderdb(5432)
./gradlew :services:order-service:bootRun                      # 8080

# (1) 해피 패스 → 201 CONFIRMED
curl -i -X POST http://localhost:8080/orders -H 'Content-Type: application/json' \
  -d '{"customerId":"11111111-1111-1111-1111-111111111111",
       "items":[{"productId":"22222222-2222-2222-2222-222222222222","quantity":1,"unitPrice":"10.00"}]}'

# (2) 결제 거절: 합계가 .99 → 402 + 재고 롤백(차감 안 됨)
#     예: 9.99  → 402 Payment Required
curl -i -X POST http://localhost:8080/orders -H 'Content-Type: application/json' \
  -d '{"customerId":"11111111-1111-1111-1111-111111111111",
       "items":[{"productId":"22222222-2222-2222-2222-222222222222","quantity":1,"unitPrice":"9.99"}]}'

# (3) 재고 부족: 재고(100)보다 큰 수량 → 409 Conflict
curl -i -X POST http://localhost:8080/orders -H 'Content-Type: application/json' \
  -d '{"customerId":"11111111-1111-1111-1111-111111111111",
       "items":[{"productId":"22222222-2222-2222-2222-222222222222","quantity":999,"unitPrice":"10.00"}]}'

# 재고 확인(주문 전후 비교)
curl -s http://localhost:8080/inventory/22222222-2222-2222-2222-222222222222
```
> **(2)의 핵심**: 402가 나온 뒤 `/inventory`로 확인하면 재고가 **그대로**다. 결제 거절이 재고 차감을
> 같은 트랜잭션에서 **자동 롤백**했다는 증거 — Phase 1이 증명하려는 바로 그것이다.

---

## 8. 알려진 한계 → 해결 Phase

| 한계 / 트레이드오프 | 성격 | 해결 Phase |
|---|---|---|
| **결제가 order-service 로컬**(가짜 stub, `payments` 테이블) — 진짜 PG·별도 서비스 아님 | 분리 | **Phase 2-1**(payment-service) → **Phase 2-2**(원격 호출, V3에서 `payments` drop) |
| **단일 트랜잭션 원자성은 "한 DB" 전제** — 서비스 분리 시 결제 거절→재고 자동 롤백이 깨짐 | 분산 일관성 | **Phase 12**(Saga: 보상 트랜잭션) |
| 진입점·인증 없음 — 8080 직접 호출, 누구나 호출 가능 | 게이트웨이/보안 | **Phase 3**(게이트웨이) · **Phase 5**(JWT) |
| 서비스 주소 하드코딩(아직 서비스가 하나뿐) | 디스커버리 | **Phase 4**(Eureka) |
| 비관적 락은 정확하지만 **인기 상품에서 직렬화 병목** 가능 | 성능 | 후속(낙관적 락/조건부 UPDATE 비교) |
| 상태 전이가 INSERT 전용(주문 확정만) — UPDATE 경로 미구현 | 영속 | **Phase 12/13**(load-then-mutate 상태 전이) |
| DB 비밀번호가 설정에 평문 하드코딩 | 시크릿 | **Phase 6**(Config) · **Phase 7**(compose/keystore) |
| 관측성 없음(로그/추적/헬스 상세 공개) | 관측성 | **Phase 8** |

---

## 9. 용어 사전

- **트랜잭션 / ACID**: 여러 DB 작업을 하나로 묶는 단위. 원자성·일관성·격리성·지속성.
- **원자성(Atomicity)**: "다 되거나 다 안 됨." Phase 1이 결제 거절 시 재고를 자동 원복하는 근거.
- **비관적 락(PESSIMISTIC_WRITE)**: `SELECT … FOR UPDATE`. 읽는 순간부터 행을 잠가 동시 차감을 직렬화.
- **낙관적 락**: `@Version`으로 충돌을 커밋 시점에 감지·재시도(비관적의 반대). 여기선 미채택.
- **lost update / oversell**: 동시 읽기-쓰기로 갱신이 덮어써져 재고보다 많이 팔리는 현상.
- **교착(deadlock)**: 두 트랜잭션이 서로가 쥔 락을 기다려 멈춤. 락 순서를 통일해 회피.
- **load-then-mutate**: 관리(managed) 엔티티를 로드해 직접 수정 → dirty checking으로 UPDATE. `merge` 금지.
- **영속성 컨텍스트(persistence context)**: 트랜잭션 동안 조회한 엔티티를 관리하는 JPA의 엔티티 캐시.
- **managed(관리) 엔티티**: 영속성 컨텍스트가 추적 중인 엔티티. 필드 변경이 dirty checking으로 UPDATE된다.
- **detached(비관리) 엔티티**: 컨텍스트 밖 엔티티. 변경해도 자동 반영 안 됨. 다시 붙이려면 `merge`가 필요.
- **`merge`**: detached 엔티티를 영속성 컨텍스트에 다시 붙이는 JPA API. 락과 무관한 별도 경로라 §4.3에서 금지.
- **dirty checking**: JPA가 managed 엔티티의 변경을 감지해 커밋 시 UPDATE를 자동 발행.
- **유스케이스(UseCase)**: 한 트랜잭션 단위의 비즈니스 로직(예: `OrderService`).
- **포트(Port) / 어댑터(Adapter)**: 포트=유스케이스가 바깥과 대화하는 인터페이스(계약), 어댑터=포트의 실제 구현(기술 세부).
- **인바운드 / 아웃바운드**: 인바운드=들어오는 요청 진입점(컨트롤러·UseCase), 아웃바운드=DB·외부로 나가는 계약(`*Port`)과 그 구현.
- **애그리거트**: 일관성 경계를 가진 도메인 객체 묶음(여기선 Order가 Payment·Item을 소유).
- **출력 모델(\*View)**: 유스케이스가 도메인 대신 반환하는 불변 read model. 도메인 누수 방지.
- **QueryDSL / Q타입**: 타입세이프 쿼리 라이브러리. `QStockItemJpaEntity` 같은 Q타입은 애노테이션 프로세서가 엔티티로부터 컴파일 타임에 생성. 리포지토리 JPQL/@Lock 대신 사용.
- **cascade**: 연관 엔티티 전이 저장. 부모 저장 시 자식(order_items·payment)도 함께 INSERT.
- **`@GeneratedValue`(UUID)**: JPA가 INSERT 시 UUID 식별자를 자동 부여하는 애노테이션.
- **지연 로딩(lazy loading) / `open-in-view=false`**: 연관 엔티티를 접근 시점에 뒤늦게 로드하는 방식. `open-in-view=false`면 트랜잭션 밖 지연 로딩 시 `LazyInitializationException` → fetch join으로 미리 로드해야 함.
- **Flyway / `ddl-auto=validate`**: 번호 붙은 SQL(V1__, V2__…)을 순서대로 적용해 스키마를 버전 관리. Hibernate는 검증만.

---

## 10. 더 알아보기 (공식 문서)

- Spring `@Transactional` / 트랜잭션: https://docs.spring.io/spring-framework/reference/data-access/transaction.html
- JPA 락 모드(PESSIMISTIC_WRITE): https://jakarta.ee/specifications/persistence/3.1/
- QueryDSL: http://querydsl.com/
- Flyway: https://documentation.red-gate.com/fd
- Testcontainers(JUnit 5): https://java.testcontainers.org/

---

*관련 문서: [HEXAGONAL.md](HEXAGONAL.md)(아키텍처 컨벤션 — 포트/어댑터·출력모델·load-then-mutate 세부), [SERVICE-DISCOVERY.md](SERVICE-DISCOVERY.md)(Phase 4), [SECURITY.md](SECURITY.md)(Phase 5). 전체 로드맵: 루트 `MSA-LEARNING-PLAN.md`.*
