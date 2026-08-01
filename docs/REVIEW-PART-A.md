# 1차 복습 — 파트 A: 동기 플랫폼 (Phase 0~7)

> ⚠️ **이 복습은 Phase 0~7 시점의 시스템을 다룹니다.** 이후 크게 바뀐 것 둘:
> **Phase 12** 부터 동기 REST 호출이 사라지고 Kafka 이벤트 Saga 가 됐으며,
> **Phase 16b** 에서 Eureka·Config Server 가 삭제되고 그 역할이 플랫폼(DNS·ConfigMap)으로 넘어갔습니다.

> Phase 0~7로 **동기(REST) 마이크로서비스 플랫폼**이 완성됐습니다. 파트 B(비동기·관측성)로 가기 전,
> 여기까지를 "하나의 시스템"으로 체화하기 위한 복습 자료입니다. **큰 그림 → 단계 회고 →
> 개념 자가진단 → 셀프 퀴즈 → 재현 체크리스트 → 아직 열린 한계** 순.

---

## 1. 큰 그림 — 지금 무엇을 만들었나

```
                    클라이언트
                        │  ①로그인으로 JWT 받고, 이후 Authorization: Bearer
                        ▼
                 gateway (8000)  ── 단일 진입점 + 엣지 인증(JWT 검증) + lb:// 라우팅
                    │        │
        lb://order-service   lb://payment-service / lb://auth-service
                    ▼        ▼
   order (8080) ──http://payment-service──▶ payment (8081)      auth (9000)
     │  (JWT 전파)                              │                  발급/JWKS
     ▼                                          ▼
   orderdb                                    paymentdb
                        ▲                         
       모두 등록/조회 │ (Eureka)          모두 설정 수신 │ (Config Server)
                 discovery (8761)          config (8888) ← config-repo(+{cipher})
```

**핵심 한 문장:** 클라이언트는 게이트웨이(8000)만 알면 되고, 서비스들은 **이름으로 서로를 찾고(디스커버리)**,
**중앙에서 설정을 받고(config)**, **토큰으로 인증/인가(security)** 하며, **`docker compose up` 하나로 다 뜬다.**

---

## 2. 단계별 회고 (무엇을·왜)

| Phase | 무엇을 | 왜(어떤 문제를 풀려고) |
|---|---|---|
| 0 스캐폴드 | 모노레포·버전 카탈로그·Flyway·Colima | "내 컴에선 됨"·버전 지옥·스키마 표류 방지 |
| 1 모놀리스 | 주문+재고+결제 **단일 트랜잭션 ACID**, 비관적 락·QueryDSL·헥사고날 | 한 프로세스일 때의 정합성·동시성 기준선 확보 |
| 2 분리 | payment-service 떼어내고 **원격 REST 호출** | 서비스 경계 → **단일 트랜잭션 소멸**(고아 결제 문제 등장) |
| 3 게이트웨이 | Spring Cloud Gateway 단일 진입점 | 클라이언트가 내부 주소를 몰라도 되게(진입점 통일) |
| 4 디스커버리 | Eureka, `lb://`·`http://이름` | 하드코딩 `localhost:포트` 제거(위치를 이름으로) |
| 5 보안 | RS256 JWT, 리소스 서버, 역할 인가, 토큰 전파 | "누구인지/뭘 할 수 있는지" 부재 해소 |
| 6 설정 | Config Server(native) + `{cipher}` 암호화 | 설정 흩어짐·시크릿 하드코딩 해소 |
| 7 compose | Dockerfile + `docker compose` 전체 기동 | 손으로 6개 띄우던 걸 한 번에·재현 가능하게 |

> **관통하는 실 하나:** Phase 2에서 **깨진 분산 트랜잭션(고아 결제)** 은 아직 안 고쳐졌다. → 파트 C(Saga)까지 이어진다.

---

## 3. 핵심 개념 자가진단 (막힘없이 설명되면 통과)

각 항목을 **소리 내어 1~2문장으로** 설명해 보세요. 막히면 옆 문서를 다시.

- [ ] **헥사고날**: 포트/어댑터/유스케이스가 뭐고, 의존성 방향은? → `HEXAGONAL.md`, `PHASE-1-MONOLITH.md`
- [ ] **비관적 락 vs 낙관적 락**, 왜 재고에 비관적 락? 교착 회피는 어떻게? → `PHASE-1-MONOLITH.md`
- [ ] **왜 QueryDSL**(리포지토리에 `@Query` 금지)? → `HEXAGONAL.md`
- [ ] **단일 트랜잭션이 왜 사라졌나**(Phase 2), 그래서 생기는 문제는? → `PHASE-2-SPLIT-PAYMENT.md`
- [ ] **게이트웨이 = 리버스 프록시**: 호출 코드 없이 어떻게 전달? north-south vs east-west? → `PHASE-3-GATEWAY.md`
- [ ] **디스커버리**: 등록/하트비트/조회, **클라이언트 사이드 LB**, `lb://`(게이트웨이) vs `http://이름`(RestClient) → `SERVICE-DISCOVERY.md`
- [ ] **JWT/RS256/JWKS**: 서명은 누가·검증은 누가? 리소스 서버·엣지 인증·토큰 전파·`ROLE_` 규약 → `SECURITY.md`
- [ ] **Config Server**: `spring.config.import`(왜 bootstrap.yml 아님), **우선순위 역전**, `{cipher}` 신뢰 모델(키는 서버만) → `PHASE-6-CONFIG.md`
- [ ] **Compose**: 왜 `localhost`가 컨테이너에서 틀리나, `prefer-ip-address`, healthcheck 기동순서, docker 프로파일 → `PHASE-7-COMPOSE.md`

---

## 4. 셀프 퀴즈 (답은 아래 접힘)

1. 클라이언트가 `POST :8000/orders`를 부르면, 게이트웨이부터 payment까지 **어떤 순서로** 흐르나? 각 구간에서 "이름→주소" 해석은 누가 하나?
2. 게이트웨이 라우트는 `lb://order-service`인데 order→payment는 `http://payment-service`다. **왜 스킴이 다른가?**
3. Config Server를 껐다 켰다 하면 order-service는 부팅되나? DB엔 접속되나? (`optional:` + `{cipher}`)
4. 토큰 없이 `POST :8081/payments`를 직접 부르면? 왜?
5. `alice`(USER) 토큰으로 `GET /orders`(목록)를 부르면 몇 번? 왜?
6. 컨테이너에서 Eureka에 `hostname: localhost`로 등록하면 왜 문제인가? 해결책은?
7. `config-repo`의 DB 비밀번호가 `{cipher}...`인데, 클라이언트(order)는 이 암호문을 보나? 키가 필요한가?
8. `docker compose up` 시 order-service가 config-service보다 먼저 뜨지 않게 하는 장치는?

<details><summary>정답 펼치기</summary>

1. client→gateway(경로 매칭)→**lb로 order 인스턴스 선택**→order 처리 중 결제 필요→**LB로 payment 인스턴스 선택**→payment. 이름 해석은 게이트웨이/order 각자(클라이언트 사이드 LB, Eureka 캐시).
2. 라이브러리가 다름 — 게이트웨이는 Spring Cloud Gateway 필터(`lb://`), RestClient는 Spring Cloud LoadBalancer 인터셉터(`http://이름`).
3. 컨텍스트는 뜬다(`optional:`). 하지만 DB 비번이 **중앙에만** 있어 config 없으면 Postgres 접속 실패.
4. **401**. payment-service도 리소스 서버라 유효한 JWT를 직접 요구(게이트웨이만 믿지 않음 = defense in depth).
5. **403**. `GET /orders`(목록)는 `@PreAuthorize("hasRole('ADMIN')")` — USER 권한 부족.
6. 다른 컨테이너가 `localhost`를 자기 자신으로 풀어 못 닿는다. → `prefer-ip-address: true`(컨테이너 IP 등록).
7. 아니오. Config Server가 **복호화해서 평문으로** 넘겨준다. 클라이언트는 암호문도 키도 모른다(키는 `ENCRYPT_KEY` env로 서버만).
8. `depends_on: { config-service: { condition: service_healthy } }` + config-service의 healthcheck.

</details>

---

## 5. 재현 체크리스트 ("맨바닥에서 뜨면 체화된 것")

- [ ] `docker compose -f deploy/compose/compose.yml down` 으로 다 내린다.
- [ ] `./gradlew bootJar` → `docker compose ... up -d --build` 로 **한 번에** 8컨테이너 healthy.
- [ ] `:8761`(Eureka)에서 4개 서비스가 **컨테이너 IP**로 UP인지 확인.
- [ ] `:8888/order-service/docker` 가 병합·복호화된 설정을 주는지 확인.
- [ ] `:8000`에서 로그인→주문(201)→역할(403/200)까지 흐르는지.
- [ ] 각 단계에서 "왜 이 값이/이 순서가 필요한가"를 스스로 설명.
- [ ] (심화) 호스트 개별 실행(`bootRun`)으로도 동일 동작 재현 — 프로파일 차이 이해.

---

## 6. 아직 열려 있는 한계 (누적) → 해결 Phase

파트 A는 **동기·단일 호스트·기본 인가**까지다. 의도적으로 남긴 것들:

| 한계 | 해결 Phase |
|---|---|
| **분산 트랜잭션 정합성**(고아 결제, 원자성 소실) — Phase 2부터 열린 채 | **Phase 10**(outbox)·**12/13**(Saga) |
| **관측성 없음**(여러 서비스 거치는 요청 추적 불가) | **Phase 8** |
| **동기 결합**(payment 느리면 order도 느림, 장애 전파) | **Phase 9**(Kafka 비동기)·**14**(복원력) |
| **인가 얕음**(payment 공개 노출·IDOR·서비스 신원 없음) | **Phase 15** |
| **단일 호스트**(스케일·셀프힐 없음), 이미지 수동 | **Phase 16**(k8s)·**17**(CI/CD) |
| 시크릿 키 관리 단순(dev 키) | **Phase 15** |

---

## 7. 다음 — 파트 B (Phase 8~11)

- **8 관측성**: 로그·메트릭·**분산 트레이싱**(한 요청이 gateway→order→payment 거치는 걸 추적).
- **9 Kafka**: 동기 호출 일부를 **비동기 이벤트**로 → 결합도↓.
- **10 Outbox + 멱등성**: Phase 2의 고아 결제/이중 처리 문제를 제대로.
- **11 CQRS**: 읽기/쓰기 모델 분리.

> 준비되면 **Phase 8**부터. 이 문서의 자가진단·퀴즈를 다시 풀어 막히는 곳이 있으면 해당 Phase 문서로 복귀하세요.

*관련: [README](../README.md) 인덱스, `docs/PHASE-0~7`·`SERVICE-DISCOVERY`·`SECURITY`·`HEXAGONAL`. 로드맵: `MSA-LEARNING-PLAN.md`.*
