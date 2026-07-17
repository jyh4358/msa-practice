# API 게이트웨이 (단일 진입점) — Spring Cloud Gateway

> **이 문서는 Phase 3 작업을 설명합니다.** 게이트웨이가 처음이어도 끝까지 이해하도록 개념 → 그림 →
> 이 프로젝트의 실제 코드/설정 → 동작 원리 → 검증 → 알려진 한계 순으로 정리했습니다.

---

## 0. 한 줄 요약

> **두 서비스(8080, 8081)로 흩어져 있던 진입점을 게이트웨이(8000) 하나로 모았다.**
> 클라이언트는 이제 8000만 알면 되고, 게이트웨이가 경로(`/orders`, `/payments`)를 보고
> 알맞은 서비스로 **전달(프록시)** 한다. 전달 로직은 **코드가 아니라 설정(yaml)** 이다.

---

## 1. 왜 이 단계인가? (Phase 2까지의 문제)

Phase 2까지 서비스가 두 개(order 8080, payment 8081)가 됐다. 그런데 **클라이언트가 두 포트를
전부 알아야** 했다. 주문은 8080, 결제 조회는 8081… 서비스가 3개, 5개, 10개로 늘면?

- **클라이언트가 내부 구조를 다 알아야 한다.** "어느 기능이 어느 포트냐"를 프론트/외부가 외워야 한다.
  서비스를 쪼개거나 합치면(내부 사정) 클라이언트가 전부 바뀐다.
- **횡단 관심사를 붙일 곳이 없다.** 인증·CORS·요청 로깅·레이트리밋 같은 건 모든 서비스에
  공통으로 필요한데, 서비스마다 중복 구현하거나 각 포트에 따로 걸어야 한다.
- **바깥에 여러 포트를 열어야 한다.** 방화벽/네트워크 관점에서 노출면이 넓어진다.

즉 **"클라이언트가 내부 서비스 배치를 직접 알아야 한다"** 는 전제가 깨진다.
이 문제를 푸는 게 **API 게이트웨이**다. 바깥에는 문 하나만 내고, 안에서 알아서 나눠 보낸다.

---

## 2. 핵심 개념

### 2.1 API 게이트웨이 = 리버스 프록시

게이트웨이는 **리버스 프록시**다. "프록시"는 대신 요청을 전달해 주는 중개자이고,
"리버스"는 **서버 쪽에 서서** 여러 백엔드를 감춘다는 뜻이다(반대로 클라이언트 쪽에 서는 건
포워드 프록시). 클라이언트는 게이트웨이 하나만 보고, 게이트웨이가 뒤의 여러 서비스로 나눠 보낸다.

```
[포워드 프록시]  여러 클라이언트 → (프록시) → 인터넷        (클라이언트를 감춤)
[리버스 프록시]  클라이언트 → (게이트웨이) → 여러 백엔드 서비스   (서버를 감춤)  ← 이 프로젝트
```

핵심은 **게이트웨이에는 "호출 코드"가 없다는 것**이다. `RestClient`로 order를 부르고… 같은
자바 코드를 짜지 않는다. **"이 경로는 저 주소로"** 라는 규칙(설정)만 선언하면, 프레임워크가
요청을 받아 그대로 흘려보낸다.

### 2.2 WebFlux/Netty가 뭔가 (서블릿 모델과의 대비)

게이트웨이는 우리가 익숙한 Spring MVC(Tomcat)가 아니라 **WebFlux(Netty)** 위에서 돈다.

- **Spring MVC(Tomcat)** = **블로킹 모델**: 요청이 오면 스레드 하나가 그 요청에 붙어 응답이 끝날 때까지 대기한다. 동시 요청이 많으면 스레드도 그만큼 필요하다.
- **WebFlux(Netty)** = **논블로킹 모델**: 소수의 **이벤트 루프**(적은 수의 스레드가 여러 연결을 번갈아 처리하는 구조) 스레드가 대기하지 않고 많은 연결을 돌아가며 처리한다.

게이트웨이는 "받아서 흘려보내는" I/O가 대부분이라, 스레드를 붙잡고 기다리지 않는 후자(논블로킹)가 유리하다. 이 차이가 §4.1 함정 2(Tomcat 금지)의 이유이고, 더 깊은 설명은 §6.1에 있다.

> 용어: **리액티브(reactive)**는 데이터가 준비되는 대로 흘려보내며 대기(블로킹)를 피하는 프로그래밍 방식을 뜻한다. WebFlux는 스프링의 리액티브 웹 스택이고, Netty는 그 아래에서 도는 논블로킹 네트워크 런타임이다.

### 2.3 north-south vs east-west 트래픽

트래픽을 방향으로 나누는 관용어다.

| 용어 | 방향 | 이 프로젝트의 예 | 게이트웨이를 거치나? |
|---|---|---|---|
| **north-south** | 바깥(클라이언트) ↔ 시스템 | 브라우저/curl → order·payment | **거친다** |
| **east-west** | 시스템 내부 서비스 ↔ 서비스 | order → payment | **안 거친다** |

> **중요:** 게이트웨이는 **north-south만** 담당한다. order가 결제를 위해 payment를 부르는
> east-west 호출은 게이트웨이를 **거치지 않고** 서비스끼리 직접 간다(Phase 2-2에서 도입한
> `http://localhost:8081` 직접 호출). 게이트웨이는 "외부 진입점"이지 "내부 서비스 버스"가 아니다.

### 2.4 라우트 = predicate + uri (+ filter)

게이트웨이 라우팅의 최소 단위는 **라우트(route)** 이고, 세 조각으로 이뤄진다.

- **predicate(조건)**: "이 요청이 이 라우트에 해당하나?" — 여기서는 `Path=/orders/**`(경로 매칭).
- **uri(목적지)**: 조건이 맞으면 어디로 보낼지 — 여기서는 `http://localhost:8080`.
- **filter(가공, 선택)**: 전달 전/후에 요청·응답을 손봄 — 여기서는 **쓰지 않는다**(§2.5).

### 2.5 통과(pass-through) 라우팅 — StripPrefix가 필요 없는 이유

다운스트림(order-service)이 이미 `/orders`, `/inventory` 경로로 서빙하고 있다.
클라이언트가 `/orders`로 부르면 게이트웨이는 **경로를 손대지 않고 그대로** `http://localhost:8080/orders`로
넘긴다. 그래서 경로 접두사를 잘라내는 `StripPrefix` 같은 필터가 **불필요**하다.

> 만약 게이트웨이에서 `/api/orders/**` 같은 접두사를 붙였다면, 다운스트림엔 `/orders`밖에 없으니
> `StripPrefix=1`로 `/api`를 떼어내야 했을 것이다. 이 프로젝트는 경로를 1:1로 유지해 단순하게 갔다.

---

## 3. 이 프로젝트의 구성

```
                 ┌───────────────────────────────────────┐
                 │        gateway-service (8000)          │  Netty(WebFlux) 리버스 프록시
   클라이언트     │  routes(설정만, 호출 코드 없음):        │
   ──HTTP :8000──▶│   /orders/**    → http://localhost:8080│
   (브라우저/curl)│   /inventory/** → http://localhost:8080│
                 │   /payments/**  → http://localhost:8081│
                 └───────────────────────────────────────┘
                         │ north-south             │ north-south
                         ▼ (그대로 전달)            ▼
                 ┌──────────────┐            ┌──────────────┐
                 │ order (8080) │──east-west─▶│ payment(8081)│
                 │ /orders      │  (게이트웨이 │ /payments    │
                 │ /inventory   │   거치지 않음)│              │
                 └──────────────┘            └──────────────┘
```

- **클라이언트**는 게이트웨이(8000)만 안다. 8080/8081은 몰라도 된다.
- **게이트웨이**는 경로를 보고 두 서비스로 나눠 **전달**한다(요청 가공 없음 = 통과).
- **order → payment**(east-west)는 게이트웨이를 거치지 않고 직접 호출한다.

> 각주(Phase 4): 이 시점 uri는 `http://localhost:8080` / `http://localhost:8081` **하드코딩**이다.
> Phase 4(서비스 디스커버리)에서 이 값들이 `lb://order-service` /
> `lb://payment-service`(`lb://`는 특정 host:port 대신 **서비스 이름**을 주면 디스커버리가 실제
> 인스턴스 주소로 로드밸런싱해 풀어 주는 논리 주소 표기 — 자세한 건 Phase 4 문서 참고)로 바뀐다.
> 이 문서는 **하드코딩이던 그 당시** 상태를 서술한다.

---

## 4. 코드/설정 — 한 부분씩 해설

### 4.1 의존성 — 2025.0.x의 세 가지 함정 (`build.gradle.kts`)

```kotlin
dependencies {
    // Spring Cloud Gateway (리액티브/WebFlux, Netty 런타임)
    implementation("org.springframework.cloud:spring-cloud-starter-gateway-server-webflux")
    // /actuator/gateway/routes 로 라우팅 상태를 들여다보기 위함
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
```
버전은 BOM이 관리한다(개별 버전 명시 X). **BOM(Bill of Materials)** 은 서로 호환되는 라이브러리
버전 묶음을 한 곳에서 지정하는 목록이다. 이걸 import하면 개별 의존성에 버전을 안 적어도 BOM이
맞는 버전을 채워 준다:
```kotlin
dependencyManagement {
    imports { mavenBom("org.springframework.cloud:spring-cloud-dependencies:2025.0.3") }  // Northfields
}
```

> ⚠️ **함정 1 — 스타터 이름이 바뀌었다.** 2025.0.x(Northfields)부터
> `spring-cloud-starter-gateway` → **`spring-cloud-starter-gateway-server-webflux`** 로 개명됐다.
> 구 이름은 deprecated라 부팅 시 경고가 뜨고, 2025.1(Oakwood)에서 제거 예정이다.
> 옛 튜토리얼을 그대로 따라 하면 여기서 막힌다.

> ⚠️ **함정 2 — `spring-boot-starter-web`(Tomcat)을 절대 넣지 마라.** 게이트웨이는
> **Netty(WebFlux)** 기반이다(블로킹 서블릿 vs 논블로킹의 대비는 §2.2 참고). 습관적으로 web
> 스타터를 추가하면 Tomcat과 Netty가 충돌해 게이트웨이 자동설정이 꺼지거나 부팅이 깨진다.
> WebFlux 런타임은 이 스타터가 **전이(transitive) 의존성**(A가 B를 의존하면 B가 자동으로 딸려 오는
> 것)으로 알아서 가져오므로 따로 넣을 필요가 없다.

> ⚠️ **함정 3 — 버전 조합.** Boot `3.5.15` ↔ Spring Cloud `2025.0.3`이 공식 짝(Northfields)이다.
> 게이트웨이 자동설정·yaml 접두사는 이 조합 기준이라, 버전이 어긋나면 라우트가 로딩되지 않는다.

### 4.2 라우트 정의 (`application.yml`) — 접두사가 핵심

```yaml
spring:
  application:
    name: gateway-service
  cloud:
    gateway:
      # 2025.0.x 접두사: spring.cloud.gateway.server.webflux.*  (구 spring.cloud.gateway.* 는 deprecated)
      server:
        webflux:
          routes:
            # /orders/** → order-service. 다운스트림이 이미 /orders 로 서빙하므로 경로를 그대로 전달
            # (StripPrefix 등 필터 불필요 = 통과 라우팅).
            - id: orders-route
              uri: http://localhost:8080          # ← Phase 4에서 lb://order-service 로 바뀜
              predicates:
                - Path=/orders/**
            # 재고 조회도 order-service 소유.
            - id: inventory-route
              uri: http://localhost:8080          # ← Phase 4에서 lb://order-service 로 바뀜
              predicates:
                - Path=/inventory/**
            # /payments/** → payment-service.
            - id: payments-route
              uri: http://localhost:8081          # ← Phase 4에서 lb://payment-service 로 바뀜
              predicates:
                - Path=/payments/**
```

> ⚠️ **가장 흔한 실수: yaml 접두사.** 2025.0.x에서 라우트 경로는
> **`spring.cloud.gateway.server.webflux.routes`** 다. 구 버전 습관대로 `spring.cloud.gateway.routes`로
> 적으면 **아무 에러 없이 라우트가 0개로 로딩**된다(조용히 무시됨). 그러면 모든 요청이 404가 난다.
> §7의 `GatewayRoutesTest`가 바로 이 접두사 오타를 잡기 위한 테스트다.

`Path=/orders/**`의 `**`는 하위 경로 전부를 뜻한다. `/orders`, `/orders/123`, `/orders/123/items`
모두 이 라우트에 매칭된다. `inventory`와 `orders`가 **둘 다 8080**인 이유는 재고 조회도
order-service가 소유하기 때문이다(한 서비스에 두 경로를 매핑).

### 4.3 포트와 종료 정책 (`application.yml`)

```yaml
server:
  port: 8000
  shutdown: graceful   # 진행 중 요청을 끝내고 종료 (프로젝트 공통 규칙)
```
- **포트 8000**: 진입점이므로 관례적으로 눈에 띄는 포트를 잡았다(서비스는 8080/8081).
- **graceful shutdown**: 종료 신호를 받아도 처리 중인 요청은 마저 끝내고 내려간다. 프로젝트 공통 규칙.

### 4.4 액추에이터로 라우트 들여다보기 (`application.yml`)

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,gateway     # gateway 엔드포인트 노출
  endpoint:
    health:
      show-details: always
    gateway:
      access: read-only   # GET /actuator/gateway/routes 로 라우트 점검 (런타임 변경은 막음)
```
**Actuator**는 스프링 부트가 제공하는 운영·모니터링용 엔드포인트 모음(health/info 등)으로, 앱
내부 상태를 HTTP로 들여다보게 해 준다. 여기에 `gateway` 엔드포인트를 켜면 라우트 상태를 조회할 수 있다.

`gateway` 액추에이터를 켜면 `GET /actuator/gateway/routes`로 **현재 실제 로딩된 라우트**를 볼 수
있다. yaml이 제대로 먹었는지 눈으로 확인하는 창구다. `access: read-only`는 **조회만 허용**하고,
런타임에 라우트를 추가/삭제하는 쓰기 동작은 막는다(실수/보안 방지).

### 4.5 진입점 클래스 (`GatewayApplication.java`) — 코드가 없다

```java
@SpringBootApplication
public class GatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
```
평범한 부트 앱 하나가 전부다. **컨트롤러도, 서비스도, 라우팅 코드도 없다.**
라우팅은 4.2의 yaml이 선언적으로 다 하고, 스타터가 클래스패스에 있으면 게이트웨이 기능이
**자동설정(auto-configuration)** 으로 켜진다(스타터가 클래스패스에 있으면 스프링 부트가 관련
빈을 알아서 등록해 주는 메커니즘). "설정 기반 전달"이라는 게이트웨이의 성격을 이 빈 클래스가 그대로 보여준다.

---

## 5. 요청 흐름 따라가기

### 5.1 `POST /orders` 한 방을 추적 (north-south + east-west)
```
1) 클라이언트 ──POST :8000/orders──▶ gateway
2) gateway: predicate Path=/orders/** 매칭 → orders-route 선택
3) gateway: uri=http://localhost:8080 → 경로 가공 없이 http://localhost:8080/orders 로 전달
4) order-service: 재고 차감(비관적 락) 후 결제 필요
5) order → payment: paymentRestClient 로 http://localhost:8081/payments 직접 호출  ★게이트웨이 안 거침(east-west)
6) payment-service: 결제 캡처 → paymentId 반환
7) order: order.confirm(paymentId) → 저장 → 201 CONFIRMED
8) gateway ──201 CONFIRMED──▶ 클라이언트
```
**2~3번(north-south)만 게이트웨이를 거친다.** 5번(east-west)은 order가 payment를 직접 부른다.
게이트웨이는 5번 호출을 전혀 모른다.

### 5.2 라우트가 안 맞으면
매칭되는 predicate가 하나도 없으면 게이트웨이는 **404**를 낸다(예: `GET :8000/unknown`).
"라우트 없음"과 "다운스트림 404"는 다르다 — 전자는 게이트웨이가, 후자는 order/payment가 낸 것.

---

## 6. 동작 원리 더 깊게 / 트레이드오프

### 6.1 Netty(WebFlux) 논블로킹 프록시
Spring Cloud Gateway는 서블릿(Tomcat)이 아니라 **Netty 위의 리액티브(WebFlux)** 스택으로 돈다.
게이트웨이는 "받아서 흘려보내는" I/O가 대부분이라, 스레드 하나가 요청 하나를 붙잡고 기다리는
서블릿 모델보다 **소수 이벤트 루프 스레드로 많은 연결을 다루는** 논블로킹 모델이 유리하다.
그래서 §4.1 함정 2(Tomcat 금지)가 단순 취향이 아니라 **아키텍처 전제**다.

### 6.2 라우트 매칭 순서
라우트는 위에서부터 평가되고 **먼저 매칭되는 것이 이긴다**. 이 프로젝트는 세 경로가 서로 겹치지
않아(`/orders`, `/inventory`, `/payments`) 순서가 문제 되지 않는다. 경로가 겹치면(예: `/orders/**`와
`/orders/special/**`) 더 구체적인 것을 위에 둬야 한다.

### 6.3 이 시점의 트레이드오프
- **주소 하드코딩**: uri가 `localhost:8080/8081` 고정이라 인스턴스가 여러 개거나 주소가 바뀌면 못
  따라간다. → **Phase 4**에서 `lb://`(Eureka 이름 해석)로 해결.
- **단일 진입점 = 단일 장애점(SPOF)**: 게이트웨이가 죽으면 north-south 전부 죽는다. 운영에선 여러
  대 띄우고 앞단 LB를 둔다(이 학습 범위 밖).
- **무인증 통과**: 지금은 누구나 8000으로 무엇이든 부를 수 있다. 게이트웨이는 인증을 걸기 딱 좋은
  자리다. → **Phase 5**에서 엣지 JWT 인증을 여기에 붙인다.
- **east-west는 여전히 직접 호출**: order→payment가 게이트웨이를 안 거치므로, 이 홉의 관측·복원력은
  게이트웨이로 해결되지 않는다(Phase 8/14 몫).

---

## 7. 검증 (그 당시 어떻게 확인했나)

### 7.1 라우트 로딩 테스트 — `GatewayRoutesTest`
DB 없이 컨텍스트만 띄워 **라우트가 실제로 로딩됐는지** 단언한다. 이 테스트가 §4.2의 yaml 접두사
오타를 사실상 보증한다(접두사가 틀리면 라우트 0개 → 단언 실패).
```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayRoutesTest {
    @Autowired RouteDefinitionLocator routeDefinitionLocator;

    @Test
    void configuredRoutesAreLoaded() {
        List<RouteDefinition> routes = routeDefinitionLocator.getRouteDefinitions()
                .collectList().block();
        assertThat(routes)
                .extracting(RouteDefinition::getId)
                .contains("orders-route", "inventory-route", "payments-route");
    }
}
```
> 게이트웨이는 DB를 쓰지 않으므로 Docker/Postgres 없이 컨텍스트가 뜬다 — 순수 라우팅 검증용.
>
> 리액티브 관용구: `getRouteDefinitions()`는 리액티브 스트림 타입인 **`Flux`**(0..N개를 비동기로
> 흘려보내는 타입, 1개짜리는 **`Mono`**)를 돌려준다. `.collectList()`로 전부 리스트에 모으고
> `.block()`으로 비동기 결과가 다 올 때까지 **여기서 잠깐 동기적으로 기다린다**(테스트라 편하게
> 블로킹으로 값을 꺼낸 것 — 실제 게이트웨이 처리 경로에서는 블로킹하지 않는다).

```bash
./gradlew :services:gateway-service:test
```

### 7.2 라우트 점검 (액추에이터)
```bash
curl -s http://localhost:8000/actuator/gateway/routes
# → orders-route: http://localhost:8080, inventory-route: http://localhost:8080,
#    payments-route: http://localhost:8081  (이 시점 하드코딩)
```

### 7.3 end-to-end (게이트웨이 통과)
```bash
# 게이트웨이(8000)로만 부른다 — 8080/8081을 직접 몰라도 됨
curl -X POST http://localhost:8000/orders -H 'Content-Type: application/json' \
  -d '{"customerId":"11111111-1111-1111-1111-111111111111",
       "items":[{"productId":"22222222-2222-2222-2222-222222222222","quantity":1,"unitPrice":10.00}]}'
# → 201 CONFIRMED + paymentId

curl -s http://localhost:8000/inventory        # → order-service의 재고 조회로 전달
```
> 핵심: **8080을 직접 부르지 않았는데** 주문이 성공하면, 게이트웨이가 `/orders`를 order-service로
> 제대로 프록시했다는 증거다.

---

## 8. 알려진 한계 → 해결 Phase

| 한계 / 트레이드오프 | 성격 | 해결 Phase |
|---|---|---|
| 라우트 uri가 `localhost:8080/8081` **하드코딩** — 인스턴스 다중화·주소 변경 못 따라감 | 디스커버리 | **Phase 4** (`lb://` 이름 해석) |
| 게이트웨이 **무인증 통과** — 누구나 8000으로 무엇이든 호출 가능 | 보안 | **Phase 5** (엣지 JWT 인증) |
| 게이트웨이 = **단일 장애점(SPOF)**, 죽으면 north-south 전부 중단 | 가용성 | 운영 시 다중화(학습 범위 밖) |
| **east-west(order→payment)는 게이트웨이 미경유** → 이 홉 관측·복원력 별도 필요 | 관측/복원력 | **Phase 8**(트레이싱) · **Phase 14**(복원력) |
| 하드코딩 host라 **컨테이너에선 틀림**(`localhost`는 컨테이너 자기 자신) | 배포 | **Phase 7** (compose) |
| 레이트리밋·CORS·요청 로깅 등 횡단 필터 미적용 | 하드닝 | **Phase 15** (플랫폼 강화) |

---

## 9. 용어 사전

- **API 게이트웨이**: 외부 클라이언트의 단일 진입점. 요청을 알맞은 내부 서비스로 전달하는 리버스 프록시.
- **리버스 프록시**: 서버 쪽에 서서 여러 백엔드를 감추고 대신 전달하는 중개자(반대는 포워드 프록시).
- **라우트(route)**: 게이트웨이 라우팅의 단위. `predicate + uri (+ filter)`로 구성.
- **predicate**: "이 요청이 이 라우트에 해당하나?" 판단 조건. 여기선 `Path=`(경로 매칭).
- **통과(pass-through) 라우팅**: 경로를 가공하지 않고 그대로 전달(StripPrefix 등 필터 없음).
- **StripPrefix**: 전달 전에 경로 앞 세그먼트를 잘라내는 필터(여기선 불필요).
- **north-south 트래픽**: 바깥(클라이언트) ↔ 시스템. 게이트웨이가 담당.
- **east-west 트래픽**: 시스템 내부 서비스 ↔ 서비스. 게이트웨이 미경유.
- **WebFlux/Netty**: 게이트웨이가 도는 리액티브 논블로킹 런타임(서블릿/Tomcat 아님). WebFlux는 스프링의 리액티브 웹 스택, Netty는 그 아래의 논블로킹 네트워크 런타임.
- **블로킹 vs 논블로킹**: 블로킹은 스레드 하나가 요청 하나에 붙어 응답까지 대기(서블릿/Tomcat), 논블로킹은 소수 이벤트 루프 스레드가 대기 없이 많은 연결을 번갈아 처리(WebFlux/Netty).
- **리액티브(reactive)**: 데이터가 준비되는 대로 흘려보내며 대기(블로킹)를 피하는 프로그래밍 방식.
- **이벤트 루프**: 적은 수의 스레드가 여러 연결을 번갈아 처리하는 논블로킹 실행 구조.
- **Flux / Mono**: 리액티브 스트림 반환 타입. `Flux`는 0..N개, `Mono`는 0..1개를 비동기로 흘려보낸다.
- **Actuator**: 스프링 부트의 운영·모니터링용 엔드포인트 기능(health/info 등 앱 내부 상태를 HTTP로 노출).
- **actuator gateway 엔드포인트**: `/actuator/gateway/routes`로 현재 라우트를 조회하는 창구.
- **자동설정(auto-configuration)**: 스타터가 클래스패스에 있으면 스프링 부트가 관련 빈을 자동 등록해 주는 메커니즘.
- **BOM(Bill of Materials)**: 서로 호환되는 라이브러리 버전 묶음을 한 곳에서 지정하는 목록. import하면 개별 의존성 버전을 생략해도 채워 준다.
- **전이(transitive) 의존성**: A가 B를 의존하면 B가 자동으로 딸려 오는 것.
- **`lb://`**: host:port 대신 서비스 이름을 주면 디스커버리가 실제 인스턴스로 로드밸런싱해 풀어 주는 논리 주소 표기(Phase 4).

---

## 10. 더 알아보기 (공식 문서)

- Spring Cloud Gateway(WebFlux): https://docs.spring.io/spring-cloud-gateway/reference/
- 라우트 predicate 팩토리: https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway/gatewayfilter-factories.html
- Spring Cloud 2025.0(Northfields) 릴리스 노트: https://github.com/spring-cloud/spring-cloud-release/wiki
- Spring Boot Actuator(엔드포인트): https://docs.spring.io/spring-boot/reference/actuator/endpoints.html

---

*관련 문서: [SERVICE-DISCOVERY.md](SERVICE-DISCOVERY.md)(Phase 4, `lb://`로 대체), [SECURITY.md](SECURITY.md)(Phase 5, 엣지 인증), [HEXAGONAL.md](HEXAGONAL.md)(아키텍처 컨벤션). 전체 로드맵: 루트 `MSA-LEARNING-PLAN.md`.*
