# 보안 — JWT 인증/인가 (Resource Server 패턴)

> **이 문서는 Phase 5 작업을 설명합니다.** 보안이 처음이어도 끝까지 이해하도록 개념 → 그림 →
> 이 프로젝트의 실제 코드/설정 → 동작 원리 → 검증 → 알려진 한계 순으로 정리했습니다.

---

## 0. 한 줄 요약

> **누구나 호출하던 API에 "로그인해서 받은 토큰(JWT)이 있어야 통과"를 붙였다.**
> 토큰은 `auth-service`가 **개인키로 서명(RS256)** 해 발급하고, 각 서비스는 `auth-service`가
> **공개키(JWKS)** 로 노출한 키로 **서명을 검증**한다. 게이트웨이가 진입점에서 1차로 막고,
> 각 서비스도 다시 검증한다(defense in depth). 토큰 안의 **역할(roles)** 로 권한도 구분한다.

---

## 1. 왜 필요한가? (Phase 4까지의 문제)

Phase 4까지는 **누구나** 게이트웨이/서비스를 호출할 수 있었다. 토큰도, 로그인도 없었다.
서비스 간 호출(order→payment)도 신뢰 경계가 없었다. 즉:

- 인증(authentication) 없음 → "당신이 누구인지" 모른다.
- 인가(authorization) 없음 → "무엇을 할 수 있는지" 구분이 없다.

Phase 5는 여기에 **인증**(로그인 → 토큰)과 **인가**(역할 기반 접근 제어)를 넣는다.

---

## 2. 핵심 개념

### 2.1 인증 vs 인가
- **인증(Authentication)**: "너 누구야?" — 로그인해서 신원을 증명(→ 토큰 발급).
- **인가(Authorization)**: "그걸 할 권한 있어?" — 신원에 따라 접근 허용/거부(역할·소유권 등).

### 2.2 JWT (JSON Web Token)
서명된 문자열 하나에 "누구(sub), 언제까지 유효(exp), 무슨 역할(roles)"을 담는다. 세 부분(`header.payload.signature`)이 점으로 연결된다. **서버가 세션을 저장하지 않는다(stateless)** — 토큰 자체가 신원 증명서다.

- **claim(클레임)**: payload 안의 각 항목(`sub`, `exp`, `roles` 등). "토큰이 주장하는 사실" 하나하나를 뜻한다.
- **전달 방법**: 클라이언트는 이후 요청마다 JWT를 HTTP 헤더 `Authorization: Bearer <JWT>` 형태로 보낸다. `Bearer`(소지자)는 "이 토큰을 가진 사람을 그 신원으로 인정한다"는 인증 스킴 이름이다.

### 2.3 대칭키 vs 비대칭키(RS256) + JWKS
- **HS256(대칭키)**: 발급자와 검증자가 **같은 비밀키**를 공유. 검증 서비스가 많아지면 비밀키가 여기저기 복사돼 위험.
- **RS256(비대칭키, 이 프로젝트)**: 발급자만 **개인키(private)** 로 서명, 검증자는 **공개키(public)** 로 검증. 검증 서비스는 공개키만 있으면 되고 서명은 못 한다(안전).
- **JWKS(JSON Web Key Set)**: 공개키를 JSON으로 노출하는 표준 엔드포인트(`/oauth2/jwks`). 검증 서비스는 이 URL(`jwk-set-uri`)만 알면 공개키를 받아 검증한다. 키에는 `kid`(key id)가 있어 여러 키를 구분한다.

### 2.4 Resource Server / 엣지 인증 / 토큰 전파
- **Resource Server**: JWT를 검증해 보호하는 서비스(우리의 gateway·order·payment).
- **엣지 인증**: 진입점(게이트웨이)에서 미인증 요청을 먼저 차단.
- **defense in depth**: 게이트웨이만 믿지 않고 각 서비스도 다시 검증(게이트웨이 우회 대비).
- **토큰 전파**: 게이트웨이 → order → payment 로 같은 Bearer 토큰을 넘겨 신원을 이어준다.

> **리액티브 스택 vs 서블릿 스택 (설정 API가 다른 이유)**
> 이 프로젝트에는 두 종류의 웹 스택이 섞여 있다.
> - **게이트웨이 = 리액티브(WebFlux)** — 논블로킹 스택. 보안 설정에 `ServerHttpSecurity`·`authorizeExchange`를 쓴다.
> - **order / payment = 서블릿(MVC)** — 전통적인 요청-스레드 스택. 보안 설정에 `HttpSecurity`·`authorizeHttpRequests`를 쓴다.
>
> **설정 API의 이름만 다를 뿐, 개념은 완전히 같다**: 셋 다 "JWT를 auth-service의 JWKS(공개키)로 검증하는 Resource Server"다. 아래 4.2(리액티브)와 4.3(서블릿)이 왜 다르게 보이는지는 이 스택 차이 때문이다.

### 2.5 역할(roles)과 `ROLE_` 규약
Spring Security의 `hasRole('ADMIN')`은 내부적으로 권한 이름 `ROLE_ADMIN`을 찾는다.
그래서 **토큰에는 순수 역할명**(`["USER","ADMIN"]`)만 담고, **검증하는 쪽이 `ROLE_`을 붙인다**.

---

## 3. 이 프로젝트의 구성

```
                          ┌──────────────────────────────┐
              ┌──JWKS 조회─▶│  auth-service (9000)          │  RSA 개인키로 서명
              │           │  POST /auth/login → JWT 발급   │  공개키를 /oauth2/jwks 로 노출
              │           │  GET  /oauth2/jwks (공개키)     │
              │           └──────────────────────────────┘
              │ (공개키로 서명 검증)      ▲ 로그인
              │                          │
   클라이언트 │  ①로그인                 │
   ──────────┼──POST :8000/auth/login──▶│ (게이트웨이가 lb://auth-service 로 전달, /auth/** 는 공개)
             │
             │  ②이후 요청에 Authorization: Bearer <JWT>
   ──────────┴──▶ ┌───────────────┐  검증+전파  ┌──────────────┐  전파  ┌──────────────┐
                  │ gateway (8000) │──────────▶│ order (8080) │──────▶│ payment(8081)│
                  │ 리소스 서버     │  Bearer   │ 리소스 서버   │ Bearer│ 리소스 서버   │
                  │ (엣지 인증)     │  통과     │ +역할 인가    │       │              │
                  └───────────────┘           └──────────────┘       └──────────────┘
                  세 리소스 서버 모두 auth-service의 JWKS(공개키)로 토큰 서명을 검증한다.
```

---

## 4. 코드/설정 — 한 부분씩 해설

> **이 절의 코드는 핵심만 보여주는 발췌/축약본이다.** import·예외 처리·빈 배선 등 곁가지는 생략했으므로, 그대로 복사-붙여넣기하면 컴파일되지 않을 수 있다. 전체 코드는 각 파일 경로(`config/...`, `adapter/...`)에서 확인하자.

### 4.1 auth-service — 토큰 발급 + JWKS

**RSA 키페어 생성 + JWKS 소스** (`config/RsaKeyConfig.java`)
```java
RSAKey rsaKey = new RSAKey.Builder(pub).privateKey(priv).keyID(UUID.randomUUID()).build();
JWKSource<SecurityContext> jwkSource = new ImmutableJWKSet<>(new JWKSet(rsaKey));
```
부팅 시 RSA 2048 키페어를 만든다. `kid`는 UUID. (인메모리라 재시작하면 `kid`가 바뀌어 이전 토큰은 무효 → 재로그인 필요. 학습용.)

**서명기** (`config/JwtConfig.java`)
```java
@Bean JwtEncoder jwtEncoder(JWKSource<SecurityContext> jwkSource) {
    return new NimbusJwtEncoder(jwkSource);   // 개인키로 RS256 서명
}
```

**토큰 발급** (`service/TokenService.java`)
```java
JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
JwtClaimsSet claims = JwtClaimsSet.builder()
        .subject(subject).issuedAt(now).expiresAt(now.plus(60, MINUTES))
        .claim("roles", roles)          // 순수 역할명(접두사 없음)
        .build();
jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
```

**로그인** (`web/AuthController.java`) — 실패 시 **명시적 401**(컨트롤러에서 던진 예외는 자동 401 변환이 안 되므로):
```java
if (user 없음 || !passwordEncoder.matches(pw, user.getPassword()))
    return ResponseEntity.status(401).build();
// 성공: ROLE_ 접두사를 떼고 roles 클레임으로 → 토큰 발급
```

**JWKS 노출** (`web/JwksController.java`)
```java
@GetMapping("/oauth2/jwks")
Map<String,Object> jwks() { return new JWKSet(rsaKey).toPublicJWKSet().toJSONObject(); }
// toPublicJWKSet() 이 개인키 성분(d,p,q,...)을 제거 → 공개키만 노출
```

### 4.2 게이트웨이 — 리액티브 리소스 서버(엣지 인증)

`config/SecurityConfig.java` (리액티브라 `ServerHttpSecurity`·`@EnableWebFluxSecurity`):
```java
http.csrf(ServerHttpSecurity.CsrfSpec::disable)
    .authorizeExchange(ex -> ex
        .pathMatchers("/auth/**").permitAll()        // 로그인은 공개(순서상 먼저!)
        .pathMatchers("/actuator/health").permitAll()
        .anyExchange().authenticated())              // 그 외엔 유효한 JWT 필요
    .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(...roles...)));
```
`application.yml`:
```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          jwk-set-uri: http://localhost:9000/oauth2/jwks   # auth-service 공개키
```

### 4.3 order / payment — 서블릿 리소스 서버

`adapter/in/web/SecurityConfig.java` (서블릿이라 `HttpSecurity`·`@EnableWebSecurity`):
```java
@EnableWebSecurity
@EnableMethodSecurity                                  // order 만: @PreAuthorize 활성화
@ConditionalOnWebApplication(type = SERVLET)           // 비-웹 테스트(NONE)에선 로딩 안 함
class SecurityConfig {
  SecurityFilterChain(HttpSecurity http) {
    http.sessionManagement(STATELESS).csrf(disable)
        .authorizeHttpRequests(a -> a.requestMatchers("/actuator/health").permitAll()
                                     .anyRequest().authenticated())
        .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(conv())));
  }
  // roles 클레임 → ROLE_ 권한
  JwtAuthenticationConverter conv() {
    var a = new JwtGrantedAuthoritiesConverter();
    a.setAuthoritiesClaimName("roles"); a.setAuthorityPrefix("ROLE_");
    var c = new JwtAuthenticationConverter(); c.setJwtGrantedAuthoritiesConverter(a); return c;
  }
}
```
> **`@ConditionalOnWebApplication(SERVLET)` 이유**: `StockConcurrencyTest`는 `webEnvironment=NONE`
> (비-웹)이라 `HttpSecurity` 빈이 없어 컨텍스트가 깨진다. 이 조건으로 비-웹 컨텍스트에선 보안 설정을
> 로딩하지 않아 도메인/동시성 테스트가 그대로 돈다. 운영(서블릿 웹앱)에서는 항상 활성이다.

**역할 인가 데모** (`OrderController.all()`):
```java
@GetMapping @PreAuthorize("hasRole('ADMIN')")   // ROLE_ADMIN 필요 → USER 토큰이면 403
List<OrderView> all() { ... }
```

### 4.4 토큰 전파 — order → payment

`adapter/out/payment/BearerTokenRelayInterceptor.java`:
```java
Authentication auth = SecurityContextHolder.getContext().getAuthentication();
if (auth != null && auth.getCredentials() instanceof AbstractOAuth2Token token)
    request.getHeaders().setBearerAuth(token.getTokenValue());  // 인바운드 토큰을 그대로 붙임
```
`PaymentClientConfig.java` — 인터셉터는 **`build()` 전에** 등록:
```java
RestClient.builder().requestInterceptor(new BearerTokenRelayInterceptor());
```
`SecurityContextHolder`는 **요청별 스레드-로컬(thread-local, 스레드마다 따로 저장되는 저장소)** 로, Spring Security가 현재 요청을 처리하는 스레드에 인증 정보를 담아 둔다. order→payment 호출은 **같은 요청 스레드에서 동기(blocking)로** 일어나므로, 그 순간에도 `SecurityContextHolder`가 가리키는 토큰이 그대로 유효하다.

> **`build()` 전에 등록하는 이유**: `RestClient.builder()`는 `build()`를 호출하는 순간의 설정을 스냅샷으로 굳혀 클라이언트를 만든다. 그래서 인터셉터는 반드시 `build()` **이전**에 붙여야 하고, `build()` 이후에 추가하면 이미 만들어진 클라이언트에는 반영되지 않아 무효다.

### 4.5 결정: `jwk-set-uri`는 왜 `lb://`가 아니라 직접 `localhost:9000`?

> **먼저 `lb`가 뭔지 요약**: `lb://`는 Phase 4에서 도입한 스킴으로, Spring Cloud LoadBalancer가 **Eureka(서비스 레지스트리)에 등록된 서비스 이름**(예: `auth-service`)을 실제 `host:port`로 해석해 준다. 즉 `lb://auth-service`는 "Eureka에게 auth-service의 실제 주소를 물어봐 라우팅하라"는 뜻이다. 자세한 배경은 [SERVICE-DISCOVERY.md](SERVICE-DISCOVERY.md) 참고.

JWT 검증기(`NimbusJwtDecoder`/reactive)는 **로드밸런싱 안 되는 평범한 HTTP 클라이언트**를 쓴다.
그래서 `http://auth-service/...`(Eureka 이름)는 해석하지 못한다. 로컬 dev에선 **직접 host:port**로
지정하는 게 가장 단순·확실하다. (프로퍼티라 Phase 7 compose에서 컨테이너 DNS로 바꾸면 코드 변경 불필요.)

### 4.6 스킴/규약 정리 (헷갈림 방지)
| 위치 | 이름 표기 |
|---|---|
| 게이트웨이 라우트 `uri` (Phase 4) | `lb://order-service` |
| `@LoadBalanced RestClient` (Phase 4) | `http://payment-service` |
| **리소스 서버 `jwk-set-uri` (Phase 5)** | **`http://localhost:9000/...`** (직접) |
| 토큰 역할 클레임 | 순수 역할명 → 검증 시 `ROLE_` 부여 |

---

## 5. 요청 흐름

### 5.1 로그인
```
POST :8000/auth/login {alice, secret}
  → 게이트웨이(/auth/** 공개) → lb://auth-service
  → auth-service: 비번 검증 → RS256 서명 JWT(sub=alice, roles=[USER], exp=+60m) 반환
```
### 5.2 인증된 요청 + 전파
```
POST :8000/orders  (Authorization: Bearer <JWT>)
  → 게이트웨이: JWKS 공개키로 서명·만료 검증 → 통과 → order 로 전달(Authorization 헤더 유지)
  → order: 다시 검증 → 재고 차감 → payment 호출 시 인터셉터가 같은 Bearer 전파
  → payment: 다시 검증 → 결제 캡처 → 201
```
토큰이 없거나 위조/만료면 게이트웨이(또는 서비스)가 **401**. 역할이 모자라면 **403**.

---

## 6. 동작 원리 더 깊게
- **서명 검증**: auth-service가 개인키로 서명 → 리소스 서버가 JWKS의 공개키로 검증. 위조 불가.
- **JWKS lazy fetch**: 리소스 서버는 첫 토큰 검증 시 JWKS를 가져와 캐싱하고, 모르는 `kid`가 오면
  다시 가져온다. 그래서 auth-service가 부팅 시 꺼져 있어도 다른 서비스는 정상 기동한다(자가 치유).
- **stateless + CSRF off**: 세션 없이 매 요청 토큰으로 인증 → 서버가 세션을 안 쓰므로 CSRF 방어
  대상(쿠키 기반 세션)이 아니라서 CSRF를 끈다. Bearer 토큰 API의 정석.
- **엣지 + 서비스 이중 검증**: 게이트웨이만 믿지 않는다. 서비스가 직접 호출돼도(게이트웨이 우회) 막힌다
  — 실제로 `POST :8081/payments`를 토큰 없이 부르면 401.

---

## 7. 검증 (실제로 실행한 스모크)
```
0) JWKS: RSA 공개키만 노출(개인키 d 없음)               ✅
1) 로그인(게이트웨이→auth) alice/admin → 토큰            ✅
2) 잘못된 비밀번호 → 401                                 ✅
3) 토큰 없이 /orders·/inventory → 401 (엣지 차단)        ✅
4) alice 토큰 → /inventory 200                           ✅
5) /orders(ADMIN 전용): alice=403, admin=200            ✅ (method security)
6) POST /orders (alice) → 201 CONFIRMED + paymentId     ✅ (order→payment 토큰 전파)
7) payment 직접 호출(토큰 없이) → 401                    ✅ (서비스도 강제)
8) 변조 토큰 → 401                                       ✅
9) /actuator/health → 200 (공개)                         ✅
```
> #6(201)과 #7(401)의 조합이 **토큰 전파가 실제로 동작**한다는 결정적 증거다.

데모 계정: `alice/secret`(USER), `admin/admin123`(USER+ADMIN).

---

## 8. 알려진 한계 → 해결 Phase (적대적 보안 리뷰 결과)

이번 단계는 **인증 + 기본 역할 인가**까지다. 아래는 리뷰에서 확정된, 의도적으로 **다음 단계로 미룬** 항목이다.

| 한계 / 갭 | 성격 | 해결 Phase |
|---|---|---|
| **payment 캡처가 공개 게이트웨이로 노출** — 인증된 USER면 `/payments` 직접 호출로 임의 결제 생성 가능(서비스 신원/스코프 없음) | 인가 깊이(A01/A04) | **Phase 15**(하드닝: 서비스 스코프/내부 전용화) — 또는 게이트웨이에서 `/payments` 라우트 제거 |
| **소유권 검증 없음(IDOR)** — 아무 인증 사용자나 `GET /orders/{id}`로 남의 주문 조회 가능. 주문이 토큰 subject에 묶이지 않음(customerId를 클라가 지정) | 인가 깊이(A01) | **Phase 15** (+주문-사용자 바인딩 도입) |
| JWT **issuer/audience 미검증**(서명·만료만 검증) — 방어 심화 부족 | 하드닝 | **Phase 15** (issuer/audience validator) |
| 게이트웨이 `permitAll(/auth/**)`가 `/auth/login`보다 넓음(현재 노출 없음, 잠재적) | 하드닝 | **Phase 15** (정확히 `/auth/login`만) |
| `/actuator/health` `show-details: always` 공개 | 하드닝/관측성 | **Phase 8/15** |
| DB 비밀번호·인메모리 키가 코드/설정에 하드코딩(재시작 시 키 회전) | 시크릿 관리 | **Phase 6**(Config) + **Phase 7**(keystore/compose) |
| 서비스간 호출이 사용자 토큰 재전파에 의존(서비스 자체 신원 없음) | 서비스 인증 | **Phase 15** (client-credentials/mTLS) |

> 리뷰에서 **정상 확인**된 것들: 리액티브/서블릿 배선, CSRF off+stateless 적절, `@ConditionalOnWebApplication`이
> 운영 보안을 끄지 않음, auth-service가 리소스 서버로 오작동하지 않음, 토큰 전파 스레드 안전성.

---

## 9. 용어 사전
- **JWT**: 서명된 토큰(신원 증명서). `header.payload.signature`.
- **RS256**: RSA 개인키 서명 / 공개키 검증(비대칭).
- **JWKS**: 공개키를 노출하는 표준 JSON 엔드포인트. `kid`로 키 구분.
- **Resource Server**: JWT를 검증해 접근을 보호하는 서비스.
- **엣지 인증**: 진입점(게이트웨이)에서의 1차 인증.
- **토큰 전파(relay)**: 인바운드 토큰을 다운스트림 호출로 넘김.
- **stateless**: 서버가 세션을 저장하지 않음(토큰이 상태).
- **인증/인가**: 누구인지 / 무엇을 할 수 있는지.
- **IDOR**: 소유권 검증 없이 ID로 남의 자원 접근.
- **claim(클레임)**: JWT payload 안의 각 항목(`sub`·`exp`·`roles` 등). 토큰이 주장하는 사실 한 조각.
- **Authorization / Bearer**: 토큰을 실어 보내는 HTTP 헤더(`Authorization: Bearer <JWT>`). `Bearer`(소지자)는 "토큰을 가진 자를 그 신원으로 인정"하는 인증 스킴.
- **리액티브(WebFlux) vs 서블릿(MVC)**: 논블로킹 스택 vs 요청-스레드 스택. 보안 설정 API가 각각 `ServerHttpSecurity`·`authorizeExchange` / `HttpSecurity`·`authorizeHttpRequests`로 다르지만, JWT 검증 개념은 동일.
- **lb / Eureka**: `lb://`는 Spring Cloud LoadBalancer 스킴. Eureka(서비스 레지스트리)에 등록된 서비스 이름을 실제 `host:port`로 해석해 라우팅(Phase 4).
- **SecurityContextHolder**: 현재 요청의 인증 정보를 담는 **스레드-로컬** 저장소(스레드마다 별도). 동기 호출 중에는 같은 스레드라 토큰이 유효.
- **스니펫(발췌)**: 문서의 코드 블록은 핵심만 추린 축약본. 그대로는 컴파일되지 않을 수 있으니 전체는 파일 경로에서 확인.

---

## 10. 더 알아보기 (공식 문서)
- Spring Security — OAuth2 Resource Server(JWT): https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html
- Reactive Resource Server: https://docs.spring.io/spring-security/reference/reactive/oauth2/resource-server/jwt.html
- Spring Security — Method Security(@PreAuthorize): https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html
- Nimbus JOSE + JWT / JWKS: https://connect2id.com/products/nimbus-jose-jwt

---

*관련 문서: [SERVICE-DISCOVERY.md](SERVICE-DISCOVERY.md)(Phase 4), [HEXAGONAL.md](HEXAGONAL.md)(아키텍처), [SETUP.md](SETUP.md)(실행). 전체 로드맵: 루트 `MSA-LEARNING-PLAN.md`.*
