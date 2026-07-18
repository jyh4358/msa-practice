# Phase 8 — 관측성 (Observability): 분산 트레이싱 · 메트릭

> **한 줄 요약:** 여러 서비스를 거치는 한 번의 요청(게이트웨이→주문→결제)을 **하나의 트레이스**로 잇고,
> 각 서비스의 지표를 **메트릭**으로 모아, `grafana/otel-lgtm` 올인원 백엔드에서 눈으로 본다.
> 앱은 **OTLP**(OpenTelemetry 프로토콜)로 텔레메트리를 내보내고, 코드 변경은 거의 **설정뿐**이다.

이 문서는 초심자(Java/Spring은 알지만 MSA·관측성은 처음) 기준으로, **왜 → 무엇을 → 어떻게** 순서로 설명합니다.
코드/설정은 발췌·회고형이며, 전문용어는 첫 등장에 괄호로 정의하고 §9 용어사전에 다시 모읍니다.

---

## 0. 이번 단계에서 한 일 (요약)

- **4개 서비스**(gateway·order·payment·auth)에 관측성 의존성 3종을 추가: 트레이싱 브릿지 + OTLP 익스포터 + OTLP 메트릭 레지스트리. **버전은 전부 Spring Boot BOM이 관리**(핀 불필요).
- **중앙 설정**(`config-repo`)에 트레이싱 샘플링·OTLP 엔드포인트를 한 번에 넣어 4개 서비스에 전파. docker 프로파일에선 엔드포인트를 컨테이너 서비스명으로 오버라이드.
- **compose에 `otel-lgtm` 올인원**(Tempo·Loki·Prometheus·Grafana + OTLP 수신기)을 추가.
- 결과: 주문 한 건이 **gateway → order → payment**를 거치는 과정이 Grafana(Tempo)에서 **하나의 트레이스**로 보인다. 콘솔 로그엔 `[서비스명,traceId,spanId]`가 자동으로 찍힌다.

> **범위 메모:** 이 문서는 **8a**(§0~§7: 트레이스+메트릭, “올인원으로 트레이스 하나 보기”)와
> **8b**(§7B: 로그→Loki · RED 대시보드 · 트레이스↔로그 점프)를 모두 다룬다.
> **관측성 스택 컴포넌트 완전 분리**(Collector·Tempo·Loki·Prometheus 개별 컨테이너)만 이후로 남긴다(§8 참고).

---

## 1. 왜 — 직전(Phase 7)의 문제

Phase 2에서 결제를 별도 서비스로 분리한 뒤로, **한 번의 주문 요청이 여러 프로세스를 건너다닌다**:

```
클라이언트 → gateway(8000) → order(8080) → payment(8081)
```

모놀리스였다면 스택 트레이스 하나로 끝났을 디버깅이, 이제는 **서비스마다 로그가 따로** 남는다.
“결제가 느리다”는 신고가 들어와도 —

- 게이트웨이 로그, order 로그, payment 로그가 **서로 다른 곳**에 있고,
- 어느 로그 줄들이 **같은 요청**에 속하는지 이어 붙일 방법이 없으며,
- 지연이 **어느 구간**에서 생겼는지(게이트웨이? 네트워크? payment DB?) 알 수 없다.

> 분산 시스템의 버그는 **기본적으로 보이지 않는다.** Saga·CQRS 같은 더 복잡한 분산 흐름을 만들기 **전에**,
> 먼저 **요청을 꿰뚫어 보는 X-ray**를 손에 쥔다. 이것이 로드맵이 관측성을 Phase 8(비동기·Saga 앞)로 당긴 이유다.

**관측성(Observability)** = 시스템 외부로 나오는 신호(텔레메트리)만으로 내부 상태를 설명할 수 있는 성질.
그 신호가 바로 아래 **3대 기둥**이다.

---

## 2. 핵심 개념 (용어부터)

### 2.1 관측성의 3대 기둥
| 기둥 | 무엇 | 이번 단계 |
|---|---|---|
| **트레이스(Trace)** | 한 요청이 서비스들을 거친 **경로와 소요시간**. 구간마다 **스팬(Span)** | ✅ 핵심 |
| **메트릭(Metric)** | 수치 시계열(요청 수·지연 p95·JVM 메모리 등) | ✅ |
| **로그(Log)** | 사건 기록(텍스트) | 콘솔 상관ID까지(✅) · Loki 전송은 8b |

- **스팬(Span):** 트레이스를 이루는 한 구간(예: “order가 요청을 처리한 시간”). 스팬은 부모-자식으로 이어져 **워터폴**을 이룬다.
- **traceId:** 한 요청 전체에 부여되는 고유 ID. 모든 서비스의 스팬·로그가 이 ID를 공유해야 “한 요청”으로 묶인다.
- **컨텍스트 전파(Context propagation):** traceId·spanId를 다음 서비스로 넘기는 것. HTTP에선 **`traceparent`** 헤더(W3C Trace Context 표준)로 실린다.

### 2.2 OpenTelemetry(OTel)와 OTLP
- **OpenTelemetry(OTel):** 트레이스·메트릭·로그를 만들고 내보내는 **벤더 중립 표준**(SDK+API).
- **OTLP(OpenTelemetry Protocol):** 그 텔레메트리를 백엔드로 보내는 **전송 프로토콜**. HTTP는 기본 포트 **4318**, gRPC는 4317.
- **Micrometer Tracing:** 스프링이 쓰는 관측 파사드. **브릿지(bridge)**로 OTel에 연결한다(`micrometer-tracing-bridge-otel`).
  - 즉 우리 코드는 Micrometer를 통해 관측하고, 실제 트레이스는 OTel이 만들어 OTLP로 내보낸다.

### 2.3 백엔드 — grafana/otel-lgtm (LGTM)
텔레메트리를 **받아 저장하고 보여주는** 쪽. 이름 **LGTM**은 4개 구성요소의 머리글자다:
- **L**oki — 로그 저장/조회
- **G**rafana — 시각화 UI(트레이스·메트릭·로그를 한 화면에서)
- **T**empo — 트레이스 저장/조회
- **M**imir/**Prometheus** — 메트릭 저장/조회

`grafana/otel-lgtm`은 이 넷 + **OTLP 수신기**를 **컨테이너 하나**로 묶은 **개발/데모 전용** 올인원이다.
포트 하나(4318)로 트레이스·메트릭을 받아 알아서 Tempo/Prometheus로 나눠 넣고, Grafana에 데이터소스를 자동 연결해 준다.

---

## 3. 구성 (그림)

```
                                 ┌───────────────────────── grafana/otel-lgtm (올인원) ───────────────────────┐
   client                        │   OTLP 수신(:4318)  →  Tempo(트레이스) · Prometheus(메트릭)                  │
     │  ①로그인→JWT                │                          ▲                                                  │
     ▼                            │                          │  Grafana UI(:3000) 에서 조회                      │
  gateway(8000) ──traceparent──▶ order(8080) ──traceparent──▶ payment(8081)                                    │
     │  span A                    │  span B                   │  span C                                          │
     └──────────── OTLP ──────────┴──────── OTLP ─────────────┴──── OTLP ───────────────────────────────────────┘
        (각 서비스가 자기 스팬/메트릭을 :4318 로 push. traceId 는 traceparent 로 이어져 A·B·C 가 한 트레이스)
```

- **전파(가로):** gateway가 order를 부를 때, order가 payment를 부를 때 `traceparent` 헤더가 실려 **traceId가 유지**된다 → 스팬들이 한 트레이스로 묶임.
- **전송(세로):** 각 서비스는 자기 스팬·메트릭을 **독립적으로** OTLP(:4318)로 push한다(백엔드가 traceId로 재조립).
- **로컬 vs docker:** 호스트 실행 땐 `localhost:4318`, 컨테이너 땐 `otel-lgtm:4318`(docker 프로파일 오버라이드).

---

## 4. 코드·설정 한 부분씩

### 4.1 의존성 (각 서비스 `build.gradle.kts`)
```kotlin
// Phase 8: 관측성 — 분산 트레이싱(OTel 브릿지 + OTLP 익스포터) + 메트릭 OTLP push.
//          전부 Boot BOM 관리(버전 생략).
implementation("io.micrometer:micrometer-tracing-bridge-otel")   // Micrometer → OTel 연결
implementation("io.opentelemetry:opentelemetry-exporter-otlp")   // 트레이스를 OTLP로 전송
implementation("io.micrometer:micrometer-registry-otlp")         // 메트릭을 OTLP로 push
```
- **왜 3개인가:** 트레이싱(브릿지) / 트레이스 전송(익스포터) / 메트릭 전송(레지스트리)은 관심사가 다르다. 명시적으로 두면 각 조각의 역할이 드러나 학습에 좋다.
- **왜 버전이 없나:** 세 아티팩트 모두 **Spring Boot 의존성 BOM**이 버전을 고정한다. 직접 핀하면 오히려 BOM과 충돌할 수 있다(§9 BOM).

### 4.2 공통 설정 (`config-repo/application.yml`) — 4개 서비스에 한 번에
```yaml
management:
  tracing:
    sampling:
      probability: 1.0            # 학습용 전량 샘플링. 기본값 0.1(10%)이면 대부분 요청이 트레이스 안 됨.
  otlp:
    tracing:
      endpoint: http://localhost:4318/v1/traces   # ★ 기본값 없음 → 반드시 명시(없으면 익스포터가 아예 안 생김)
    metrics:
      export:
        url: http://localhost:4318/v1/metrics
        step: 10s                 # push 주기(기본 60s). 데모에서 빨리 보이게 낮춤.
```
- **service.name은 자동:** OTel의 `service.name`(트레이스에서 서비스를 구분하는 이름)은 **`spring.application.name`이 자동 매핑**된다. 별도 설정 불필요.
- **샘플링 100%는 학습용:** 운영에선 비용/저장 때문에 0.1~0.3으로 낮춘다.

### 4.3 게이트웨이만의 한 줄 (`config-repo/gateway-service.yml`)
```yaml
spring:
  reactor:
    context-propagation: auto     # WebFlux(리액티브) 파이프라인에서 트레이스 컨텍스트가 끊기지 않게
```
- 게이트웨이는 **WebFlux/Netty(리액티브)** 스택이다. 리액티브 파이프라인은 스레드를 넘나들기 때문에, 컨텍스트 자동 전파를 켜지 않으면 traceId가 중간에 유실될 수 있다.

### 4.4 docker 프로파일 오버라이드 (`config-repo/application-docker.yml`)
```yaml
management:
  otlp:
    tracing:  { endpoint: http://otel-lgtm:4318/v1/traces }   # localhost → 컨테이너 서비스명
    metrics:  { export: { url: http://otel-lgtm:4318/v1/metrics } }
```
- **왜 필요한가:** 컨테이너 안에서 `localhost`는 **자기 자신**이다. Phase 7에서 겪은 것과 같은 함정(§PHASE-7). 다른 컨테이너(관측성 백엔드)로 보내려면 **서비스명 DNS**를 써야 한다.

### 4.5 관측성 백엔드 (`deploy/compose/compose.yml`)
```yaml
otel-lgtm:
  image: grafana/otel-lgtm:0.29.1     # 버전 고정(재현성). 개발/데모 전용 올인원.
  ports:
    - "3000:3000"   # Grafana UI
    - "4317:4317"   # OTLP gRPC
    - "4318:4318"   # OTLP HTTP  ← 앱이 여기로 전송
  environment:
    GF_AUTH_ANONYMOUS_ENABLED: "true" # 로그인 없이 Grafana 열람(개발 편의)
    GF_AUTH_ANONYMOUS_ORG_ROLE: Admin
  healthcheck:
    test: ["CMD-SHELL", "cat /tmp/ready || exit 1"]   # ★ HTTP가 아니라 파일 기반 준비 신호
```
- **healthcheck가 특이한 이유:** otel-lgtm은 준비되면 `/tmp/ready` 파일을 만든다. 다른 서비스처럼 `wget .../actuator/health`로 확인할 수 없다.
- **앱은 이 컨테이너에 의존(depends_on)하지 않는다:** OTLP 전송은 “쏘고 잊는(fire-and-forget)” 방식이라, 백엔드가 잠깐 없어도 앱은 정상 동작한다(초기 몇 스팬만 유실).

### 4.6 로그 상관ID는 자동
트레이싱이 클래스패스에 있으면 Spring Boot가 **콘솔 로그 패턴에 `[서비스명,traceId,spanId]`를 자동 삽입**한다.
→ **logback 설정 파일이 없어도** 로그에서 traceId를 볼 수 있다(같은 요청의 로그를 눈으로 이어붙일 수 있음).

---

## 5. 요청 하나가 흐르는 순서

1. 클라이언트가 `POST :8000/orders` 호출 → **gateway**가 요청을 받고 **트레이스를 시작**(traceId 생성, span A).
2. gateway가 `lb://order-service`로 프록시하며 **`traceparent` 헤더**에 traceId를 실어 보냄.
3. **order**가 헤더를 읽어 **같은 traceId로 span B** 생성. 처리 중 결제가 필요해 `RestClient`로 payment 호출 →
   여기서도 `traceparent`가 자동으로 실림(Spring이 계측한 `RestClient.Builder`를 쓰기 때문).
4. **payment**가 **같은 traceId로 span C** 생성 → 결제 캡처.
5. 각 서비스는 자기 스팬을 **OTLP로 :4318**에 push. 백엔드(Tempo)가 traceId로 **A·B·C를 하나의 트레이스**로 재조립.
6. Grafana에서 그 트레이스를 열면 **gateway→order→payment 워터폴**과 각 구간 소요시간이 보인다.

---

## 6. 원리 / 트레이드오프

- **왜 코드가 거의 안 바뀌나:** 계측은 **자동 계측(auto-instrumentation)**이다. 스프링의 웹 서버/클라이언트(Web MVC, WebFlux, RestClient)에 Micrometer 관측 지점이 이미 박혀 있어, 의존성+설정만으로 스팬이 생기고 헤더가 전파된다.
  - **딱 한 곳의 예외(이번에 잡은 버그):** order→payment의 `@LoadBalanced RestClient`는 **커스텀 빌더**(`RestClient.builder()`)로 만들어 Boot 자동구성 빌더를 대체했기 때문에 **관측 계측이 빠져 있었다**. 그래서 `traceparent`가 전파되지 않아 payment가 **별도 트레이스**로 떨어졌다. 해결: 빌더에 **`ObservationRegistry`를 주입**(`.observationRegistry(reg)`). 교훈 — 자동 계측은 **Spring이 계측해 준 빌더**를 쓰거나, 커스텀 빌더면 **관측 레지스트리를 직접 붙여야** 작동한다(§4.1, §7.2).
- **push(OTLP) vs pull(Prometheus scrape):** 메트릭을 앱이 밀어 넣는 방식(push)을 택했다. 올인원 백엔드에선 스크레이프 대상 설정이 필요 없어 **가장 단순**하기 때문. 운영에선 Prometheus scrape(pull)가 더 흔하다(서비스 디스커버리와 결합).
- **샘플링:** 전량(1.0)은 모든 요청을 추적해 학습엔 좋지만, 트래픽이 크면 저장·전송 비용이 급증한다. **꼬리 지연(tail latency)**만 잡으려면 낮은 확률 + 테일 기반 샘플링을 쓴다.
- **올인원의 한계:** `otel-lgtm`은 단일 프로세스라 **프로덕션 신뢰성 보장이 없다**(부하 시 유실 가능, 재시작 시 데이터 소실). 그래서 8b에서 컴포넌트를 분리한다.

---

## 7. 검증 (실증)

### 7.1 빌드·기동
- `./gradlew build` — **BUILD SUCCESSFUL**, 트레이싱 의존성 추가 후에도 기존 테스트(StockConcurrencyTest·GatewayRoutesTest) 통과.
- `docker compose up -d --build` — **9개 컨테이너 healthy**(기존 8개 + `otel-lgtm`). 메모리: otel-lgtm ~440MiB/2G, 앱 각 ~220~315MiB.

### 7.2 분산 트레이싱 — gateway→order→payment 한 트레이스
게이트웨이(8000)로 로그인→주문(201)을 만든 뒤 Tempo에서 조회:

```
traceId fcdd6f87b774c84c2dfb830f04123800   (root: gateway-service)
  gateway-service   http post            SERVER   ← 클라 요청 수신(POST /orders)
  gateway-service   HTTP POST            CLIENT   ← order 로 프록시
  order-service     http post /orders    SERVER   ← 수신
  order-service     http post            CLIENT   ← payment 호출
  payment-service   http post /payments  SERVER   ← 결제 캡처
```
→ **세 서비스의 스팬이 하나의 traceId로** 묶였다(참여 서비스 = `[gateway-service, order-service, payment-service]`).

> **이 단계에서 실제로 잡은 버그:** 처음엔 payment 스팬이 **별도 트레이스로 분리**됐다(order→payment 미전파).
> 원인은 `PaymentClientConfig`가 `RestClient.builder()`로 **관측 미계측 빌더**를 만들었기 때문(커스텀 `@LoadBalanced`
> 빌더를 선언하면 Boot 자동구성 빌더가 물러나며 관측 계측도 함께 빠진다). **`ObservationRegistry`를 주입**해
> `.observationRegistry(reg)`로 설정하니 order의 CLIENT 스팬이 생기고 `traceparent`가 전파되어 위처럼 한 트레이스가 됐다.
> → **“자동 계측은 Spring이 만든 빌더를 써야 작동한다”**는 원리를 몸으로 확인.

### 7.3 메트릭 — 4개 서비스 지표 수집
otel-lgtm 내부 Prometheus에 **181개 메트릭** 도착. `http_server_requests_*`·`jvm_memory_used_bytes`·`hikaricp_connections`·`system_cpu_usage` 등 표준 지표 확인. 서비스별 HTTP 시계열:
```
job=auth-service    job=order-service    job=payment-service    job=gateway-service   → 4개 모두 수집
```

### 7.4 로그 상관ID
트레이싱이 클래스패스에 있어 Spring Boot가 **콘솔 로그 패턴에 상관ID 필드를 자동 추가**했다
(`... [order-service] [thread] [traceId,spanId] ...`). 유휴 로그에선 비어 있고, **요청 처리 중 앱이 로그를 남기면 채워진다.**
(로그를 Loki로 **전송**해 트레이스↔로그로 점프하는 것은 8b.)

### 7.5 눈으로 보기
브라우저 **`http://localhost:3000`**(Grafana, 익명 접근) → Explore → Tempo에서 위 트레이스의 워터폴을, Prometheus에서 메트릭을 확인.

---

## 7B. Phase 8b — 로그→Loki · RED 대시보드 · 트레이스↔로그 점프

8a에서 “트레이스 하나”를 봤다면, 8b는 **세 기둥을 한 화면에서 상관지어** 본다.

### 7B.1 로그 → Loki (OTLP Logback appender)
콘솔에만 찍히던 로그를 **OTLP로 Loki에 전송**한다. 핵심은 “Logback → OTel SDK” 다리다.

- **의존성**(4서비스): `io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0:2.15.0-alpha`
  - ⚠️ **버전 정렬**: 이 아티팩트는 Boot BOM이 관리하지 않는다. Boot 3.5.15가 고정한 OTel SDK **1.49.0**과 맞는 **2.15.0-alpha**를 직접 핀한다(2.16+는 1.50 요구 → 충돌). `-alpha`는 API 안정성 경고일 뿐, 공식 권장 경로.
- **`logback-spring.xml`**(4서비스): Boot 기본 콘솔(상관ID 유지) + `OpenTelemetryAppender` 추가.
  ```xml
  <include resource="org/springframework/boot/logging/logback/defaults.xml"/>
  <include resource="org/springframework/boot/logging/logback/console-appender.xml"/>
  <appender name="OTEL" class="io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender"/>
  <root level="INFO"><appender-ref ref="CONSOLE"/><appender-ref ref="OTEL"/></root>
  ```
- **초기화 빈**(각 서비스): Logback은 스프링보다 **먼저** 초기화되므로, 컨텍스트가 뜬 뒤 `OpenTelemetryAppender.install(openTelemetry)`를 호출해야 appender가 살아난다(안 하면 no-op).
  ```java
  @Component
  class OpenTelemetryAppenderInstaller implements InitializingBean {
      private final OpenTelemetry openTelemetry;   // 생성자 주입
      public void afterPropertiesSet() { OpenTelemetryAppender.install(openTelemetry); }
  }
  ```
- **설정**: `management.otlp.logging.endpoint: http://localhost:4318/v1/logs`(docker→`otel-lgtm:4318`). 이 키가 Boot의 로그 OTLP 자동구성 트리거다(`opentelemetry-exporter-otlp`에 필요한 클래스가 이미 있어 추가 의존성 불필요).

### 7B.2 트레이스 컨텍스트 안의 업무 로그
로그가 트레이스와 이어지려면 **요청 처리 중(트레이스 컨텍스트가 살아있을 때)** 남긴 로그여야 한다.
그래서 `OrderService.placeOrder`에 업무 로그를 추가했다:
```java
log.info("주문 생성 시작 orderId={} customer={} 품목수={}", order.getId(), command.customerId(), command.items().size());
// ... 결제/저장 ...
log.info("주문 확정 orderId={} paymentId={} total={}", order.getId(), paymentId, order.getTotalAmount());
```
이 로그는 서버 스팬 안에서 실행되므로 appender가 **`trace_id`를 자동으로 붙여** Loki로 보낸다.

### 7B.3 커스텀 RED 대시보드 (프로비저닝)
`grafana/otel-lgtm`엔 이미 RED/JVM 대시보드가 내장돼 있지만, 그것들은 **OTel 규약 이름**(`http_server_request_duration_seconds_*`)을 조회한다.
우리는 **Micrometer**를 쓰므로 실제 이름이 **`http_server_requests_milliseconds_*`**라 내장 대시보드가 빈다(이게 “Grafana에 볼 게 없다”의 원인).
→ **우리 메트릭에 맞춘 커스텀 대시보드**를 작성해 provisioning 경로에 bind-mount(내장 대시보드와 공존).
- 파일: `deploy/grafana/dashboards/shopsaga-red.json`(대시보드) + `deploy/grafana/provisioning/shopsaga-dashboards.yaml`(provider).
- p95를 쓰려면 히스토그램 버킷이 필요 → `management.metrics.distribution.percentiles-histogram.http.server.requests: true`.
- 패널 PromQL(우리 이름 기준):
  | 패널 | PromQL |
  |---|---|
  | 요청률 | `sum by (job) (rate(http_server_requests_milliseconds_count[1m]))` |
  | 에러·거절 | `sum by (job, outcome) (rate(http_server_requests_milliseconds_count{outcome=~"CLIENT_ERROR\|SERVER_ERROR"}[1m]))` |
  | p95 지연(ms) | `histogram_quantile(0.95, sum by (job, le) (rate(http_server_requests_milliseconds_bucket[5m])))` |
  | JVM Heap | `sum by (job) (jvm_memory_used_bytes{area="heap"})` |

### 7B.4 검증(실증)
- **로그→Loki**: 4개 서비스 로그가 `service_name` 라벨로 Loki 도착. 주문 로그가 **`trace_id` 부착**(구조화 메타데이터) — 한 주문의 “생성 시작”·“확정”이 **같은 trace_id** 공유.
- **대시보드**: 커스텀 대시보드 로드 + 실데이터 표시 — 요청률(4서비스), **p95**(order 51ms·payment 139ms·gateway 93ms·auth 161ms), outcome(SUCCESS/CLIENT_ERROR), JVM/CPU.
- **트레이스↔로그 점프(왕복 증명)**: 같은 `trace_id`가 **Loki(로그) + Tempo(트레이스 gateway→order→payment)** 양쪽에 존재. Tempo `tracesToLogsV2`(`| trace_id="..."`, `service.name→service_name`)로 스팬→로그, Loki `derivedFields`로 로그→트레이스 점프.
- **보는 법**: Grafana(`:3000`) → **Dashboards → ShopSaga → “ShopSaga — RED + JVM (Phase 8b)”**. 로그는 Explore → Loki → `{service_name="order-service"}`.

---

## 8. 알려진 한계 → 해결 Phase

| 한계 | 설명 | 해결 |
|---|---|---|
| ~~로그가 Loki로 안 감~~ | **8b 해결** — OTLP logback appender로 4서비스 로그를 Loki 전송, 트레이스 로그엔 trace_id 부착 | ✅ Phase 8b |
| ~~커스텀 대시보드 없음~~ | **8b 해결** — 커스텀 RED+JVM 대시보드 프로비저닝(우리 Micrometer 메트릭 이름에 맞춤) | ✅ Phase 8b |
| **관측성 스택이 올인원** | 여전히 `otel-lgtm` 단일 컨테이너(개발 전용). Collector·Tempo·Loki·Prometheus **개별 분리**는 미진행 | 이후(선택) |
| **트레이스↔로그는 "트레이스 로그"만** | 트레이스 컨텍스트 안에서 남긴 로그만 trace_id를 단다. 요청 밖·기동 로그는 상관 안 됨(설계상 정상) | — |
| **span_id 미수집** | Loki엔 trace_id는 오지만 span_id는 비어 보임(트레이스 단위 상관엔 충분) | 이후(선택) |
| **게이트웨이 자체 스팬 이슈** | Spring Cloud Gateway 2025.0.x GH#3904(자기 스팬 export 누락 가능, 다운스트림 전파는 정상) | 상류 관찰 |
| **인프라 서비스 미계측** | discovery·config는 트레이싱/로그 미적용(요청 경로 아님) | 필요 시 후속 |
| **actuator·Grafana 무인증 노출** | 익명 Grafana(Admin)·상세 health | **Phase 15**(하드닝) |
| **비동기 전파 미검증** | Kafka 등 메시지 경계의 trace 전파는 아직 없음 | **Phase 9~12**(`traceparent` 보존) |

---

## 9. 용어사전

- **관측성(Observability):** 외부 신호(텔레메트리)만으로 내부 상태를 설명할 수 있는 성질.
- **트레이스/스팬:** 요청의 전체 경로 / 그 안의 한 구간. 부모-자식으로 워터폴 형성.
- **traceId / spanId:** 요청 전체 / 개별 구간의 식별자. 로그·스팬을 한 요청으로 묶는 열쇠.
- **컨텍스트 전파:** traceId 등을 다음 서비스로 넘김. HTTP는 `traceparent`(W3C Trace Context).
- **OpenTelemetry(OTel):** 벤더 중립 관측 표준(API/SDK).
- **OTLP:** OTel 텔레메트리 전송 프로토콜. HTTP 4318 / gRPC 4317.
- **Micrometer Tracing:** 스프링의 관측 파사드. `-bridge-otel`로 OTel에 연결.
- **LGTM:** Loki·Grafana·Tempo·Mimir(Prometheus). `grafana/otel-lgtm`은 이 스택+OTLP 수신기 올인원.
- **샘플링(sampling):** 트레이스를 얼마나 수집할지 확률. 학습=1.0, 운영=낮게.
- **자동 계측:** 프레임워크에 관측 지점이 내장돼 코드 변경 없이 스팬/전파가 생기는 것.
- **push vs pull:** 앱이 밀어 넣기(OTLP) vs 백엔드가 긁어가기(Prometheus scrape).
- **BOM:** Bill of Materials — 의존성 버전을 한꺼번에 고정하는 목록(Spring Boot BOM).

---

## 10. 참고 / 상호링크

- 직전: [PHASE-7-COMPOSE](PHASE-7-COMPOSE.md)(컨테이너·서비스명 DNS·프로파일) · [SERVICE-DISCOVERY](SERVICE-DISCOVERY.md)(`lb://`·RestClient 계측 대상)
- 큰 그림: [REVIEW-PART-A](REVIEW-PART-A.md) · 로드맵: [`MSA-LEARNING-PLAN.md`](../MSA-LEARNING-PLAN.md)(부록 B: 트레이싱/메트릭 설정)
- 다음: **Phase 8b**(로그→Loki·대시보드·스택 분리) → **Phase 9**(Kafka: 메시지 경계 trace 전파)
- 공식: Spring Boot Actuator(Tracing) · Micrometer OTLP · github.com/grafana/docker-otel-lgtm

*각 단계의 “알려진 한계 → 해결 Phase”는 [README](../README.md) 인덱스에서 모아 볼 수 있습니다.*
