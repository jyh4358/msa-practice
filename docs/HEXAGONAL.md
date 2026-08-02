# 헥사고날 아키텍처 컨벤션 (모든 서비스 공통)

이 프로젝트의 **모든 마이크로서비스는 헥사고날(Ports & Adapters) 아키텍처**로 구현한다.
(Tom Hombergs, 『만들면서 배우는 클린 아키텍처』 스타일). `order-service`가 레퍼런스 구현이다.

> 핵심 원칙: **의존성은 항상 안쪽(도메인)을 향한다.** 도메인은 바깥(프레임워크·DB·웹·메시징)을 모른다.
> 바깥 기술은 전부 "어댑터"로 갈아끼울 수 있는 세부사항이다.

---

## 1. 패키지 구조 (서비스마다 동일)

```
com.shopsaga.<service>
├── domain/                      # 순수 도메인. java.* 외 import 금지(특히 Spring·JPA 금지)
│   ├── <Aggregate>.java         #   애그리거트 루트 (create()/restore() 팩토리)
│   ├── <ValueObject>.java       #   값 객체 (생성자에서 불변식 검증)
│   └── <Enum>.java
├── application/
│   ├── <X>Stereotype.java       # @UseCase (= @Component) 등
│   ├── port/in/                 # 인바운드 포트(인터페이스) + Command/Query 모델
│   │   ├── <Verb>UseCase.java   #   커맨드 측 (예: PlaceOrderUseCase)
│   │   ├── <Verb>Command.java   #   인바운드 입력 모델(웹 DTO와 분리)
│   │   └── <Get>Query.java      #   쿼리 측
│   ├── port/out/                # 아웃바운드 포트(인터페이스)
│   │   ├── Save<X>Port.java
│   │   ├── Load<X>Port.java
│   │   └── Publish<X>EventPort.java   # (메시징 단계에서)
│   └── service/                 # 유스케이스 구현(@UseCase, @Transactional) + 애플리케이션 예외
│       ├── <X>Service.java      #   in-port 구현, out-port에만 의존 (package-private)
│       └── <X>NotFoundException.java  # 어댑터가 번역하는 예외 → public
└── adapter/
    ├── in/
    │   ├── web/                 # REST 컨트롤러 + 요청/응답 DTO + ExceptionHandler
    │   └── messaging/           # (Kafka) @KafkaListener 소비자 → in-port 호출
    └── out/
        ├── persistence/         # JPA 엔티티·리포지토리·어댑터·매퍼 (out-port 구현)
        └── messaging/           # 이벤트 발행 / outbox 릴레이 (out-port 구현)
```

공유: `shared/events/`(이벤트 계약 POJO, **JPA·Spring 금지**) — 메시징 어댑터만 참조, 도메인은 절대 참조 안 함.

---

## 2. 계층별 규칙

### domain (가장 안쪽 — 순수)
- `java.*`만 import. **Spring·JPA·Jackson·어떤 어댑터/애플리케이션 타입도 import 금지.**
- 애그리거트 루트는 **팩토리 2개**: `create(...)`(신규, id=null) / `restore(...)`(영속 복원, 어댑터 전용).
- **불변식은 도메인이 스스로 보호**한다 — 값 객체 생성자/메서드에서 검증(`IllegalArgumentException`). 웹 DTO의 `@Valid`에만 의존하지 않는다(웹 아닌 호출자도 있으므로).
- 상태 변경은 한 곳(예: `recalculateTotal()`)을 거치게 해 불변식 동기화를 보장.
- DB·프레임워크 없이 순수 단위 테스트 가능해야 한다.

### application (유스케이스)
- in-port를 구현하고 **out-port(인터페이스)에만 의존**한다 — 영속/메시징 기술을 모른다.
- 허용되는 유일한 Spring 참조: **`@UseCase`(=@Component) 와 `@Transactional`**. 그 외 Spring import 금지.
- **트랜잭션 경계는 애플리케이션 서비스가 소유**한다(@Transactional). 어댑터로 내리거나 컨트롤러로 올리지 않는다.
- **애그리거트당 애플리케이션 서비스 1개**가 그 애그리거트의 모든 in-port를 구현한다.
- 어댑터가 HTTP 등으로 번역하는 애플리케이션 예외(`*NotFoundException`)는 **public**, 유스케이스 구현체는 **package-private**.
- 포트 분리: 커맨드(`UseCase`) ↔ 쿼리(`Query`), 저장(`SavePort`) ↔ 조회(`LoadPort`). (Phase 11 CQRS 분리의 사전 포석)
- **인바운드 포트는 도메인 애그리거트가 아니라 불변 출력 모델(`*View`/result record)을 반환**한다. 애플리케이션 서비스가 `OrderView.from(order)`처럼 도메인→뷰로 매핑 → 가변 애그리거트가 어댑터로 새지 않고, 어댑터가 실수로 도메인 변경 메서드를 호출할 수 없다. (입력은 이미 `web DTO → Command → domain` 으로 안쪽 보호)

### adapter (가장 바깥 — 세부사항)
- **in/web**: 요청 DTO를 `*Command`로 변환해 in-port 호출, 응답은 포트가 준 **불변 뷰(`*View`)를 그대로 반환**(도메인 애그리거트는 웹 어댑터에 들어오지 않음 — 도메인 예외→HTTP 번역만 예외적으로 허용). **OpenAPI/Swagger 문서화(springdoc `@Tag`/`@Operation` + `OpenApiConfig`)도 여기에만** 둔다 — 도메인·애플리케이션은 springdoc에 의존하지 않는다.
- **in/messaging**: `@KafkaListener`가 이벤트를 받아 in-port 호출(멱등 처리는 Phase 10 outbox/처리이력 규칙).
- **out/persistence**: out-port 구현. **도메인↔JPA 매핑을 어댑터 안에서 끝낸다**(애플리케이션·웹은 LAZY를 모르게).
- **out/messaging**: 이벤트 발행/outbox 릴레이가 `Publish*EventPort`를 구현. 애플리케이션은 Kafka를 모른다.
- 어댑터 구현체는 가능하면 **package-private + @Component**(또는 @RestController/@RestControllerAdvice).

---

## 3. 영속 규칙 (자주 밟는 함정 — 반드시 준수)

1. **JPA 엔티티는 도메인과 분리**한다(`OrderJpaEntity` ≠ `Order`). 영속 어노테이션이 도메인을 오염시키지 않게.
2. **식별자 생성 전략**
   - DB 생성(`@GeneratedValue`): 도메인 id=null → `repository.save()`가 깨끗이 INSERT. (현재 order-service)
   - 앱 할당(도메인이 UUID 생성): `save()`가 merge로 빠져 매 저장마다 SELECT가 붙는다 →
     `Persistable<UUID>`(transient isNew) 구현 또는 `entityManager.persist()`로 INSERT 강제.
3. **저장 = INSERT 전용 아님.** 기존 애그리거트의 **상태 전이(예: PENDING→PAID)** 저장은
   **load-then-mutate**(관리 엔티티를 찾아 필드 갱신 → dirty checking으로 UPDATE)로 한다.
   새 엔티티를 만들어 `save()`하면 **새 행이 INSERT**된다. (Phase 12/13 Saga에서 핵심)
4. **커스텀 조회는 QueryDSL**(리포지토리 인터페이스에 JPQL `@Query`/`@Lock`/`@EntityGraph` 금지).
   `@Configuration JPAQueryFactory` 빈 + `<Aggregate>QueryRepository`(@Repository)에 작성. Q-클래스는 APT가 생성(deps: `querydsl-jpa:jakarta` + `querydsl-apt:jakarta` + jakarta persistence/annotation API as annotationProcessor).
   **`open-in-view: false`** 이므로 웹/메시징이 렌더링하는 연관은 **fetch join 으로 즉시 로딩**.
   - ⚠️ `@OneToMany` List를 fetch join + 목록 조회 → 카테시안 곱으로 루트 중복 → `.distinct()`(단건도 동일). `@OneToOne`은 곱 없음. **컬렉션 fetch join은 1개만**(2개면 MultipleBagFetchException).
5. **DB 스키마는 Flyway가 소유**(`ddl-auto: validate`). 엔티티 매핑과 마이그레이션이 정확히 일치해야 부팅된다.
6. **동시성(재고 등) = DB 비관적 락**(단일 서비스·단일 DB이므로 분산락 아님).
   - 락 메커니즘은 어댑터 뒤로 숨긴다: 애플리케이션은 `ReserveStockPort.reserve(id, qty)` 같은 **의도(intent)** 포트만 호출.
   - 어댑터: QueryDSL `setLockMode(PESSIMISTIC_WRITE)`로 **managed 엔티티를 잠근 채 로드 → 도메인 규칙 적용 → 그 managed 엔티티를 직접 수정(dirty checking으로 UPDATE)**. 새 엔티티 `merge` 금지(락↔UPDATE 결합이 깨져 lost-update 위험).
   - 여러 행을 잠그면 **전역 정렬 순서(예: id `TreeMap`)** 로 잠가 교착(deadlock) 회피 — 모든 writer가 동일 순서를 지켜야 함.
   - ⚠️ Phase 2: 락을 **원격 호출(결제 등) 구간까지 들고 가지 말 것**. 원격 호출 전에 커밋(락 해제)하고, 교차 서비스 일관성은 Saga(보상)로.
   - 회귀 가드로 **Testcontainers 동시성 테스트**를 함께 클론한다(N 동시 주문 → 정확히 재고만큼만 성공).

---

## 4. 에러 처리 컨벤션
- `ApiExceptionHandler`(@RestControllerAdvice)가 애플리케이션/도메인 예외를 HTTP로 번역.
  - `*NotFoundException` → 404
  - `IllegalArgumentException`(도메인 불변식 위반) → 400
  - Bean Validation(`@Valid`) 실패 → Spring Boot 기본 ProblemDetail 400에 위임
- 모든 서비스가 같은 핸들러 패턴을 복제해 응답 형태를 일관되게 유지.

---

## 5. 메시징(Kafka) 슬롯 — Phase 9+ 대비 미리 합의
- 소비: `adapter/in/messaging/<X>EventListener` → in-port 호출.
- 발행: `adapter/out/messaging/<X>EventPublisher` → `Publish*EventPort` 구현.
- **outbox 테이블·릴레이**는 `adapter/out/persistence`(+messaging)에 두고 **아웃바운드 포트 뒤**에 숨긴다 → 애플리케이션은 Kafka를 모름.
- 이벤트 POJO는 **`shared/events`에만**(JPA·Spring 금지). 도메인은 이벤트 타입을 참조하지 않는다.

## 6. Saga(Phase 12/13)
- `saga_instance`는 **자체 애그리거트**: `domain/saga/`(순수 상태 전이 switch — DB 없이 단위 테스트) + `application/port` + `adapter/out/persistence`.
- 오케스트레이터는 order-service 안에 둔다(계획 §3). 상태 전이 저장은 §3.3 load-then-mutate 규칙을 따른다.

---

## 7. 매퍼 / 스테레오타입 / Lombok
- **매퍼는 무상태 static 유틸**(`OrderMapper`) — 주입 불필요·테스트 쉬움. 협력자가 필요해지면 그때 package-private `@Component`로 승격.
- 스테레오타입: 애플리케이션 = `@UseCase`. (선택) 어댑터에 `@WebAdapter`/`@PersistenceAdapter`(둘 다 meta-@Component)를 두면 계층이 자기설명적이 된다 — 현재는 `@RestController`/`@Component` 사용.
- **Lombok** (버전은 Boot BOM 관리; `compileOnly` + `annotationProcessor`, annotationProcessor 선언은 **QueryDSL APT 보다 먼저**):
  - Spring 컴포넌트(서비스·어댑터·컨트롤러·쿼리리포지토리) → **`@RequiredArgsConstructor`**(final 필드 주입 생성자 제거).
  - JPA 엔티티 → **`@Getter` + `@NoArgsConstructor(access = PROTECTED)`**. ⚠️ `@Data`/`@Setter`/`@ToString`/`@EqualsAndHashCode` **금지**(지연로딩 재귀·식별자 문제).
  - 도메인 → **`@Getter`만**(불변성·행위·팩토리 보존; setter/data 금지). 커스텀 getter(예: `Order.getItems()` 불변 뷰)는 Lombok이 덮어쓰지 않으므로 그대로 유지.
  - record(Command/View/요청 DTO) → Lombok 불필요.

---

## 8. 레퍼런스: order-service 파일 맵
| 역할 | 파일 |
|---|---|
| 도메인 애그리거트 | `domain/Order.java` (create/restore, addItem→recalc) |
| 도메인 값 객체 | `domain/OrderItem.java` (생성자 불변식) |
| 인바운드 포트(커맨드) | `application/port/in/PlaceOrderUseCase.java` + `PlaceOrderCommand.java` |
| 인바운드 포트(쿼리) | `application/port/in/GetOrderQuery.java` |
| 아웃바운드 포트 | `application/port/out/{SaveOrderPort,LoadOrderPort}.java` |
| 유스케이스 구현 | `application/service/OrderService.java` (@UseCase, @Transactional) |
| 웹 어댑터 | `adapter/in/web/{OrderController,PlaceOrderRequest,ApiExceptionHandler}.java`(응답 DTO 없음 — 인바운드 포트가 반환한 `OrderView`를 그대로 응답) |
| 영속 어댑터 | `adapter/out/persistence/{OrderJpaEntity,OrderItemJpaEntity,OrderJpaRepository,OrderPersistenceAdapter,OrderMapper}.java` |

---

## 9. 새 서비스 만들 때 체크리스트
1. `domain/`에 애그리거트 + 값 객체(불변식) — 순수하게. 단위 테스트부터.
2. `application/port/in`·`out` 인터페이스 정의(커맨드/쿼리, 저장/조회 분리).
3. `application/service`에 유스케이스 구현(@UseCase, @Transactional, out-port 의존).
4. `adapter/out/persistence`: JpaEntity + Repository + Adapter + Mapper (§3 영속 규칙 준수).
5. `adapter/in/web`: Controller + DTO + ExceptionHandler.
6. Flyway `V1__init.sql`로 스키마. `ddl-auto=validate`.
7. (메시징 단계) `adapter/in|out/messaging` + `shared/events`.
8. 빌드·단위테스트 → DB 띄우고 API 검증.

---

## 복습 포인트 (스스로 답해보기)

1. `domain/` 패키지가 `java.*` 외 import를 금지하는 이유는? Spring 애노테이션 하나 정도는 괜찮지 않나?
   <details><summary>답</summary>도메인이 프레임워크를 알면 "바깥 기술은 갈아끼울 수 있는 세부사항"이라는 헥사고날의 전제가 깨진다. Spring이나 JPA를 하나라도 들이면 DB·프레임워크 없이 순수 단위 테스트를 할 수 없게 되고, 의존성 방향(바깥→안쪽)이 역전된다(§1, §2).</details>

2. 애그리거트 루트에 `create()`와 `restore()` 팩토리를 **둘 다** 두는 이유는? 각각 언제 쓰나?
   <details><summary>답</summary>`create()`는 신규 생성(id 없음, 웹 요청 등 바깥 입력을 통해 도메인이 태어나는 경로)이고, `restore()`는 DB에서 읽어온 값으로 도메인 객체를 복원하는 경로(영속 어댑터 전용)다. 하나로 합치면 "신규인지 기존인지"를 생성자 인자만으로 구분해야 해 실수하기 쉽다(§1).</details>

3. 애플리케이션 서비스가 도메인 애그리거트(`Order`)를 그대로 반환하지 않고 `OrderView`로 변환해 반환하는 이유는?
   <details><summary>답</summary>가변 도메인 객체를 그대로 내보내면 어댑터가 실수로 도메인의 변경 메서드를 호출하거나, 도메인이 어댑터(웹·JSON 직렬화)에 종속될 위험이 생긴다. 불변 뷰로 한 번 감싸면 도메인이 바깥으로 새지 않는다(§2 application 규칙).</details>

4. 기존 애그리거트의 상태를 바꿔 저장할 때(`PENDING`→`PAID`) 왜 새 엔티티를 만들어 `save()`하면 안 되나?
   <details><summary>답</summary>새 엔티티로 `save()`하면 JPA가 그걸 신규로 오인해 **새 행을 INSERT**할 수 있다(§3.3). 기존 행을 갱신하려면 **load-then-mutate**(관리 엔티티를 로드해 필드를 직접 바꾸고 dirty checking으로 UPDATE)를 써야 한다.</details>

5. 커스텀 조회에 리포지토리 `@Query`/`@Lock` 대신 QueryDSL을 쓰는 이유는? `@OneToMany` 컬렉션을 fetch join할 때 무엇을 조심해야 하나?
   <details><summary>답</summary>쿼리 로직을 어댑터의 QueryDSL 클래스 한 곳에 모아 타입세이프하게 관리하기 위해서다(§3.4). 컬렉션을 fetch join하면서 목록을 조회하면 카테시안 곱으로 루트가 중복되므로 `.distinct()`가 필요하고, 컬렉션 fetch join은 **한 번에 하나만** 가능하다(둘이면 `MultipleBagFetchException`).</details>

---

## 10. 용어 사전

- **헥사고날 아키텍처(Ports & Adapters)**: 의존성이 항상 안쪽(도메인)을 향하게 하고, 바깥 기술(DB·웹·메시징)은 갈아끼울 수 있는 "어댑터"로 다루는 아키텍처 스타일.
- **포트(Port)**: 유스케이스가 바깥과 대화하는 기술 무관 인터페이스. 인바운드 포트(들어오는 진입점 계약)와 아웃바운드 포트(DB·외부로 나가는 계약)로 나뉜다.
- **어댑터(Adapter)**: 포트의 실제 구현(기술 세부). 인바운드 어댑터(웹 컨트롤러 등)와 아웃바운드 어댑터(JPA 영속화 등).
- **애그리거트(Aggregate) / 애그리거트 루트**: 일관성 경계를 가진 도메인 객체 묶음과 그 대표(진입점). DDD 용어.
- **값 객체(Value Object)**: 식별자 없이 값 자체로 동일성이 판단되는 도메인 객체. 생성자에서 불변식을 검증한다.
- **불변식(invariant)**: 객체가 항상 지켜야 하는 규칙(예: 재고는 음수가 될 수 없음). 도메인이 스스로 보호해야 한다.
- **유스케이스(UseCase)**: 한 트랜잭션 단위의 비즈니스 로직. `@UseCase`(=@Component)로 표시.
- **출력 모델(`*View`)**: 유스케이스가 가변 도메인 대신 반환하는 불변 read model. 도메인이 어댑터로 새는 것을 막는다.
- **JPA 엔티티**: DB 테이블에 매핑되는 클래스. 도메인 객체와 분리해(`OrderJpaEntity` ≠ `Order`) 영속 애노테이션이 도메인을 오염시키지 않게 한다.
- **영속성 컨텍스트(persistence context) / managed(관리) 엔티티**: 트랜잭션 동안 조회한 엔티티를 관리하는 JPA의 캐시, 그리고 그 안에서 추적되는 엔티티.
- **dirty checking**: JPA가 managed 엔티티의 필드 변경을 감지해 커밋 시 자동으로 UPDATE를 발행하는 메커니즘.
- **load-then-mutate**: 관리 엔티티를 로드해 직접 수정 → dirty checking으로 UPDATE. 새 엔티티를 `merge`하는 방식과 대비된다(§3.3).
- **persist vs merge**: `persist`는 id 없는 신규 엔티티를 곧장 INSERT, `merge`는 이미 id가 있어 신규 확신이 안 될 때 INSERT 전에 SELECT로 존재를 확인하는 경로.
- **QueryDSL / Q타입**: 타입세이프 쿼리 라이브러리. `QOrderJpaEntity` 같은 Q타입은 애노테이션 프로세서가 엔티티로부터 컴파일 타임에 생성한다.
- **fetch join**: 연관 엔티티를 지연 로딩 대신 즉시 함께 로드하는 쿼리 방식. `open-in-view: false` 환경에서 트랜잭션 밖 지연 로딩 예외를 피하려 쓴다.
- **비관적 락(PESSIMISTIC_WRITE)**: 행을 읽는 시점부터 DB 레벨에서 잠가 다른 트랜잭션의 동시 수정을 막는 방식(`SELECT … FOR UPDATE`).
- **전역 정렬 순서(락 순서 통일)**: 여러 행을 잠글 때 항상 같은 순서(예: id 정렬)로 잠가 교착(deadlock)을 회피하는 규칙.
- **Flyway / `ddl-auto=validate`**: 번호 붙은 SQL로 스키마를 버전 관리하는 도구, 그리고 Hibernate가 스키마를 만들지 않고 검증만 하게 하는 설정.
- **outbox**: 이벤트 발행을 DB 트랜잭션과 같은 커밋에 묶어 유실을 막는 패턴(테이블은 서비스별 자기 DB, Phase 9+ 대비).
- **스테레오타입(`@UseCase` 등)**: 계층의 역할을 이름으로 드러내는 커스텀 애노테이션(내부적으로 `@Component`).
- **package-private**: 같은 패키지 안에서만 보이는 접근 제한자. 유스케이스 구현체·어댑터처럼 "포트로만 노출되면 충분한" 클래스에 붙여 외부 결합을 줄인다.
