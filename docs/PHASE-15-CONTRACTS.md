# Phase 15 — 플랫폼 강화 (설정 방송 · 계약 테스트 · 스키마 진화)

> **한 줄 요약:** 지금까지는 "코드가 맞게 동작하는가"를 봤다. 이번엔 **서비스들 사이의 약속**을 다룬다.
> 설정을 재시작 없이 전 인스턴스에 방송하고(**Bus**), 서비스 간 인터페이스를 파일로 못 박아
> **깨지면 배포 전에 빌드가 실패하게** 만들고(**계약 테스트**), 이벤트 스키마를 어떻게 바꿔도 되는지 규칙을 코드로 남긴다.

초심자(Java/Spring은 알지만 서비스 간 계약 관리는 처음) 기준으로 **왜 → 무엇을 → 어떻게** 순서로 설명합니다.

---

## 0. 이번 단계에서 한 일 (요약)

- **Spring Cloud Bus (Kafka)** — 한 인스턴스에 `POST /actuator/busrefresh` → `springCloudBus` 토픽으로 방송 → **전 서비스가 설정을 다시 읽는다**.
- **`@Value` → `@ConfigurationProperties`** — 리프레시로 값이 실제로 바뀌게 하는 전제 조건.
- **계약 테스트(동기 API)** — inventory가 계약을 발행 → 생성 테스트로 자기 검증 + **stub jar** 발행 → order가 그 stub 으로 검증.
- **계약 테스트(이벤트)** — `OrderPlaced` 페이로드 계약. SCC에 Kafka 통합이 없어 **`MessageVerifier` 를 직접 구현**(발신함 = outbox).
- **스키마 진화 규칙을 테스트로** — 필드 추가는 허용, 제거·개명·타입변경은 금지임을 6개 테스트로 못 박음.
- **★ 계약 테스트가 실제 결함을 잡아냈다** — 소비자가 누락된 필드를 `int` 기본값 `0`("재고 0")으로 삼키고 있었다.

---

## 1. 왜 — 지금까지 쌓인 문제

**① 설정을 바꾸려면 재시작해야 한다.**
Phase 6에서 Config Server를 세웠지만, 값을 바꾸면 각 서비스를 <b>일일이</b> 재시작해야 반영됐다.
인스턴스가 10개면 10번이다. 게다가 재시작은 그 자체로 장애 리스크(커넥션 끊김·워밍업)를 만든다.

**② 서비스 간 약속이 아무 데도 없다.**
Phase 14에서 order-service가 inventory-service의 `GET /inventory/{id}` 를 호출하기 시작했다.
그 응답이 `availableQuantity` 라는 걸 order 팀은 어떻게 알까? **inventory 코드를 읽어서** 알았다.
inventory 팀이 그 필드를 개명하면? order 팀은 **프로덕션에서** 알게 된다.

**③ 이벤트는 더 위험하다.**
동기 호출은 깨지면 지금 500이 나서 즉시 안다. 이벤트는 발행자가 멀쩡한 채로 **소비자만 조용히 실패**한다
(Phase 14의 DLT로 흘러간다). 게다가 Phase 11의 **리플레이**는 과거 스키마를 되살린다 —
즉 어느 순간에도 **여러 버전이 동시에 흐른다**.

> 💡 이 셋의 공통점: **혼자 테스트해서는 절대 못 잡는다.** 통합 환경에서, 대개 배포 후에 드러난다.
> Phase 15는 그것들을 **빌드 시점으로 당겨오는** 작업이다.

---

## 2. 핵심 개념 (용어부터)

| 용어 | 뜻 | 없으면 |
|---|---|---|
| **Spring Cloud Bus** | 설정 변경 같은 이벤트를 메시지 브로커로 **전 인스턴스에 방송**하는 장치 | 인스턴스마다 따로 `/actuator/refresh` 를 쳐야 한다 |
| **`@RefreshScope` / `@ConfigurationProperties`** | 리프레시 때 다시 만들어지는 빈 / 다시 **바인딩**되는 설정 객체 | `@Value` 필드는 리프레시해도 그대로다 |
| **CDC (Consumer-Driven Contract)** | 소비자가 필요한 형태를 계약으로 못 박고, 프로듀서가 그 계약을 지키는지 자동 검증 | "코드를 읽어서" 알아내고, 바뀌면 프로덕션에서 안다 |
| **Stub (WireMock)** | 계약에서 생성된 **가짜 프로듀서** — 소비자 테스트가 상대 서비스 없이 돈다 | 통합 환경을 띄워야만 검증 가능 |
| **Tolerant reader** | 모르는 필드는 무시하고 아는 필드만 읽는 소비자 | 프로듀서가 필드 하나 추가할 때마다 전 소비자가 깨진다 |
| **스키마 진화** | 여러 버전이 동시에 흐르는 상황에서 안전하게 스키마를 바꾸는 규칙 | 배포 순서에 따라 랜덤하게 깨진다 |

> 💡 **계약 테스트의 한 문장 정의:** "**통합 환경 없이** 통합 버그를 잡는 방법."

---

## 3. 구성 (그림)

```
[① 설정 방송]
  config-repo/*.yml 수정
        │
        ▼  (한 곳에만!)
  POST inventory:8082/actuator/busrefresh
        │
        ▼  springCloudBus 토픽
   ┌────┴────┬──────────┬──────────────┐
   ▼         ▼          ▼              ▼
 order   inventory   payment     order-query      ← 전부 Config Server 에서 다시 읽는다
   └─ @ConfigurationProperties 재바인딩 → 즉시 동작 변경(재시작 없음)

[② 계약 테스트 — 동기 API]
  inventory(프로듀서)                      order(소비자)
   contracts/rest/*.yml                     
     ├─▶ 생성된 RestTest ── 자기 검증        
     └─▶ verifierStubsJar ──▶ ~/.m2 ──▶ StubRunner(WireMock :8100)
                                              └─▶ InventoryStockClient 를 그 위에서 실행

[② 계약 테스트 — 이벤트]
  order(프로듀서)  contracts/messaging/order_placed.yml
     └─▶ 생성된 MessagingTest → orderPlacedTriggered() → outbox row → 커스텀 MessageVerifier → 계약 대조

[③ 스키마 진화]
  order-query(소비자)  EventSchemaEvolutionTest — 추가/누락/개명/타입변경 4가지를 코드로 고정
```

---

## 4. 코드·설정 한 부분씩

### 4.1 Bus 를 켜기 전에 — `@Value` 로는 아무것도 안 바뀐다

```java
// Phase 14 (리프레시 불가)                     // Phase 15 (리프레시 가능)
@Value("${order.stock-precheck...}")            @ConfigurationProperties("order.stock-precheck")
private boolean rejectOnInsufficient;           public class StockPrecheckProperties { ... }
```

`@Value` 로 주입한 값은 **빈이 만들어질 때 한 번** 박히고 끝이다. 반면 `@ConfigurationProperties` 빈은
Boot 의 `ConfigurationPropertiesRebinder` 가 리프레시 때 **자동으로 다시 바인딩**한다.

> ⚠️ **바뀌지 않는 것도 있다.** `@ConditionalOnProperty` 로 거는 값(빈의 **존재 여부**)은 리프레시로 안 바뀐다 —
> 조건 평가는 컨텍스트 기동 시 한 번뿐이다. 그건 재시작이 필요하다.
> 게이트웨이 라우트도 마찬가지(기동 시 로드) — 그래서 이 프로젝트는 게이트웨이에 Bus 를 넣지 않았다.

### 4.2 Bus 설정 — ★ 여기서 가장 오래 헤맸다

```yaml
spring:
  cloud:
    stream:
      kafka:
        binder:
          brokers: ${spring.kafka.bootstrap-servers}   # 바인더 기본값은 localhost:9092 (별개 설정!)
          configuration:
            key.serializer:   org.apache.kafka.common.serialization.ByteArraySerializer
            value.serializer: org.apache.kafka.common.serialization.ByteArraySerializer
            key.deserializer:   org.apache.kafka.common.serialization.ByteArrayDeserializer
            value.deserializer: org.apache.kafka.common.serialization.ByteArrayDeserializer
    bus:
      enabled: true
      destination: springCloudBus
```

**무슨 일이 있었나(실측):** Bus 는 spring-cloud-stream 의 Kafka 바인더 위에서 돈다.
그런데 그 바인더는 **우리가 Phase 9~14에서 깔아 둔 `spring.kafka.{producer,consumer}` 설정을 물려받는다.**
그 설정은 도메인 이벤트용(`JsonSerializer` + `ErrorHandlingDeserializer`)이라,
**byte[] 인 Bus 메시지가 base64 문자열로 이중 인코딩**됐다.

```
$ kafka-console-consumer --topic springCloudBus
"eyJ0eXBlIjoiUmVmcmVzaFJlbW90ZUFwcGxpY2F0aW9uRXZlbnQiLCJ0aW1lc3RhbXAiOjE3ODUyNTU2MTgwODAs…"
   └─ JSON 문자열로 따옴표까지 씌워진 base64 = JsonSerializer 가 byte[] 를 직렬화한 흔적
```

증상이 지독하다: **토픽에는 메시지가 정상적으로 쌓이고, 아무 에러도 안 나고, 그냥 아무도 리프레시되지 않는다.**
"성공한 것처럼 보이는 실패"라 원인을 찾는 데 가장 오래 걸렸다.
→ 바인더 전용 `configuration` 으로 byte[] 직렬화를 되돌려 해결.

### 4.3 동기 API 계약 — 파일 하나가 양쪽을 묶는다

```yaml
# services/inventory-service/src/contractTest/resources/contracts/rest/stock_found.yml
request:  { method: GET, urlPath: /inventory/22222222-2222-2222-2222-222222222222 }
response:
  status: 200
  body: { productId: "2222…", availableQuantity: 100 }
  matchers:
    body:
      - path: $.availableQuantity
        type: by_regex
        predefined: number      # ← 값 100이 아니라 '숫자 타입'을 계약한다
```

이 파일 하나가 **두 개**를 만든다.

| 산출물 | 누가 쓰나 | 무엇을 보장하나 |
|---|---|---|
| 생성된 `RestTest` | 프로듀서(inventory) | "우리가 정말 이렇게 응답하는가" |
| `verifierStubsJar` | 소비자(order) | "상대가 이렇게 응답한다 치고 우리 파싱이 맞는가" |

**실패 경로도 계약이다.** `stock_not_found.yml`(404)을 함께 둔 이유: 성공만 계약하면
소비자는 실패 응답 형태를 **상상해서** 코딩한다 — 그게 통합 버그의 단골 출처다.

베이스 클래스는 **애플리케이션 전체를 띄우지 않는다**:

```java
RestAssuredMockMvc.mockMvc(MockMvcBuilders
        .standaloneSetup(new InventoryController(mockedQuery, noChaos))
        .setControllerAdvice(new ApiExceptionHandler()).build());
```

계약이 말하는 것은 **HTTP 표면**뿐이다. DB·Kafka·Eureka 는 계약과 무관하므로 끌어오지 않는다.
여기서 통합까지 검증하려 들면 계약 테스트가 통합 테스트가 되고, **깨졌을 때 원인을 알 수 없게** 된다.

### 4.4 소비자 쪽 — Spring 없이 stub 위에서 프로덕션 코드를 돌린다

```java
@RegisterExtension
static StubRunnerExtension stubRunner = new StubRunnerExtension()
        .downloadStub("com.shopsaga", "inventory-service", "0.0.1-SNAPSHOT", "stubs")
        .withPort(8100)
        .stubsMode(StubRunnerProperties.StubsMode.LOCAL);   // 로컬 Maven 저장소에서 가져온다
```

```kotlin
// order-service/build.gradle.kts — 이 의존이 계약 테스트의 의미를 만든다
tasks.named<Test>("test") {
    dependsOn(":services:inventory-service:publishStubsPublicationToMavenLocal")
}
```

이 `dependsOn` 이 없으면 소비자는 **옛날 stub 으로 계속 통과**해 버려 계약이 무의미해진다.

### 4.5 이벤트 계약 — 기성품이 없어서 검증기를 직접 끼웠다

SCC 4.3의 메시징 통합은 **Camel · Spring Integration · Spring Cloud Stream · JMS** 뿐이다.
**Apache Kafka 전용 통합은 없다.** 우리는 `KafkaTemplate` 을 직접 쓰므로 맞는 기성품이 없다.

그래서 `MessageVerifierReceiver` 를 직접 구현했다. 핵심 발상:

> 이 플랫폼에서 이벤트의 **발신함은 Kafka 가 아니라 outbox 테이블**이다(Phase 10~12).
> "이 이벤트를 보내겠다"고 결정한 지점이 outbox row 이고, 그 payload 는 **실제 발행에 쓰이는 바로 그 JSON** 이다.
> → outbox 를 읽으면 브로커 없이 진짜 직렬화 결과를 계약과 대조할 수 있다.

```java
@Bean
MessageVerifierReceiver<Message<?>> contractMessageReceiver(List<OutboxMessage> sentBox, ObjectMapper om) {
    return (destination, timeout, unit, contract) -> sentBox.stream()
            .filter(m -> destination.equals(m.getTopic()))
            .findFirst().map(m -> toMessage(m, om)).orElse(null);
}
```

> ⚠️ 제네릭이 `Message<?>`(스프링 메시징)인 이유: Phase 15에서 Bus 를 넣으며 spring-cloud-stream 이
> 클래스패스에 들어왔고, 그러면 SCC의 스트림용 자동설정이 활성화되어 `MessageVerifierSender<Message<?>>` 를 요구한다.
> 타입이 어긋나면 `NoSuchBeanDefinitionException` 으로 컨텍스트가 뜨지 않는다(실제로 겪었다).

**이 선택의 한계(정직하게):** 브로커까지의 왕복(파티션 키·헤더·릴레이 동작)은 검증하지 않는다.
그건 Phase 12~14의 compose 실증이 담당한다. 계약 테스트는 **스키마**에만 집중한다.

### 4.6 스키마 진화 — 규칙을 문서가 아니라 테스트로

```java
private final ObjectMapper consumerMapper = JacksonUtils.enhancedObjectMapper();  // 런타임과 '같은' 매퍼
```

> ⚠️ `new ObjectMapper()` 로 테스트하면 런타임과 **다른 것**을 검증하게 된다.
> spring-kafka 의 `JsonDeserializer` 가 실제로 쓰는 것은 `JacksonUtils.enhancedObjectMapper()` 다.

| 변경 | 결과 | 판정 |
|---|---|---|
| 필드 **추가**(`couponCode`) | 옛 소비자가 **무시**하고 계속 읽음 | ✅ 안전 |
| 필드 **누락**(옛 이벤트 리플레이) | 역직렬화는 성공, 값은 **null** | ⚠️ 조건부 — 소비자가 null 을 견뎌야 |
| 필드 **개명**(`totalAmount`→`total`) | 예외 없음, 값만 **null** | ❌ **조용한 데이터 유실** |
| **타입 변경**(`quantity: "two"`) | `InvalidFormatException` | ❌ 실패(하지만 시끄러워서 그나마 낫다) |

---

## 5. 요청 하나가 흐르는 순서 (설정 변경)

```
1. 개발자가 config-repo/order-service.yml 의 reject-on-insufficient 를 true 로 수정
2. inventory-service '한 곳'에 POST /actuator/busrefresh
3. inventory 가 springCloudBus 토픽에 RefreshRemoteApplicationEvent 발행
     {"type":"RefreshRemoteApplicationEvent","originService":"inventory-service:docker:8082:…",
      "destinationService":"**"}          ← "**" = 전원
4. order·payment·order-query 가 각자 그 메시지를 받는다(익명 컨슈머 그룹 = 전원 수신)
5. 각자 Config Server 에서 자기 설정을 다시 가져온다
6. 바뀐 키가 있으면 @ConfigurationProperties 빈을 다시 바인딩 → ["order.stock-precheck.reject-on-insufficient"]
7. 다음 요청부터 order-service 가 409 를 돌려준다 — 재시작 없음
```

---

## 6. 원리 / 트레이드오프

### 6.1 Bus 는 "설정 배포"지 "설정 관리"가 아니다
Bus 는 변경을 **전파**할 뿐, 누가 언제 무엇을 바꿨는지는 모른다. 그래서 실무에선 config-repo 를
**Git 백엔드**로 두고 PR·리뷰·롤백을 붙인다. 이 프로젝트는 학습용이라 native(파일) 백엔드다.

### 6.2 계약 테스트 vs 통합 테스트 — 섞지 말 것
계약 테스트는 "인터페이스가 맞나"만 본다. DB·브로커·디스커버리를 끌어들이면
**깨졌을 때 원인이 계약인지 인프라인지 알 수 없게** 되고, 느려서 아무도 안 돌리게 된다.
통합은 Phase 12~14의 compose 실증이 담당한다 — **층을 나눠야 각 층이 쓸모 있다.**

### 6.3 계약은 '값'이 아니라 '모양'을 못 박아야 한다
`availableQuantity: 100` 을 그대로 계약하면 프로듀서가 테스트 시드를 바꿀 때마다 계약이 깨진다.
`type: by_regex, predefined: number` 처럼 **타입/형식**만 계약해야 오래 간다.

> ⚠️ 실측: `predefined:` 로 쓰면 프로듀서 테스트에 단언이 **생성되지 않는 경우**가 있었다.
> 이벤트 계약에서는 명시 정규식(`value:`)으로 바꾸고 나서야 단언 7개가 제대로 생성됐다.
> **생성된 코드를 한 번은 반드시 열어 볼 것**(`build/generated-test-sources`).

### 6.4 스키마 진화에서 가장 무서운 것은 예외가 아니라 침묵
타입 변경은 예외를 던져 소비를 멈추고 DLT에 증거를 남긴다 — **시끄러워서 낫다.**
개명은 아무 에러 없이 값만 사라진다. 시스템은 정상 동작하는데 금액 없는 주문이 쌓인다.
**개명 = 제거 + 추가**임을 몸으로 이해해야 한다. 안전한 개명은 "새 필드 추가 → 소비자 전환 → 옛 필드 제거"의 3단계다.

### 6.5 이번 설계가 포기한 것
- **스키마 레지스트리 없음**(Apicurio 등) — 규칙을 테스트로만 강제한다. 다른 팀이 규칙을 어기면 런타임에 안다.
- **API 버저닝(`/api/v1/...`) 미적용** — 경로가 그대로다. 지금은 소비자를 우리가 다 통제하므로 유예했다.
- **소비자 쪽 이벤트 stub 재생(`stubFinder.trigger`) 없음** — Kafka 통합 부재 때문. 대신 스키마 진화 테스트가 그 자리를 메운다.

---

## 7. 검증 (실증)

### 7.1 Bus — 한 곳에 리프레시하면 전부 바뀐다

```
① reject-on-insufficient=false        → POST /orders(99999개) = 201
② config-repo 수정(true), 리프레시 전  → 201            ← 파일만 바꿔서는 아무 일도 안 일어난다
③ inventory-service '에만' busrefresh  → [rc=0]
④ order-service 동작                   → 409  ✅
   {"status":409,"detail":"재고 사전 확인 실패(참고값 기준): … 요청=99999 가용=69"}
⑥ 원복 후                              → 201
```

**order-service 는 한 번도 직접 호출되지 않았다.** 토픽 `springCloudBus` 는 바인더가 자동 생성했다.

### 7.2 ★ 계약을 깨면 어디서 잡히는가 (로드맵의 핵심 주장)

```
① 정상                                   프로듀서 contractTest EXIT=0 / 소비자 계약테스트 EXIT=0
② 프로듀서가 '구현만' 변경               프로듀서 contractTest EXIT=1  ← 자기 팀에서 즉시 잡힘
   (availableQuantity → available)
③ 프로듀서가 '계약도 같이' 변경           프로듀서 contractTest EXIT=0  ← 프로듀서 입장에선 문제 없음
④ 새 stub 발행 후 소비자 빌드            소비자 계약테스트   EXIT=1  ← ★ 배포 전에 잡힌다
⑤ 원복                                   양쪽 EXIT=0, 작업 트리 깨끗
```

②와 ④가 **다른 팀에서 다른 시점에** 잡힌다는 것이 계약 테스트의 요점이다.

> ⚠️ **함정:** ④를 처음 돌렸을 때 **통과해 버렸다.** 두 가지 이유였다.
> 1. `~/.m2` 의 stub jar 는 Gradle 입력이 아니라 `test` 가 **UP-TO-DATE 로 건너뛴다** → `--rerun-tasks` 필요.
> 2. 그리고 아래 §7.3의 진짜 결함.

### 7.3 ★ 계약 테스트가 잡아낸 실제 결함 — 조용한 0

`--rerun-tasks` 로 강제 실행해도 소비자 테스트가 **여전히 통과**했다. 원인:

```java
record StockResponse(UUID productId, int availableQuantity) {}   // ← int
```

필드가 사라지면 Jackson 은 `int` 를 **0으로 채운다**. 즉 소비자는
"재고 0"이라는 **그럴듯한 거짓말**을 조용히 받아들이고 있었다 — Phase 14의 사전 확인이
"INSUFFICIENT"라고 잘못 판정하게 만드는 결함이다.

```java
record StockResponse(UUID productId, Integer availableQuantity) {}   // 수정
if (body == null || body.availableQuantity() == null) {
    throw new IllegalStateException("재고 응답에 availableQuantity 가 없다: " + productId);
}
```

> **tolerant reader = "모르는 필드를 무시한다"이지 "필요한 필드가 없어도 괜찮다"가 아니다.**
> 이 구분을 놓치면 관대함이 데이터 유실로 바뀐다.

### 7.4 스키마 진화 — 4가지 변경의 결과

| 테스트 | 결과 |
|---|---|
| `unknownField_isIgnored` | 새 필드 `couponCode` 추가 → 예외 없음 ✅ |
| `missingField_readsAsNull…` | 옛 이벤트(리플레이) → `totalAmount == null`, `occurredAt == null` ⚠️ |
| `renamedField_isSilentDataLoss` | `totalAmount`→`total` → **예외 없이 null** ❌ |
| `typeChange_breaksDeserialization` | `quantity:"two"` → `InvalidFormatException` ❌ |
| `consumerMapperIsConfiguredTolerant` | `FAIL_ON_UNKNOWN_PROPERTIES == false` 확인 |

### 7.5 자동 테스트 (총 87개 통과)

| 테스트 | 지키는 것 |
|---|---|
| `RestTest`(생성) | inventory 가 계약대로 응답하는가(200 스키마 + 404) |
| `MessagingTest`(생성, 단언 7개) | `OrderPlaced` 페이로드가 계약대로 직렬화되는가 |
| `InventoryContractConsumerTest` | 우리 파싱 코드가 프로듀서 계약과 맞는가(+404 처리) |
| `EventSchemaEvolutionTest` | 스키마 진화 4규칙 |

---

## 8. 알려진 한계 → 해결 Phase

| # | 한계 | 왜 지금은 이대로 | 해결 |
|---|---|---|---|
| 1 | **스키마 레지스트리 없음** — 규칙을 테스트로만 강제 | Apicurio 컨테이너 추가는 Phase 16(RAM 최대 고비) 직전 부담 | Phase 18(캡스톤) 또는 별도 |
| 2 | **API 버저닝(`/api/v1`) 미적용** | 소비자를 전부 우리가 통제 중 | Phase 18 / 외부 소비자 생길 때 |
| 3 | 이벤트 계약이 **브로커 왕복을 검증하지 않음**(outbox 까지만) | SCC에 Kafka 통합이 없음 | 통합은 compose 실증이 담당 / 필요시 Testcontainers 기반 검증기 |
| 4 | 소비자 계약 테스트가 **UP-TO-DATE 로 건너뛸 수 있음**(stub jar 가 Gradle 입력이 아님) | 로컬 편의 | CI는 clean 빌드라 무해 · 개선하려면 stub jar 를 입력으로 선언 |
| 5 | Bus 가 **게이트웨이에는 없음** — 라우트는 여전히 재시작 필요 | 라우트는 기동 시 로드(리프레시 불가) | Phase 16 k8s 롤링 재시작 |
| 6 | `busrefresh` 가 **인증만 통과하면 누구나** 호출 가능 | 학습용 | 운영: actuator 별도 포트 + 네트워크 격리 |
| 7 | config-repo 가 **native(파일) 백엔드** — 변경 이력·리뷰 없음 | 학습 단순화 | Git 백엔드 전환(운영 표준) |
| 8 | 계약이 **inventory GET + OrderPlaced 둘뿐** | 패턴 학습이 목적 | 나머지 API·이벤트로 확장 |

---

## 9. 용어사전

| 용어 | 한 줄 정의 |
|---|---|
| **Spring Cloud Bus** | 브로커를 통해 설정 변경 등을 전 인스턴스에 방송하는 장치 |
| **`busrefresh`** | 한 인스턴스에 호출하면 전원이 설정을 다시 읽게 하는 actuator 엔드포인트 |
| **`@ConfigurationProperties` 재바인딩** | 리프레시 때 빈을 새로 만들지 않고 필드만 갈아 끼우는 것 |
| **CDC(소비자 주도 계약)** | 소비자가 필요한 형태를 계약으로 정하고 프로듀서가 지키는지 검증하는 방식 |
| **Stub jar** | 계약에서 생성된 가짜 프로듀서(WireMock 정의 + 계약 원문)를 담은 jar |
| **Stub Runner** | stub jar 를 내려받아 가짜 서버로 띄워 주는 도구 |
| **`MessageVerifier`** | 메시징 계약 검증 시 "메시지를 받아 오는" 역할의 SCC 확장점 |
| **Tolerant reader** | 모르는 필드를 무시하고 진행하는 소비자(스키마 진화의 전제) |
| **조용한 데이터 유실** | 예외 없이 값만 사라지는 실패 — 스키마 사고 중 가장 위험 |

---

## 10. 참고 / 상호링크

- 직전 단계: [Phase 14 · 복원력 패턴](PHASE-14-RESILIENCE.md) — 이번에 계약을 건 사전 확인 호출이 거기서 생겼다.
- 설정 기반: [Phase 6 · 중앙 설정](PHASE-6-CONFIG.md) — Bus 는 그 위에 얹은 방송 장치다.
- 이벤트 기반: [Phase 10 · Outbox](PHASE-10-OUTBOX.md) — 이벤트 계약의 "발신함"이 여기 있다.
- 리플레이: [Phase 11 · CQRS](PHASE-11-CQRS.md) — 옛 스키마가 되살아나는 경로.
- 커밋 지도: [PHASE-COMMIT-MAP.md](PHASE-COMMIT-MAP.md) · 로드맵: `MSA-LEARNING-PLAN.md`
- 공식 문서: [Spring Cloud Contract](https://docs.spring.io/spring-cloud-contract/reference/) ·
  [Spring Cloud Bus](https://docs.spring.io/spring-cloud-bus/reference/)

---

**다음 단계 → Phase 16 (로컬 Kubernetes, kind):** 같은 이미지를 k8s로 **이전**한다.
16a는 서비스 하나 + probe, 16b는 **Eureka 삭제 → k8s Service DNS**(디스커버리가 앱에서 플랫폼으로 이동).
⚠️ RAM 최대 고비 — kind 를 띄우기 전에 **compose 인프라를 먼저 내려야 한다.**
