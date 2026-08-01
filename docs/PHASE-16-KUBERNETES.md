# Phase 16a — 로컬 Kubernetes 로 이전 (kind)

> **한 줄 요약:** compose 로 잘 돌던 서비스를 **코드는 거의 그대로 둔 채** 쿠버네티스로 옮긴다.
> 이 단계에서 배우는 건 "k8s 문법"이 아니라 **역할의 이동** —
> 지금까지 우리가 코드·설정으로 하던 일(기동 순서 보장, 재시작, 설정 배포, 인스턴스 찾기)을
> **플랫폼이 대신하기 시작한다.**

초심자(Java/Spring 은 알지만 쿠버네티스는 처음) 기준으로 **왜 → 무엇을 → 어떻게** 순서로 설명합니다.
Phase 16 은 둘로 나뉩니다. 이 문서는 **16a — 클러스터를 세우고 order-service 하나를 이전**하는 단계입니다.
(16b 에서 Eureka·Config Server 를 삭제하고 전체 플랫폼을 올립니다.)

---

## 0. 이번 단계에서 한 일 (요약)

- **kind 클러스터** 를 Colima VM 안에 세웠다(단일 노드, k8s v1.36.1, arm64).
- **order-service + order-db + auth-service** 를 파드로 올렸다. **애플리케이션 코드 변경은 단 한 줄** —
  probe 하위 경로(`/actuator/health/**`)를 보안에서 열어준 것뿐.
- compose 의 구성 요소를 k8s 개념으로 1:1 이전했다:
  `environment/Config Server → ConfigMap` · `{cipher} 비밀번호 → Secret` ·
  `depends_on → readinessProbe` · `restart → livenessProbe` · `ports → Service(NodePort)` · `volumes → PVC`.
- **liveness 와 readiness 를 다르게 구성**했다(readiness 만 DB 포함). DB 를 죽여 **재시작 0회로 트래픽만 차단**되는 것을 실측했다.
- 자가치유(`kubectl delete pod`)·스케일 아웃(1→3)·PVC 영속성·`ImagePullBackOff` 함정을 전부 재현했다.
- **Kafka 가 없는데도 `POST /orders` 가 201 을 돌려줬다** — Phase 10 outbox 의 성질이 k8s 에서도 그대로 증명됐다.

---

## 1. 왜 — compose 로는 안 되는 것

Phase 7 의 Docker Compose 는 훌륭했다. 한 줄로 15개 컨테이너가 뜬다. 그런데 다음 것들을 못 한다.

**① 죽으면 살아나지만, "고장난 채로 살아있는" 건 모른다.**
compose 의 `restart: unless-stopped` 는 **프로세스가 종료됐을 때만** 다시 띄운다.
JVM 이 살아 있는데 DB 커넥션 풀이 고갈됐거나 데드락에 빠졌다면? 컨테이너는 `running` 이고,
compose 는 아무것도 하지 않으며, 게이트웨이는 계속 그리로 요청을 보낸다.
**필요한 건 "살아있나"가 아니라 "제대로 동작하나"를 묻는 장치다.**

**② 기동 순서를 `depends_on: condition: service_healthy` 로 붙잡아야 한다.**
Phase 7 에서 우리는 DB → config → discovery → 앱 순서를 compose 에 명시했다.
그런데 이 순서는 **기동 시점에만** 유효하다. 운영 중 DB 가 잠깐 끊기면? 아무 일도 일어나지 않는다.
게다가 순서를 명시할수록 전체 기동이 직렬화되어 느려진다.

**③ 인스턴스를 늘리는 수단이 없다(실질적으로).**
`docker compose up --scale order-service=3` 은 되지만, 그러면 호스트 포트가 충돌한다.
포트 매핑을 빼면 이번엔 밖에서 못 닿는다. **여러 인스턴스 앞에 서는 안정적인 주소**가 없다.
그래서 Phase 4 에서 **Eureka** 를 세웠다 — 애플리케이션이 애플리케이션을 찾는 방식으로.

**④ 설정을 바꾸려면 Config Server 라는 또 하나의 서비스가 살아 있어야 한다.**
Phase 6 의 Config Server 는 모든 서비스의 **기동 의존성**이다. 그게 죽으면 아무도 못 뜬다.
설정을 위해 서비스를 하나 더 운영하고, 그 서비스의 가용성을 또 걱정한다.

> **쿠버네티스의 대답은 일관된다: 그건 애플리케이션이 할 일이 아니다.**
> 헬스 판단·재시작·주소 해석·설정 주입·부하 분산을 **플랫폼이 제공**하면,
> 애플리케이션은 자기 비즈니스만 하면 된다. 그게 Phase 16 의 전부다.

---

## 2. 개념 — 쿠버네티스 최소 용어

처음 보는 사람을 위해, **compose 의 무엇에 대응하는가**로 설명한다.

| k8s | 한 줄 정의 | compose 대응 |
|---|---|---|
| **Pod** | 함께 배치되는 컨테이너 묶음. **스케줄링·재시작의 최소 단위** | 컨테이너 1개 (대충) |
| **Deployment** | "이 파드를 N개 유지해라"는 선언. 파드가 죽으면 새로 만든다 | `restart` + `--scale` |
| **ReplicaSet** | Deployment 가 내부적으로 만드는 "N개 유지" 실행자. 롤아웃마다 새로 생긴다 | 없음 |
| **Service** | 파드 묶음 앞의 **고정 이름 + 고정 IP**. 파드 IP 가 바뀌어도 이름은 그대로 | compose 네트워크의 서비스명 |
| **ConfigMap** | 설정을 담는 키-값. 환경변수나 파일로 파드에 주입 | `environment` / Config Server |
| **Secret** | 비밀값용 ConfigMap. **암호화는 아니다**(§8 참고) | `{cipher}` + ENCRYPT_KEY |
| **PersistentVolumeClaim(PVC)** | "이만한 디스크를 주세요"라는 요청. 파드가 죽어도 남는다 | named volume |
| **Namespace** | 이름의 방. DNS·RBAC·쿼터의 경계 | compose 의 `name:` (프로젝트) |
| **probe** | kubelet 이 주기적으로 컨테이너에 던지는 질문 | `healthcheck` |

### 세 가지 probe — 이게 이 Phase 의 핵심이다

| probe | 묻는 것 | 실패하면 | 여기에 넣으면 안 되는 것 |
|---|---|---|---|
| **startup** | "아직 기동 중인가?" | 다른 probe 를 **보류**한다. 상한 초과 시 컨테이너 재시작 | — |
| **liveness** | "이 프로세스를 죽였다 살리면 나아지는가?" | **컨테이너를 죽이고 재시작** | ⚠️ **외부 의존성**(DB·Kafka·다른 서비스) |
| **readiness** | "지금 트래픽을 받아도 되는가?" | **Service Endpoints 에서 제외**(죽이지 않음) | — (의존성을 넣는 게 맞다) |

**왜 liveness 에 DB 를 넣으면 안 되나.**
DB 가 5분 끊겼다고 하자. liveness 에 db 가 들어 있으면 **모든 파드가 동시에** 재시작 루프에 빠진다.
재시작해도 DB 는 안 고쳐지므로 무한 반복이다. 그리고 DB 가 살아난 순간
방금 재시작한 파드 수십 개가 **동시에 커넥션 풀을 채우려 들어** DB 를 다시 눕힌다.
반대로 readiness 만 실패시키면 파드는 살아 있고(캐시·JIT 워밍업 유지), 트래픽만 끊기며,
DB 가 돌아오면 **재시작 없이** 즉시 복귀한다. §7 에서 이걸 그대로 측정한다.

### kind 는 무엇인가

**k**ubernetes **in** **d**ocker. 쿠버네티스 노드를 **도커 컨테이너로** 띄운다.

```
macOS (18GB)
└─ Colima VM (12GB, arm64)
   └─ docker 컨테이너: shopsaga-control-plane   ← 이게 "노드"다
      └─ containerd
         └─ 파드들: order-service, order-db, auth-service, (etcd·apiserver·CoreDNS…)
```

컨테이너 안에 컨테이너다. 그래서 두 가지가 따라온다.

1. **이미지가 자동으로 공유되지 않는다.** Colima 의 도커 데몬에 빌드한 이미지를 노드 안 containerd 는 모른다
   → `kind load docker-image` 로 밀어 넣어야 한다(§8 의 대표 함정).
2. **포트를 두 번 뚫어야 한다.** 파드 → 노드(NodePort) → 노드 컨테이너 밖(kind 의 `extraPortMappings`) → macOS(Colima 포트 포워딩).

---

## 3. 구성 — 무엇을 어떻게 배치했나

```
                        macOS :30080
                             │  (Colima 포트 포워딩)
                             ▼
  ┌──── kind 노드 컨테이너 (shopsaga-control-plane) ─────────────────────┐
  │  :30080 (NodePort)                                                   │
  │      │                                                               │
  │      ▼                                                               │
  │  Service order-service (ClusterIP 10.96.223.176, NodePort 30080)     │
  │      │  selector: app=order-service                                  │
  │      ├──────────────┬──────────────┐   ← 복제본만큼 분산(무작위)      │
  │      ▼              ▼              ▼                                 │
  │   Pod            Pod            Pod       image: shopsaga/order-service:0.0.1
  │   10.244.0.9   10.244.0.10   10.244.0.11                             │
  │      │                                                               │
  │      │ ① 설정: ConfigMap → /application/config/application.yml       │
  │      │ ② 비밀: Secret → SPRING_DATASOURCE_{USERNAME,PASSWORD}        │
  │      │ ③ 신원: Downward API → POD_NAME, NODE_NAME                    │
  │      │                                                               │
  │      ├── jdbc:postgresql://order-db:5432 ──▶ Service order-db ──▶ Pod postgres:18
  │      │                                                    └─ PVC 1Gi (local-path)
  │      └── http://auth-service:9000/oauth2/jwks ─▶ Service auth-service ─▶ Pod
  └──────────────────────────────────────────────────────────────────────┘
```

**핵심:** 화살표 어디에도 **Eureka 도 Config Server 도 없다.**
`order-db`·`auth-service` 는 k8s **Service 이름**이고, CoreDNS 가 ClusterIP 로 풀어준다.
Phase 4 에서 애플리케이션 라이브러리(Eureka client)가 하던 일을 이제 **DNS 가** 한다.

### 파일

| 파일 | 내용 |
|---|---|
| `deploy/k8s/kind-cluster.yaml` | 클러스터 정의(단일 노드 + 포트 매핑 30080·8000) |
| `deploy/k8s/build-and-load.sh` | bootJar → 도커 이미지 → `kind load` |
| `deploy/k8s/00-namespace.yaml` | 네임스페이스 `shopsaga` |
| `deploy/k8s/10-secrets.yaml` | DB 자격증명 |
| `deploy/k8s/20-order-db.yaml` | PVC + Deployment(Recreate) + Service |
| `deploy/k8s/30-auth-service.yaml` | ConfigMap + Deployment + Service |
| `deploy/k8s/40-order-service.yaml` | ConfigMap + Deployment(probe 3종·Secret·Downward API) + NodePort |

### 애플리케이션 코드 변경 (전체)

```java
// services/*/…/SecurityConfig.java  — 6개 서비스 모두 동일하게
.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
```

이게 **전부**다. `"/actuator/health"` 만 열려 있으면 `/actuator/health/liveness` 와
`/actuator/health/readiness` 가 **401** 을 돌려주고, kubelet 은 토큰을 붙이지 않으므로
readiness 실패(트래픽 차단) → liveness 실패(무한 재시작)로 이어진다.
**보안 설정이 probe 를 막는 것**은 Spring + k8s 조합에서 가장 흔한 첫 실패다.

---

## 4. 코드 — 결정적인 부분만

### ① Config Server 를 ConfigMap 으로 (코드 수정 없이)

```yaml
# deploy/k8s/40-order-service.yaml
data:
  application.yml: |
    spring:
      datasource:
        url: jdbc:postgresql://order-db:5432/orderdb     # ← Service DNS
      security:
        oauth2.resourceserver.jwt.jwk-set-uri: http://auth-service:9000/oauth2/jwks
```
```yaml
          volumeMounts:
            - { name: config, mountPath: /application/config, readOnly: true }
```

**왜 `/application/config` 인가.** Spring Boot 의 기본 설정 탐색 순서는

```
classpath:/  <  classpath:/config/  <  file:./  <  file:./config/     (뒤쪽이 이긴다)
```

이고, Dockerfile 의 `WORKDIR` 이 `/application` 이다. 즉 `/application/config/application.yml` 은
`file:./config/application.yml` 이 되어 **jar 안의 설정을 덮어쓴다**.
→ 이미지를 다시 만들 필요도, 코드를 고칠 필요도 없다.

그리고 jar 안에 남아 있는 Config Server 의존은 환경변수로 무력화한다:

```yaml
            - { name: SPRING_CONFIG_IMPORT, value: "" }   # optional:configserver:… 를 지운다
```

### ② Secret → 환경변수 (우선순위를 이용한다)

```yaml
            - name: SPRING_DATASOURCE_PASSWORD
              valueFrom: { secretKeyRef: { name: order-db-credentials, key: password } }
```

ConfigMap 파일에는 비밀번호를 **아예 쓰지 않았다**. Spring 의 프로퍼티 우선순위에서
**OS 환경변수 > `file:./config/`** 이므로, 파일에 없는 값을 환경변수가 채운다.
Phase 6 의 `{cipher}` + `ENCRYPT_KEY` 조합이 하던 일을 플랫폼이 대신한다
(그리고 복호화 키를 애플리케이션이 들고 있을 필요가 없어졌다).

### ③ liveness ≠ readiness — 이 Phase 의 알맹이

```yaml
    management:
      endpoint:
        health:
          probes:
            enabled: true              # ★ 이게 없으면 /actuator/health/{liveness,readiness} 자체가 없다
          group:
            readiness:
              include: readinessState,db     # 의존성 포함 → 트래픽만 끊는다
            liveness:
              include: livenessState         # ⚠️ db 를 넣지 않는다 → 재시작 루프 방지
```

기본값은 **둘 다 애플리케이션 상태만** 본다(의존성 미포함).
readiness 에 `db` 를 넣는 건 우리의 명시적 선택이고, liveness 에 넣지 않는 것도 명시적 선택이다.

```yaml
          startupProbe:
            httpGet: { path: /actuator/health/readiness, port: 8080 }
            periodSeconds: 5
            timeoutSeconds: 3
            failureThreshold: 36       # 5s × 36 = 최대 3분(Flyway 마이그레이션 포함)
```

**startupProbe 의 존재 이유:** JVM 은 느리게 뜬다. startup 이 성공하기 전까지 liveness/readiness 는
**아예 평가되지 않으므로**, liveness 를 짧고 민감하게(period 10s / failure 3) 유지하면서도
기동 중 오탐 재시작을 막을 수 있다. startupProbe 가 없으면 `initialDelaySeconds` 를 크게 잡아야 하고,
그러면 **정상 파드도** 그만큼 늦게 트래픽을 받는다.

> ⚠️ `timeoutSeconds` 기본값은 **1초**다. readiness 가 DB 를 조회하는 이상 1초는 빡빡하다 —
> 부하가 조금만 올라도 멀쩡한 파드가 Endpoints 에서 빠졌다 들어왔다 한다(flapping). 3초로 늘렸다.

### ④ RWO 볼륨 + RollingUpdate = 교착

```yaml
# deploy/k8s/20-order-db.yaml
spec:
  strategy:
    type: Recreate      # 기본값 RollingUpdate 로 두면 안 된다
```

기본 전략인 RollingUpdate 는 **새 파드를 먼저 띄우고 헌 파드를 지운다**.
그런데 PVC 의 accessMode 가 `ReadWriteOnce` 면 두 파드가 같은 볼륨을 동시에 잡을 수 없어
새 파드가 영영 `Pending` 에 걸리고, 헌 파드는 안 죽으므로 롤아웃이 멈춘다.
`Recreate` 는 **헌 파드를 먼저 죽인다**(그 대신 짧은 다운타임이 있다 — DB 니까 감수한다).

### ⑤ Downward API — 파드가 자기 이름을 안다

```yaml
            - name: POD_NAME
              valueFrom: { fieldRef: { fieldPath: metadata.name } }
```
```yaml
    info:
      pod: ${POD_NAME:unknown}
    management:
      info:
        env:
          enabled: true
```

k8s API 를 호출하지 않고(=권한 없이) 파드가 자기 메타데이터를 환경변수로 받는다.
`/actuator/info` 로 **어느 파드가 응답했는지** 볼 수 있게 되어, §7 의 스케일 실측이 가능해졌다.

---

## 5. 흐름 — 요청 하나가 지나가는 길

```
curl localhost:30080/orders                       (macOS)
   │
   ├─ Colima 포트 포워딩 ────────────▶ VM :30080
   ├─ docker 포트 매핑(extraPortMappings) ──▶ 노드 컨테이너 :30080
   ├─ kube-proxy 의 iptables 규칙 ──▶ 준비된(ready) 파드 중 하나를 무작위 선택 + DNAT
   │
   ▼  Pod order-service:8080
   ├─ Spring Security: Bearer 토큰 검증
   │     └─ JWKS 공개키가 없으면 http://auth-service:9000/oauth2/jwks 조회
   │            └─ CoreDNS: auth-service → 10.96.215.231 (ClusterIP)
   │                   └─ iptables → Pod auth-service:9000
   ├─ OrderService.place()
   │     └─ 한 트랜잭션: orders INSERT + saga_instance INSERT + outbox INSERT ×2
   │            └─ jdbc:postgresql://order-db:5432 → CoreDNS → ClusterIP → Pod postgres
   ▼
 201 Created  { status: PENDING }
```

**Kafka 는 이 경로에 없다.** 16a 에는 브로커가 없지만 주문은 성공한다 —
Phase 10 에서 "DB 쓰기와 이벤트 발행을 한 트랜잭션으로" 만들어 뒀기 때문이다.
이벤트는 `outbox` 테이블에 남아 있고, 16b 에서 Kafka 가 올라오면 릴레이가 그때 내보낸다.
(§7-④ 에서 실제로 미발행 2건을 확인한다.)

---

## 6. 원리 — 왜 이렇게 동작하나

### ① k8s 에는 `depends_on` 이 없다 — 일부러 없다

16a 첫 배포에서 order-service 는 **3번 재시작한 뒤에** 안정됐다.
DB 보다 먼저 떠서 Flyway 가 연결에 실패해 죽고, `CrashLoopBackOff` 로 물러났다가 다시 시도한 것이다.

```
Caused by: java.net.ConnectException: Connection refused
   at org.flywaydb.core.internal.jdbc.JdbcUtils.openConnection
```

이건 **버그가 아니라 설계**다. compose 는 "순서를 보장"하려 했지만, 순서 보장은 기동 시점에만 유효하고
운영 중 장애에는 무력하다. k8s 는 대신 **"실패하면 물러났다 다시 하라(exponential backoff)"** 를 택했다.
같은 메커니즘이 기동 실패든 운영 중 장애든 똑같이 동작한다 — 특수한 경우가 없다.

> 다만 "재시작 3번"이 정상이라는 뜻은 아니다. 운영에서는 앱이 DB 연결을 **재시도**하도록 만들거나
> (Hikari `initializationFailTimeout`, Flyway `connectRetries`) initContainer 로 대기시킨다.
> 학습용으로는 k8s 의 기본 동작을 그대로 보는 편이 낫다고 판단해 두었다.

### ② Service 는 프록시가 아니라 **iptables 규칙**이다

`kubectl logs service/order-service` 같은 건 없다. Service 는 프로세스가 아니기 때문이다.
kube-proxy 가 각 노드의 iptables(이 클러스터는 `Using iptables Proxier`)에
"ClusterIP 로 가는 패킷을 파드 IP 중 하나로 DNAT 하라"는 규칙을 심어 둔 것이 전부다.

그래서 두 가지 성질이 따라온다.

- **분산은 무작위다, 라운드로빈이 아니다.** iptables 의 `statistic mode random` 을 쓴다.
  §7-⑤ 의 실측이 13/11/6 으로 고르지 않은 이유다(표본이 커질수록 1/3 로 수렴한다).
- **커넥션 단위로 결정된다.** HTTP keep-alive 로 연결을 오래 물고 있으면 그 커넥션은 계속 같은 파드로 간다.
  (Phase 2 의 `RestClient` 처럼 커넥션 풀을 쓰는 클라이언트는 스케일 아웃 효과가 늦게 나타난다.)

### ③ readiness 실패는 파드를 죽이지 않고 **명단에서 뺀다**

EndpointSlice 를 들여다보면 이렇게 되어 있다.

```
10.244.0.9   ready=false  serving=false
10.244.0.10  ready=false  serving=false
10.244.0.11  ready=false  serving=false
```

주소는 **그대로 남아 있고** `conditions.ready` 만 false 로 바뀐다.
kube-proxy 는 ready 인 엔드포인트로만 규칙을 만들므로, 결과적으로 트래픽이 끊긴다.
DB 가 돌아오면 다음 probe 성공에 ready=true 로 돌아오고 — **파드는 그동안 한 번도 죽지 않았다.**

### ④ ConfigMap 을 바꿔도 애플리케이션은 안 바뀐다

ConfigMap 을 파일로 마운트하면 kubelet 이 파일 내용은 갱신해 준다(~1분 내).
하지만 **Spring 은 이미 읽은 프로퍼티를 다시 바인딩하지 않는다.** 그래서 셋 중 하나를 해야 한다.

1. `kubectl rollout restart deployment/…` — 새 파드가 새 설정을 읽는다(우리가 쓴 방법).
2. Phase 15 의 `POST /actuator/refresh` + `@ConfigurationProperties` — 재시작 없이 재바인딩.
3. Deployment 의 podTemplate 에 ConfigMap 해시를 넣어 **내용이 바뀌면 자동 롤아웃**(Helm 의 흔한 패턴 — Phase 18).

> 실측 중 하나 짚고 갈 것: 우리가 `kubectl apply` 했을 때 파드가 **자동으로** 새로 떴는데,
> 그건 ConfigMap 때문이 아니라 같은 파일 안 **Deployment 의 podTemplate(env 추가)이 바뀌었기 때문**이다.
> ConfigMap 만 바꿨다면 아무 일도 일어나지 않는다.

### ⑤ `imagePullPolicy` 기본값의 함정

명시하지 않으면 k8s 는 **태그를 보고 정한다**: `:latest` 면 `Always`, 그 외 태그면 `IfNotPresent`.
`kind load` 로 노드에 이미지를 넣어놨어도 태그가 `latest` 면 레지스트리로 나가서 실패한다.
그래서 `0.0.1` 처럼 **버전 태그를 붙이고 정책도 명시**했다. §7-⑧ 에서 이 실패를 일부러 재현한다.

---

## 7. 검증 — 실제로 측정한 것

> 환경: macOS 18GB / Colima **12GB·6CPU**(8GB→증설) / kind v0.32.0 / k8s **v1.36.1** arm64 /
> kubectl v1.36.3. **compose 는 전부 내린 상태.**

### ① 클러스터와 이미지 적재

```
$ kubectl get nodes -o wide
NAME                     STATUS   ROLES           VERSION   OS-IMAGE                  CONTAINER-RUNTIME
shopsaga-control-plane   Ready    control-plane   v1.36.1   Debian GNU/Linux 13       containerd://2.3.1

$ docker exec shopsaga-control-plane crictl images | grep shopsaga
docker.io/shopsaga/auth-service    0.0.1   83636db6d3c7a   124MB
docker.io/shopsaga/order-service   0.0.1   09de2aa11bfe8   181MB
```

### ② 기동 — `depends_on` 없는 세계

```
NAME                             READY   STATUS             RESTARTS     AGE
order-service-578886b9c9-kj88d   0/1     CrashLoopBackOff   2 (6s ago)   29s      ← DB 보다 먼저 떴다
...
order-service-578886b9c9-kj88d   1/1     Running            3 (72s ago)  95s      ← DB Ready 후 자력 회복
```

로그: `Connection to order-db:5432 refused` → Flyway 실패 → 종료 → backoff → 재시도 → 성공.
**사람이 개입하지 않았다.**

### ③ NodePort 도달 + probe 분리

```
$ curl localhost:30080/actuator/health            → HTTP 200, status UP
$ curl localhost:30080/actuator/health/liveness
{"status":"UP","components":{"livenessState":{"status":"UP"}}}
$ curl localhost:30080/actuator/health/readiness
{"status":"UP","components":{"db":{"status":"UP","details":{"database":"PostgreSQL",…}},
                             "readinessState":{"status":"UP"}}}
$ curl localhost:30080/orders                     → HTTP 401   (토큰 없음 — 보안은 그대로 산다)
```

의도한 대로 **liveness 에는 db 가 없고 readiness 에는 있다.**

### ④ 주문 흐름 — Kafka 없이도 접수된다

```
$ curl -X POST localhost:30080/orders -H "Authorization: Bearer $TOKEN" …
HTTP 201
{"id":"d276440e-…","status":"PENDING","totalAmount":20.00}

$ psql -c "SELECT …"
orders=1 outbox_미발행=2 saga=1

$ psql -c "SELECT version||' '||description||' '||success FROM flyway_schema_history"
1 init true          5 outbox true
2 inventory and payment true   6 processed messages true
3 remove local payment true    7 saga instance true
4 drop local stock true        8 processed commands true
```

- JWT 가 **k8s Service DNS 로 찾은** auth-service 의 JWKS 로 검증됐다.
- Flyway 8개 마이그레이션이 클러스터 안에서 실행됐다.
- **outbox 미발행 2건** = Phase 10 의 성질이 그대로 살아있다는 증거. 브로커 없이도 주문은 받는다.

### ⑤ 스케일 아웃 1 → 3

```
$ kubectl scale deployment/order-service --replicas=3
$ kubectl get endpointslices …
10.244.0.9   10.244.0.11   10.244.0.10

$ for i in $(seq 1 30); do curl -s …/actuator/info | jq -r .pod; done | sort | uniq -c
  13 order-service-8c4bbcfdf-c57x2
  11 order-service-8c4bbcfdf-txv9g
   6 order-service-8c4bbcfdf-kb57n
```

3개 파드 전부가 받았다. **13/11/6 = 무작위 분배**(iptables `statistic mode random`)이지 라운드로빈이 아니다.

### ⑥ ★ liveness ≠ readiness — DB 를 죽인다

```
[전]  Endpoints ready=true ×3      재시작 횟수 0 0 0

$ kubectl scale deployment/order-db --replicas=0        ← DB 장애 시뮬레이션
   (55초 대기)

[후]  order-service-…-c57x2   0/1  Running   0      ← READY 가 빠졌다
      order-service-…-kb57n   0/1  Running   0
      order-service-…-txv9g   0/1  Running   0
      Endpoints:  10.244.0.9 ready=false serving=false   (×3)
      재시작 횟수: 0 0 0                                  ← ★ 한 번도 안 죽었다

      파드 안에서 직접:
        liveness  → {"status":"UP","components":{"livenessState":{"status":"UP"}}}
        readiness → HTTP/1.1 503
      NodePort   → HTTP 000 (연결 실패 — Ready 파드가 하나도 없다)

$ kubectl scale deployment/order-db --replicas=1        ← 복구
[복구 후] 3개 파드 모두 1/1 Running,  ready=true ×3,  재시작 횟수 0 0 0,  NodePort → HTTP 200
```

**이것이 이 Phase 에서 가장 중요한 실측이다.**
DB 장애 동안 파드는 **한 번도 재시작되지 않았고**(liveness UP), 트래픽만 정확히 차단됐으며(readiness 503),
DB 가 돌아오자 **사람 개입 없이** 복귀했다. liveness 에 db 를 넣었다면 이 시나리오는
"3개 파드 전부 재시작 루프 → DB 복구 순간 커넥션 폭풍"이 됐을 것이다.

관련 이벤트도 두 단계로 찍혔다(증상이 시간에 따라 변한다 — 디버깅 시 헷갈리는 지점):

```
Readiness probe failed: HTTP probe failed with statuscode: 503          ← 초기(빠른 실패)
Readiness probe failed: … context deadline exceeded                     ← 이후(Hikari 가 커넥션 타임아웃까지 블록)
```

### ⑦ 자가치유 + PVC 영속성

```
$ kubectl delete pod order-service-8c4bbcfdf-c57x2
   → 4초 뒤 order-service-8c4bbcfdf-mzlm5 (0/1 Running) 이 이미 생성됨
   → 삭제된 파드 조회: Error from server (NotFound)
   → 대체 파드 Ready 까지 무중단:  200 200 200 200 200   (복제본 3개 덕분)

$ DB 파드를 통째로 교체한 뒤 (replicas 0 → 1, 새 파드 order-db-…-bsdnr)
$ psql -c "SELECT count(*) FROM orders"  →  orders=1        ← PVC 가 데이터를 지켰다
```

### ⑧ `kind load` 를 잊으면 (계획서 §582 의 함정 재현)

```
$ kubectl run pull-trap --image=shopsaga/order-service:9.9.9
pull-trap   0/1   ImagePullBackOff

Failed to pull image "shopsaga/order-service:9.9.9":
  failed to resolve reference "docker.io/shopsaga/order-service:9.9.9":
  pull access denied, repository does not exist or may require authorization
```

노드는 **도커 허브에 물어보러 나간다**. 로컬 이미지는 `kind load docker-image` 로 넣어야 보인다.

### ⑨ 자원

```
$ colima ssh -- free -h
               total   used   free   available
Mem:            11Gi   2.4Gi  4.5Gi      9.2Gi     ← 컨트롤플레인 + 파드 5개
```

16b 에서 Kafka·Mongo·관측성·나머지 서비스를 올릴 여유가 충분하다.

### ⑩ 회귀 테스트

`./gradlew build` — 기존 테스트 전부 통과(보안 매처 변경이 아무것도 깨뜨리지 않았다).

---

## 8. 이번 단계의 한계 → 어디서 해결되나

| # | 한계 | 왜 문제인가 | 해결 |
|---|---|---|---|
| 1 | **Eureka·Config Server 가 아직 코드에 남아 있다** | 16a 는 `enabled: false` 로 끄기만 했다. 의존성·설정이 그대로라 "플랫폼으로 이동"이 절반이다 | **Phase 16b** — 삭제 |
| 2 | **서비스 3개뿐** — Kafka·Saga·CQRS·관측성이 없다 | 주문이 `PENDING` 에서 멈춘다(outbox 미발행 2건) | **Phase 16b** |
| 3 | **Secret 은 암호화가 아니다** | `data:` 는 base64 일 뿐이고 etcd 에도 기본은 평문. RBAC 이 유일한 방어선 | Phase 18(External Secrets/SOPS) — 학습 범위 밖 |
| 4 | **auth-service 를 복제할 수 없다** | RS256 키쌍을 기동 시 메모리에 생성 → 파드마다 키가 다르다. replicas 2 면 산발적 401 | **Phase 16b**(키를 Secret 으로) |
| 5 | **`kubectl apply -f` 를 7번 친다** | 순서·중복·환경별 차이를 사람이 관리한다. 값 하나 바꾸려면 YAML 을 직접 고친다 | Phase 18(Helm/Kustomize) |
| 6 | **이미지 배포가 수동**(`kind load`) | 사람이 빌드하고 사람이 밀어 넣는다. 어느 커밋이 떠 있는지 추적 불가 | **Phase 17**(CI/CD + 레지스트리) |
| 7 | **컨테이너가 root 로 돈다** | `runAsNonRoot`·`readOnlyRootFilesystem`·NetworkPolicy 등 보안 기본값이 없다 | Phase 18 |
| 8 | **PVC 가 `local-path`** | 노드의 로컬 디렉터리다. 노드가 죽으면 데이터도 죽고, 멀티노드에선 파드가 그 노드에 묶인다 | 학습 범위 밖(운영은 CSI 스토리지) |
| 9 | **DB 가 Deployment** | 원래 StatefulSet 이 맞다(안정적 이름·순서·파드별 볼륨). `Recreate` 로 우회 중 | 학습 범위 밖 |
| 10 | **기동 시 CrashLoopBackOff 3회** | 정상 동작이지만 운영에선 알람이 울린다. 앱이 DB 연결을 재시도하는 게 낫다 | Phase 18(Flyway `connectRetries`) |
| 11 | **`kubectl top` 이 안 된다** | metrics-server 미설치 → HPA(자동 스케일)도 불가 | **Phase 16b** 또는 Phase 18 |
| 12 | **auth-service 가 밖에서 안 보인다** | ClusterIP 라 토큰 받으려면 `port-forward` 가 필요하다 | **Phase 16b**(Ingress) |

---

## 9. 용어

- **kind** — Kubernetes IN Docker. 노드를 도커 컨테이너로 띄우는 로컬 클러스터 도구.
- **컨트롤플레인** — 클러스터의 두뇌. `apiserver`(모든 요청의 관문) · `etcd`(상태 저장소) ·
  `scheduler`(파드를 어느 노드에) · `controller-manager`(선언과 현실의 차이를 메운다).
- **kubelet** — 각 노드의 대리인. 파드를 실제로 띄우고 **probe 를 실행**한다.
- **kube-proxy** — Service 의 iptables/nftables 규칙을 노드에 심는 데몬.
- **CoreDNS** — 클러스터 DNS. `order-db` → ClusterIP 해석.
- **ClusterIP / NodePort / LoadBalancer / Ingress** — 노출 수단 4종.
  안에서만(ClusterIP) → 노드 포트로(NodePort) → 클라우드 LB(LoadBalancer) → HTTP 경로 라우팅(Ingress, 16b).
- **EndpointSlice** — Service 뒤에 실제로 붙은 파드 주소 목록(+`conditions.ready`).
- **Downward API** — 파드가 자기 메타데이터(이름·노드·라벨)를 환경변수/파일로 받는 방법.
- **CrashLoopBackOff** — 컨테이너가 반복해서 죽어 k8s 가 재시작 간격을 늘려가며 물러난 상태(최대 5분).
- **ImagePullBackOff** — 이미지를 받아오지 못해 물러난 상태.
- **requests / limits** — 스케줄러가 예약해 주는 양 / 넘으면 안 되는 상한.
  메모리가 limits 를 넘으면 **OOMKilled**(경고 없이 즉사), CPU 는 스로틀링만 된다.

---

## 10. 참고

- 코드: `deploy/k8s/` (매니페스트 · `build-and-load.sh` · [README](../deploy/k8s/README.md))
- 이전 단계: [Phase 7 · Docker Compose](PHASE-7-COMPOSE.md) · [Phase 4 · 서비스 디스커버리](SERVICE-DISCOVERY.md) ·
  [Phase 6 · 중앙 설정](PHASE-6-CONFIG.md) · [Phase 10 · Outbox](PHASE-10-OUTBOX.md)
- 로드맵: [`MSA-LEARNING-PLAN.md`](../MSA-LEARNING-PLAN.md) §Phase 16
- 공식 문서:
  - [Kubernetes — Configure Liveness, Readiness and Startup Probes](https://kubernetes.io/docs/tasks/configure-pod-container/configure-liveness-readiness-startup-probes/)
  - [Spring Boot — Kubernetes Probes](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.kubernetes-probes)
  - [Spring Boot — Externalized Configuration (우선순위)](https://docs.spring.io/spring-boot/reference/features/external-config.html)
  - [kind — Loading an Image Into Your Cluster](https://kind.sigs.k8s.io/docs/user/quick-start/#loading-an-image-into-your-cluster)
