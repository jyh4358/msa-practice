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

### adapter (가장 바깥 — 세부사항)
- **in/web**: 요청 DTO를 `*Command`로 변환해 in-port 호출, 도메인 → 응답 DTO로 변환. 도메인/엔티티를 와이어로 노출 금지. **OpenAPI/Swagger 문서화(springdoc `@Tag`/`@Operation` + `OpenApiConfig`)도 여기에만** 둔다 — 도메인·애플리케이션은 springdoc에 의존하지 않는다.
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
4. **`open-in-view: false`** 가 모든 서비스 기본. 웹/메시징이 렌더링하는 애그리거트 연관은
   리포지토리에서 **즉시 로딩**해야 한다(`@EntityGraph` 단건, **`distinct` fetch-join** 목록).
   - ⚠️ `@OneToMany` List를 join fetch + 목록 조회하면 **카테시안 곱으로 루트가 중복**된다 →
     `@Query("select distinct e from E e left join fetch e.children")` 또는 Set 사용.
5. **DB 스키마는 Flyway가 소유**(`ddl-auto: validate`). 엔티티 매핑과 마이그레이션이 정확히 일치해야 부팅된다.

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

## 7. 매퍼 / 스테레오타입
- **매퍼는 무상태 static 유틸**(`OrderMapper`) — 주입 불필요·테스트 쉬움. 협력자가 필요해지면 그때 package-private `@Component`로 승격.
- 스테레오타입: 애플리케이션 = `@UseCase`. (선택) 어댑터에 `@WebAdapter`/`@PersistenceAdapter`(둘 다 meta-@Component)를 두면 계층이 자기설명적이 된다 — 현재는 `@RestController`/`@Component` 사용.

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
| 웹 어댑터 | `adapter/in/web/{OrderController,PlaceOrderRequest,OrderResponse,ApiExceptionHandler}.java` |
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
