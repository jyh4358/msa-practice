# 로컬 오케스트레이션 — Docker Compose

> **이 문서는 Phase 7 작업을 설명합니다.** 처음 보는 사람도 끝까지 이해하도록 개념 → 그림 →
> 실제 코드/설정 → 동작 원리 → 검증 → 한계 순으로 정리했습니다.
>
> (본문의 코드/설정 블록은 핵심만 보여주는 **발췌**이며, 실제 파일과 다를 수 있습니다. 해설은
> 작업을 마친 뒤 되짚어 정리한 **회고형** 설명입니다.)

---

## 0. 한 줄 요약

> **6개 서비스를 컨테이너 이미지로 만들어, `docker compose up` 한 번으로 전체 스택을 띄운다.**
> 서비스끼리는 `localhost`가 아니라 **compose 서비스명(DNS)** 으로 통신하고, 기동 순서는
> healthcheck로 보장한다. 로컬(호스트) 실행 방식도 그대로 유지된다.

---

## 1. 왜 필요한가? (Phase 6까지의 문제)

Phase 6까지는 서비스 6개를 **손으로 순서 맞춰**(config → discovery → 나머지) 각각 `./gradlew bootRun`으로 띄웠다. 문제:

- **재현·이식이 어렵다**: 새 사람이 셋업하려면 6개 터미널 + 순서 + 환경변수를 다 맞춰야 한다.
- **"내 머신에선 됨"**: JDK·포트·기동순서가 사람마다 달라 깨진다.
- **운영과 괴리**: 실제 배포는 컨테이너 단위인데, 호스트 프로세스로만 돌리면 그 간극을 못 배운다.

**Docker Compose**는 "이미지로 패키징 + 한 파일로 전체 기동·네트워크·순서 선언"으로 이를 푼다. (다음 단계 k8s의 디딤돌이기도 하다.)

---

## 2. 핵심 개념

### 2.1 컨테이너 이미지 / 레이어드 jar
- **이미지**: 앱 + 실행 환경(JRE)을 한 덩어리로 굳힌 실행 템플릿. **컨테이너**는 그 이미지의 실행 인스턴스.
- **레이어드 jar**: Spring Boot jar를 변경 빈도별 4겹(dependencies / spring-boot-loader / snapshot-dependencies / application)으로 나눠 이미지 레이어로 쌓으면, **코드만 바뀌면 마지막 얇은 레이어만 다시 빌드**된다(캐시 효율).
  - Boot 3.3+ 추출 명령: `java -Djarmode=tools -jar app.jar extract --layers`(구 `layertools` 대체).

### 2.2 Compose와 서비스명 DNS
- `compose.yml`에 서비스들을 선언하면, 같은 **네트워크**에 붙은 컨테이너끼리 **서비스 이름으로 서로를 찾는다**(Docker 내장 DNS). 예: order-service가 `order-db:5432`, `config-service:8888`로 접속.
- 컨테이너 안의 `localhost`는 **자기 자신**이라, Phase 6까지의 `localhost:8761` 같은 값은 컨테이너에선 틀린다 → 서비스명으로 바꿔야 한다.

### 2.3 기동 순서: healthcheck + depends_on
- **healthcheck**: 컨테이너가 "준비됨"인지 판정하는 명령(여기선 `/actuator/health` 조회).
- **`depends_on: condition: service_healthy`**: 의존 서비스가 **healthy가 될 때까지 기다렸다가** 시작. → config → discovery → 앱 순서를 보장.

### 2.4 환경별 설정: `docker` 프로파일 + 부트스트랩 env
설정이 이미 중앙(Config Server)에 있으므로, "컨테이너용 차이"만 **`docker` 프로파일**로 얹는다:
- `config-repo/application-docker.yml`, `{service}-docker.yml` — `localhost` → 서비스명 오버라이드.
- 컨테이너에 `SPRING_PROFILES_ACTIVE=docker` 를 주면 Config Server가 `{app}-docker.yml`을 자동 병합.
- 단 **Config Server를 가리키는 값**(`spring.config.import`)과 Config Server 자신의 설정은 중앙에서 못 온다(닭-달걀) → **환경변수**로 준다.

### 2.5 `prefer-ip-address`
컨테이너의 Eureka 등록 시 `hostname: localhost` 대신 **`prefer-ip-address: true`** 로 컨테이너 IP(예: 172.18.0.7)를 등록한다. 그래야 게이트웨이가 `lb://`로 받은 주소가 **네트워크에서 실제로 닿는다**.

---

## 3. 이 프로젝트의 구성

```
 호스트 :8000/:8761/:8888 만 공개
        │
        ▼
┌──────────────────── docker network: shopsaga-net ────────────────────┐
│                                                                       │
│   config-service ──(config-repo 볼륨 마운트, ENCRYPT_KEY env)          │
│   discovery-service (Eureka)                                          │
│                                                                       │
│   auth ─ order ─ payment ─ gateway   (SPRING_PROFILES_ACTIVE=docker)  │
│     │       │        │        │       SPRING_CONFIG_IMPORT=config…8888 │
│     └───────┴────────┴────────┘  서로 서비스명 DNS로 통신              │
│   order-db  payment-db  (내부 전용, 호스트 미공개)                      │
└───────────────────────────────────────────────────────────────────────┘
```
- 공개 포트: **gateway 8000**(단일 진입점), discovery 8761·config 8888(대시보드/확인용). 나머지(auth/order/payment/DB)는 **내부 전용**.
- 기동 순서(자동): DB·config·discovery healthy → auth/order/payment/gateway.

---

## 4. 코드/설정 — 한 부분씩 해설

> 이 절의 코드는 핵심 **발췌**다.

### 4.1 재사용 Dockerfile (`deploy/docker/Dockerfile.service`)
```dockerfile
FROM eclipse-temurin:21-jre-alpine AS builder
WORKDIR /builder
ARG JAR_FILE
COPY ${JAR_FILE} application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted   # Boot 3.3+ 방식

FROM eclipse-temurin:21-jre-alpine
WORKDIR /application
COPY --from=builder /builder/extracted/dependencies/ ./
COPY --from=builder /builder/extracted/spring-boot-loader/ ./
COPY --from=builder /builder/extracted/snapshot-dependencies/ ./
COPY --from=builder /builder/extracted/application/ ./
ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS:-} -jar application.jar"]  # exec → SIGTERM 이 JVM에 → graceful
```
- **alpine JRE**: 경량 + arm64 지원 + busybox `wget` 내장(헬스체크 가능).
- **`exec java`**: java가 PID 1이 되어 `docker stop`의 SIGTERM을 받아 우아하게 종료.
- 하나의 Dockerfile을 `--build-arg JAR_FILE=...`로 6개 서비스에 재사용.

### 4.2 docker 프로파일 오버라이드 (config-repo)
`config-repo/application-docker.yml` (공통):
```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          jwk-set-uri: http://auth-service:9000/oauth2/jwks   # localhost → 서비스명
eureka:
  client:
    service-url:
      defaultZone: http://discovery-service:8761/eureka/
  instance:
    prefer-ip-address: true      # 컨테이너 IP로 등록
```
`config-repo/order-service-docker.yml`: `spring.datasource.url: jdbc:postgresql://order-db:5432/orderdb`
(username·`{cipher}` 비번은 `order-service.yml`에서 상속.)

### 4.3 compose.yml (핵심 발췌)
```yaml
name: shopsaga
networks: { shopsaga-net: { driver: bridge } }

x-app-common: &app-common          # 4개 앱 클라이언트 공통(YAML 앵커)
  networks: [shopsaga-net]
  environment:
    SPRING_PROFILES_ACTIVE: docker
    SPRING_CONFIG_IMPORT: "configserver:http://config-service:8888"   # 부트스트랩 = env
    JAVA_OPTS: "-Xms128m -Xmx320m"
  deploy: { resources: { limits: { memory: 512M } } }

services:
  config-service:
    build: { context: ../.., dockerfile: deploy/docker/Dockerfile.service, args: { JAR_FILE: services/config-service/build/libs/config-service-0.0.1-SNAPSHOT.jar } }
    ports: ["8888:8888"]
    environment:
      ENCRYPT_KEY: "${ENCRYPT_KEY:?...}"                               # .env 에서(미커밋)
      SPRING_CLOUD_CONFIG_SERVER_NATIVE_SEARCH_LOCATIONS: "file:/config-repo"
    volumes: ["../../config-repo:/config-repo:ro"]                     # config-repo 마운트
    healthcheck: { test: ["CMD-SHELL","wget -q -O /dev/null http://localhost:8888/actuator/health || exit 1"], ... }

  order-service:
    <<: *app-common
    build: { ..., args: { JAR_FILE: services/order-service/build/libs/order-service-0.0.1-SNAPSHOT.jar } }
    depends_on:
      config-service: { condition: service_healthy }
      discovery-service: { condition: service_healthy }
      order-db: { condition: service_healthy }
```
(gateway만 `ports: ["8000:8000"]`; auth/order/payment/DB는 미공개.)

### 4.4 시크릿 / 부트스트랩 env
- `deploy/compose/.env`(gitignore): `ENCRYPT_KEY=...` — config-service가 `{cipher}` 복호화에 사용. `.env.example` 제공.
- **왜 env인가**: `SPRING_CONFIG_IMPORT`는 "Config Server 위치"라 중앙에서 못 온다(닭-달걀). config-service의 `search-locations`도 마찬가지. 이 둘만 env, 나머진 전부 중앙 config.

---

## 5. 기동 흐름
```
docker compose up
  → order-db, payment-db 뜸 → healthy(pg_isready)
  → config-service 뜸 → config-repo 읽고 /actuator/health healthy
  → discovery-service 뜸 → healthy
  → (depends_on 이 위 healthy 를 기다렸다가) auth/order/payment/gateway 동시 기동
     · 각자 SPRING_CONFIG_IMPORT 로 config-service:8888 에서 docker 프로파일 설정 수신
     · Eureka(discovery-service:8761)에 컨테이너 IP로 등록
     · order→payment 는 http://payment-service(Eureka 해석), gateway 는 lb://…
  → 전부 healthy = 스택 준비 완료
```

---

## 6. 동작 원리 / 트레이드오프
- **localhost → 서비스명**: 컨테이너 격리 때문에 필수. 중앙 config 덕분에 `docker` 프로파일 파일 몇 개로 끝(서비스 코드/로컬 yml 불변).
- **`prefer-ip-address`**: hostname 등록은 다른 컨테이너가 그 이름을 못 풀 수 있어, IP 등록이 안전. (k8s에선 또 다른 방식 — Phase 16.)
- **로컬 vs docker 공존**: 로컬은 default 프로파일(`localhost`), 컨테이너는 `docker` 프로파일. **포트가 겹치므로 동시 실행은 안 함**(둘 중 하나만).
- **메모리**: 8GB Colima VM에 8컨테이너 → 각 `JAVA_OPTS`로 힙 상한, `deploy.resources.limits.memory`로 컨테이너 상한. JVM은 cgroup 인지.
- **이미지 빌드**: prebuilt jar 방식(`./gradlew bootJar` 후 `docker build`) — 학습·속도에 유리. CI에선 in-Docker 빌드(멀티스테이지 gradle)도 가능.

---

## 7. 검증 (실제 실행)
```
docker compose -f deploy/compose/compose.yml up -d --build
→ 8개 컨테이너 전부 healthy (config/discovery/gateway 공개, 나머지 내부)

Eureka(:8761):   AUTH/ORDER/PAYMENT/GATEWAY 모두 UP, 주소가 컨테이너 IP(172.18.0.x) ✅
Config(:8888):   /order-service/docker → 병합 4소스(order-service-docker→application-docker→order-service→application),
                 datasource.url = jdbc:postgresql://order-db:5432/orderdb, password = orderpw(복호화) ✅
Gateway(:8000):  로그인 → 무토큰 401 → POST /orders 201 CONFIRMED(order→payment 전파+DB 기록) → 역할 403/200 ✅
```

**실행법**
```bash
./gradlew bootJar
cp deploy/compose/.env.example deploy/compose/.env
docker compose -f deploy/compose/compose.yml up -d --build   # 기동
docker compose -f deploy/compose/compose.yml ps              # 상태
docker compose -f deploy/compose/compose.yml down            # 정지(-v 붙이면 DB 볼륨까지 삭제)
```

---

## 8. 알려진 한계 → 해결 Phase
| 한계 / 트레이드오프 | 해결 Phase |
|---|---|
| Compose는 **단일 호스트**(스케일링·셀프힐링·롤아웃 없음) | **Phase 16**(Kubernetes) |
| 이미지 빌드가 수동(`bootJar` → `up --build`), 태깅/레지스트리 없음 | **Phase 17**(CI/CD) |
| `.env`의 `ENCRYPT_KEY`·`prefer-ip-address` 등 여전히 학습용 단순화 | **Phase 15**(하드닝) |
| 관측성(로그/메트릭/트레이스 수집) 컨테이너 없음 | **Phase 8**(관측성 스택) |
| (이월) 분산 트랜잭션 고아 결제 | **Phase 10·12** |

---

## 9. 용어 사전
- **이미지 / 컨테이너**: 실행 템플릿 / 그 실행 인스턴스.
- **레이어드 jar / jarmode=tools**: 변경 빈도별로 나눠 캐시 효율을 높이는 Boot 패키징.
- **compose 서비스명 DNS**: 같은 네트워크의 컨테이너를 서비스 이름으로 찾기.
- **healthcheck / depends_on(service_healthy)**: 준비 판정 / 준비될 때까지 대기.
- **docker 프로파일**: 컨테이너 환경 오버라이드(`SPRING_PROFILES_ACTIVE=docker`).
- **prefer-ip-address**: Eureka에 컨테이너 IP로 등록.
- **부트스트랩 값**: Config Server를 가리키는 값 등, 중앙 config보다 먼저 필요한 값(→ env).

---

## 10. 더 알아보기
- Spring Boot 컨테이너 이미지: https://docs.spring.io/spring-boot/reference/packaging/container-images/dockerfiles.html
- Docker Compose 기동 순서: https://docs.docker.com/compose/how-tos/startup-order/
- Compose 파일 레퍼런스: https://docs.docker.com/reference/compose-file/

---

*관련 문서: [PHASE-6-CONFIG.md](PHASE-6-CONFIG.md)(Phase 6), [SERVICE-DISCOVERY.md](SERVICE-DISCOVERY.md)(Phase 4), [SETUP.md](SETUP.md). 전체 로드맵: 루트 `MSA-LEARNING-PLAN.md`.*
