# 중앙 설정 (Central Config) — Spring Cloud Config

> **이 문서는 Phase 6 작업을 설명합니다.** 처음 보는 사람도 끝까지 이해하도록 개념 → 그림 →
> 실제 코드/설정 → 동작 원리 → 검증 → 한계 순으로 정리했습니다.
>
> (본문의 코드/설정 블록은 핵심만 보여주는 **발췌**이며, 실제 파일과 다를 수 있습니다. 해설은
> 작업을 마친 뒤 되짚어 정리한 **회고형** 설명입니다.)

---

## 0. 한 줄 요약

> **각 서비스에 흩어져 있던 설정을 "설정 서버(config-service)" 한 곳으로 모았다.**
> 서비스는 부팅할 때 config-service(:8888)에서 자기 설정을 내려받는다. DB 비밀번호 같은
> **시크릿은 암호문(`{cipher}`)으로 저장**되고, 서버가 응답 전에 복호화해서 넘겨준다.

---

## 1. 왜 필요한가? (Phase 5까지의 문제)

Phase 5까지 설정이 **서비스마다 각자의 `application.yml`에 흩어져** 있었다. 문제:

- **중복·불일치**: `eureka.defaultZone`, `jwk-set-uri` 같은 값이 여러 서비스에 복붙돼 있어, 하나 바꾸려면 여러 파일을 고쳐야 한다.
- **시크릿 하드코딩**: DB 비밀번호(`orderpw`, `paymentpw`)가 평문으로 git에 커밋돼 있었다(Phase 5 보안 리뷰가 지적한 항목).
- **환경별 분리 불가**: dev/stage/prod 설정을 코드와 함께 관리하면 재빌드 없이 바꿀 수 없다.

**중앙 설정 서버**는 "설정을 코드 밖 한 곳에서 관리하고, 시크릿은 암호화한다"로 이를 푼다.

---

## 2. 핵심 개념

### 2.1 Config Server / Config Client
- **Config Server**(우리의 `config-service`): 설정의 **단일 출처**. `GET /{서비스이름}/{프로파일}` 로 그 서비스의 병합된 설정을 JSON으로 돌려준다.
- **Config Client**(order/payment/gateway/auth): 부팅 시 Config Server에서 자기 설정을 받아온다.

### 2.2 백엔드: native vs git
Config Server가 설정 **파일을 어디서 읽느냐**:
- **git 백엔드**(운영 표준): 설정을 git 저장소에서 읽음. 이력·리뷰가 가능하지만 별도 repo 필요.
- **native 백엔드**(이 프로젝트, 로컬 학습용): **파일시스템 디렉터리**(`config-repo/`)에서 읽음. 설정이 간단.

### 2.3 `spring.config.import` (왜 `bootstrap.yml`이 아닌가)
예전(Spring Boot 2.3 이하)엔 클라이언트가 `bootstrap.yml` + "부트스트랩 컨텍스트"로 설정을 먼저 당겨왔다. **Boot 2.4+부터는 이 방식이 기본 비활성**이고, 대신 `application.yml`에 한 줄:
```yaml
spring:
  config:
    import: "optional:configserver:http://localhost:8888"
```
- `configserver:` — "이 URL의 Config Server에서 설정을 가져와라".
- `optional:` — 서버가 없어도 **부팅 실패하지 않고** 로컬 기본값으로 진행(dev 편의). 빼면(strict) 서버가 없을 때 시작 자체가 실패.
- ⚠️ **함정**: `spring-cloud-starter-config`가 클래스패스에 있으면 이 `import` 선언이 **반드시** 있어야 한다(없으면 부팅 시 `ImportException`). 테스트에서 서버를 안 쓸 땐 `spring.cloud.config.enabled=false`로 끈다.

### 2.4 우선순위 (가장 큰 함정)
`spring.config.import` 모델에서는 **로컬 `application.yml`이 원격(Config Server) 값보다 우선**한다.
→ 중앙으로 옮긴 키를 로컬 파일에 **남겨두면 로컬 값이 조용히 이긴다.** 옮긴 키는 로컬에서 반드시 삭제.

### 2.5 대칭키 암호화 (`{cipher}`)
- 비밀번호를 평문 대신 **암호문**으로 config 파일에 저장: `password: '{cipher}0d82...'`.
- Config Server가 **대칭키(AES)** 로 응답 전에 복호화해 클라이언트에 **평문**으로 준다.
- **키는 파일/ git에 두지 않고 `ENCRYPT_KEY` 환경변수로만** 서버에 주입한다(키를 리포지토리에 남기면 암호화 의미 없음).
- **클라이언트는 키가 필요 없다** — 복호화는 서버가 한다. (신뢰 모델: Config Server만 키를 안다.)
- Java 21은 무제한 강도 JCE가 기본 → 별도 라이브러리(Bouncy Castle 등) 불필요.

### 2.6 config-first vs discovery-first
- **config-first**(이 프로젝트): 클라이언트가 **직접 URL**(`http://localhost:8888`)로 Config Server를 찾는다. Eureka와 순서 의존이 없어 단순.
- discovery-first: Config Server도 Eureka에 등록하고 클라이언트가 Eureka로 찾는다. HA엔 유리하나 닭-달걀 순서 문제가 생김.

---

## 3. 이 프로젝트의 구성

```
                        config-repo/ (파일시스템)
                        ├ application.yml       (공통: eureka·jwk-set-uri·jpa)
                        ├ order-service.yml     (datasource + {cipher} 비번, payment url)
                        ├ payment-service.yml   (datasource + {cipher} 비번)
                        ├ gateway-service.yml   (라우트)
                        └ auth-service.yml
                                 ▲ 읽음(native)
                                 │
                        ┌─────────────────────────┐   ENCRYPT_KEY(env)로 복호화
                        │  config-service (8888)   │  ← Config Server
                        │  GET /{app}/{profile}    │
                        └─────────────────────────┘
              부팅 시 설정 fetch ▲     ▲     ▲     ▲
                                │     │     │     │  (config-first: 직접 URL)
                          ┌─────┴─┐ ┌─┴──┐ ┌┴───┐ ┌┴─────┐
                          │ auth  │ │order│ │pay │ │gateway│
                          │ 9000  │ │8080 │ │8081│ │ 8000  │
                          └───────┘ └────┘ └────┘ └───────┘
     (이들은 Phase 4의 Eureka 클라이언트이기도 하다. discovery-service·config-service는 서로 독립.)
```

- config-service는 **Eureka에 등록하지 않는다**(config-first, 순서 의존 제거).
- discovery-service는 **Config 클라이언트가 아니다**(자기 설정은 로컬 — Eureka를 독립 기준점으로 유지, 닭-달걀 회피).

---

## 4. 코드/설정 — 한 부분씩 해설

> 이 절의 코드는 핵심만 보여주는 **발췌**다. 전체는 저장소의 실제 파일 참고.

### 4.1 config-service (Config Server)
**의존성** (`build.gradle.kts`)
```kotlin
implementation("org.springframework.cloud:spring-cloud-config-server")  // 이름에 -starter- 없음(이게 본체)
implementation("org.springframework.boot:spring-boot-starter-actuator")
// 대칭키 암호화는 추가 의존성 불필요(spring-security crypto 전이 포함, Java 21 무제한 JCE).
```
**메인 클래스**
```java
@SpringBootApplication
@EnableConfigServer      // ← 이 한 줄이 Config Server 활성화(2025.0.x에서도 필수)
public class ConfigServiceApplication { ... }
```
**`application.yml`**
```yaml
server:
  port: 8888
spring:
  profiles:
    active: native                # native 백엔드(파일시스템)
  cloud:
    config:
      server:
        native:
          search-locations: file:///Users/younho/IdeaProjects/msa/config-repo  # 절대경로
          add-label-locations: false   # config-repo/master/ 추가 탐색 끔(flat)
# encrypt.key 는 파일에 두지 않는다 → ENCRYPT_KEY 환경변수로 주입.
```

### 4.2 config-repo 레이아웃 + 우선순위
```
config-repo/
├ application.yml        # 모든 클라이언트 공통 기본값
├ order-service.yml      # order 전용 (같은 키면 공통을 덮어씀)
├ payment-service.yml
├ gateway-service.yml
└ auth-service.yml
```
서버 병합 우선순위(높은→낮은): `{app}-{profile}.yml` > `{app}.yml` > `application-{profile}.yml` > `application.yml`.
우리는 default 프로파일만 쓰므로 `{app}.yml`이 공통 `application.yml`을 덮는다.

**공통 `config-repo/application.yml`** (발췌)
```yaml
spring:
  jpa:
    hibernate: { ddl-auto: validate }
    open-in-view: false
  security:
    oauth2:
      resourceserver:
        jwt:
          jwk-set-uri: http://localhost:9000/oauth2/jwks   # 한 곳에서 관리
eureka:
  client:
    service-url:
      defaultZone: http://localhost:8761/eureka/           # 한 곳에서 관리
  instance:
    hostname: localhost
```

### 4.3 시크릿 암호화 — `{cipher}` 값 만들기
config-service를 키와 함께 띄운 뒤 `/encrypt`로 암호문을 만든다:
```bash
export ENCRYPT_KEY='shopsaga-dev-encrypt-key-0123456789ab'   # (이 리포의 dev 키. 운영은 절대 커밋/공유 X)
./gradlew --no-daemon :services:config-service:bootRun        # 8888

curl -s -X POST http://localhost:8888/encrypt -H 'Content-Type: text/plain' -d 'orderpw'
# -> 0d82324915ffe995...   이 값을 config-repo/order-service.yml 에 넣는다:
```
```yaml
# config-repo/order-service.yml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/orderdb
    username: order
    password: '{cipher}0d82324915ffe995...'   # 반드시 홑따옴표! (안 하면 YAML이 {..}를 map으로 해석)
```
> `/encrypt`·`/decrypt`는 actuator가 아니라 Config Server의 상시 엔드포인트다(actuator exposure로 못 켠다).

### 4.4 클라이언트 전환 (order/payment/gateway/auth 공통)
**의존성**: `implementation("org.springframework.cloud:spring-cloud-starter-config")`
(`spring-cloud-starter-bootstrap`은 넣지 않는다 — 2025.0.x는 import 모델.)

**`application.yml` — 스트립 후** (identity + import + port만 로컬):
```yaml
spring:
  application:
    name: order-service                              # {application} 키 — 반드시 로컬
  config:
    import: "optional:configserver:http://localhost:8888"
server:
  port: 8080                                         # 포트는 로컬 유지(서버 다운 시 8080 충돌 방지)
  shutdown: graceful
```
datasource·jpa·flyway·security·eureka·payment url·actuator는 **전부 중앙**으로 이동, 로컬에선 삭제(우선순위 함정 회피).

---

## 5. 동작 흐름 (부팅 시)
```
order-service 부팅
  → spring.config.import 로 http://localhost:8888/order-service/default 요청
  → config-service: order-service.yml + application.yml 병합, {cipher} 비번을 ENCRYPT_KEY로 복호화
  → 평문 설정(datasource url/user/pw, eureka, jwk-set-uri...) 응답
  → order-service: 이 설정으로 DataSource·Eureka·Security 초기화 → Postgres 접속 → UP
```
클라이언트는 암호문도, 키도 보지 않는다. 서버가 복호화한 평문만 받는다.

---

## 6. 동작 원리 더 깊게 / 트레이드오프
- **우선순위 역전**: import 모델에선 로컬 `application.yml`이 원격보다 우선 → 옮긴 키는 로컬에서 지워야 중앙값이 적용된다(안 지우면 로컬이 조용히 이김).
- **`optional:` 실패 거동**: Config Server가 죽어도 클라이언트 컨텍스트는 뜬다. 단 order/payment는 DB 비번이 이제 **중앙에만** 있어, 서버가 없으면 DB 접속에서 실패한다(컨텍스트는 떠도 기능 불가). → "반드시 있어야" 하는 자세면 order/payment는 `optional:` 을 떼서 fail-fast로.
- **키 신뢰 모델**: `ENCRYPT_KEY`는 Config Server만 안다. 서버가 복호화 후 평문을 HTTP로 전달 → 그래서 서버↔클라이언트 구간 보안(내부망/TLS)이 다음 관심사.
- **refresh**: `@RefreshScope` + `POST /actuator/refresh`로 재시작 없이 일부 설정 갱신 가능. 단 `HikariDataSource`(DB 비번)는 refresh 불가 → 재시작 필요. 다중 인스턴스 일괄 갱신은 Spring Cloud Bus(후속).

---

## 7. 검증 (실제로 실행)
```
V1 서버 병합:  GET :8888/order-service/default → order-service.yml, application.yml 두 소스 확인 ✅
V2 암호화:     POST :8888/encrypt -d 'orderpw' → 암호문, /decrypt → 'orderpw' ✅
V3 복호화:     :8888/order-service/default 의 spring.datasource.password = "orderpw"(평문) ✅
V4 클라이언트: config-service 켠 상태로 order/payment/auth/gateway 기동 → 4개 모두 health UP
               (order/payment UP = 중앙의 복호화된 DB 비번으로 Postgres 접속 성공) ✅
V5 회귀:       게이트웨이 통과 로그인→401(무토큰)→POST /orders 201 CONFIRMED→역할 403/200,
               /actuator/gateway/routes 4개 lb:// 라우트(중앙 config에서 로딩) ✅
```
빌드/테스트: `./gradlew build` **BUILD SUCCESSFUL**(gateway 테스트는 `spring.cloud.config.enabled=false`로,
order 동시성 테스트는 JPA/Flyway 프로퍼티 명시로 config-service 없이 자립).

**실행 순서**: `config-service`(ENCRYPT_KEY 필요) → `discovery-service` → auth/order/payment/gateway.

---

## 8. 알려진 한계 → 해결 Phase
| 한계 / 트레이드오프 | 해결 Phase |
|---|---|
| `ENCRYPT_KEY`를 env로만 관리(보관소 없음), dev 키를 문서에 노출 | **Phase 15**(Vault/KMS 등 시크릿 보관) |
| `/encrypt`·`/decrypt`·설정 조회에 **인증 없음**(누구나 접근) | **Phase 15**(Config Server 보안) / 내부망 한정 **Phase 7** |
| config-service **단일 인스턴스 = SPOF**, native(파일) 백엔드 | **Phase 7**(compose로 컨테이너화) / 운영은 git 백엔드·HA |
| `optional:` 라 서버 없이도 컨텍스트는 떠서 실패가 늦게 드러남 | (선택) order/payment strict 전환 · **Phase 7** |
| 다중 인스턴스 일괄 refresh 불가(Bus 없음) | 후속(Spring Cloud Bus) |
| 포트는 여전히 로컬(중앙화 안 함) — 의도적(충돌 방지) | 수용 |

---

## 9. 용어 사전
- **Config Server / Client**: 설정의 단일 출처 / 그걸 받아쓰는 서비스.
- **native 백엔드**: 파일시스템 디렉터리에서 설정을 읽는 방식(vs git 백엔드).
- **`spring.config.import`**: 클라이언트가 Config Server를 가리키는 선언(구 `bootstrap.yml` 대체).
- **`optional:`**: 서버가 없어도 부팅 실패 안 함.
- **`{cipher}`**: 암호문 접두사. 서버가 응답 전 대칭키로 복호화.
- **대칭키(AES)**: 암·복호화에 같은 키. 여기선 `ENCRYPT_KEY` env.
- **config-first**: 클라이언트가 직접 URL로 Config Server를 찾음(vs discovery-first).
- **우선순위 역전**: import 모델에서 로컬 설정이 원격보다 우선.
- **`@RefreshScope` / `/actuator/refresh`**: 재시작 없이 설정 갱신(제약 있음).

---

## 10. 더 알아보기
- Spring Cloud Config(공식): https://docs.spring.io/spring-cloud-config/reference/
- `spring.config.import` (Boot Config Data): https://docs.spring.io/spring-boot/reference/features/external-config.html
- Config 암호화/복호화: https://docs.spring.io/spring-cloud-config/reference/server/encryption-and-decryption.html

---

*관련 문서: [SECURITY.md](SECURITY.md)(Phase 5), [SERVICE-DISCOVERY.md](SERVICE-DISCOVERY.md)(Phase 4), [HEXAGONAL.md](HEXAGONAL.md), [SETUP.md](SETUP.md). 전체 로드맵: 루트 `MSA-LEARNING-PLAN.md`.*
