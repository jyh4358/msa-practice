# Phase 16 — 로컬 Kubernetes 로 이전 (kind)

> **한 줄 요약:** compose 로 잘 돌던 서비스를 **코드는 거의 그대로 둔 채** 쿠버네티스로 옮긴다.
> 이 단계에서 배우는 건 "k8s 문법"이 아니라 **역할의 이동** —
> 지금까지 우리가 코드·설정으로 하던 일(기동 순서 보장, 재시작, 설정 배포, 인스턴스 찾기)을
> **플랫폼이 대신하기 시작한다.**

초심자(Java/Spring 은 알지만 쿠버네티스는 처음) 기준으로 **왜 → 무엇을 → 어떻게** 순서로 설명합니다.
Phase 16 은 둘로 나뉩니다.
- **[16a](#part-16a)** — 클러스터를 세우고 **order-service 하나**를 이전한다(§0~§9).
- **[16b](#part-16b)** — **Eureka·Config Server 를 삭제**하고 **전체 플랫폼**을 올린다(§10~).

<a id="part-16a"></a>

---
---

# 파트 16a — 클러스터를 세우고 서비스 하나를 옮긴다

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
| `deploy/k8s/31-order-service.yaml` | ConfigMap + Deployment(probe 3종·Secret·Downward API) + NodePort |

> 파일 번호는 16b 에서 서비스가 늘며 재정렬됐다(`40-` → `31-`). 또한 16b 부터 ConfigMap 은
> 매니페스트에 인라인으로 있지 않고 `deploy/config/` 의 파일에서 생성된다 — §11 참고.
>
> ⚠️ **Phase 18에서 `deploy/k8s/base/` 로 이동**하며 위 숫자 접두사(`00-`·`10-`·`20-`·`30-`…)가 전부 사라졌다.
> 위 표는 그 시점의 경로를 보여주는 **역사 기록**이며, 현재 경로는 [PHASE-18-KUSTOMIZE.md](PHASE-18-KUSTOMIZE.md) 참고.

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
# deploy/k8s/31-order-service.yaml (16a 시점 — 인라인 ConfigMap)
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

## 8. 16a 의 한계 → 어디서 해결되나

| # | 한계 | 왜 문제인가 | 해결 |
|---|---|---|---|
| 1 | **Eureka·Config Server 가 아직 코드에 남아 있다** | 16a 는 `enabled: false` 로 끄기만 했다. 의존성·설정이 그대로라 "플랫폼으로 이동"이 절반이다 | **Phase 16b** — 삭제 |
| 2 | **서비스 3개뿐** — Kafka·Saga·CQRS·관측성이 없다 | 주문이 `PENDING` 에서 멈춘다(outbox 미발행 2건) | **Phase 16b** |
| 3 | **Secret 은 암호화가 아니다** | `data:` 는 base64 일 뿐이고 etcd 에도 기본은 평문. RBAC 이 유일한 방어선 | → [BACKLOG.md](BACKLOG.md)(Sealed Secrets/External Secrets Operator — **정정**: Phase 18 은 이 항목을 다루지 않고 그대로 넘겼다) |
| 4 | **auth-service 를 복제할 수 없다** | RS256 키쌍을 기동 시 메모리에 생성 → 파드마다 키가 다르다. replicas 2 면 산발적 401 | **Phase 16b**(키를 Secret 으로) |
| 5 | **`kubectl apply -f` 를 7번 친다** | 순서·중복·환경별 차이를 사람이 관리한다. 값 하나 바꾸려면 YAML 을 직접 고친다 | Phase 18(Helm/Kustomize) |
| 6 | **이미지 배포가 수동**(`kind load`) | 사람이 빌드하고 사람이 밀어 넣는다. 어느 커밋이 떠 있는지 추적 불가 | **Phase 17**(CI/CD + 레지스트리) |
| 7 | **컨테이너가 root 로 돈다** | `runAsNonRoot`·`readOnlyRootFilesystem`·NetworkPolicy 등 보안 기본값이 없다 | → [BACKLOG.md](BACKLOG.md) |
| 8 | **PVC 가 `local-path`** | 노드의 로컬 디렉터리다. 노드가 죽으면 데이터도 죽고, 멀티노드에선 파드가 그 노드에 묶인다 | 학습 범위 밖(운영은 CSI 스토리지) |
| 9 | **DB 가 Deployment** | 원래 StatefulSet 이 맞다(안정적 이름·순서·파드별 볼륨). `Recreate` 로 우회 중 | 학습 범위 밖 |
| 10 | **기동 시 CrashLoopBackOff 3회** | 정상 동작이지만 운영에선 알람이 울린다. 앱이 DB 연결을 재시도하는 게 낫다 | 미해결 — **정정**: Phase 18 은 이 항목을 다루지 않았다 → [BACKLOG.md](BACKLOG.md) (Flyway `connectRetries`/Hikari `initializationFailTimeout`) |
| 11 | **`kubectl top` 이 안 된다** | metrics-server 미설치 → HPA(자동 스케일)도 불가 | → [BACKLOG.md](BACKLOG.md)(**정정**: 16b·Phase 18 모두 이 항목을 다루지 않았다 — §17-#9 참고) |
| 12 | **auth-service 가 밖에서 안 보인다** | ClusterIP 라 토큰 받으려면 `port-forward` 가 필요하다 | **Phase 16b**(Ingress) |

---

## 복습 포인트 (스스로 답해보기)

<details><summary>Q1. 왜 Service 가 필요한가 — 파드 IP 를 클라이언트가 직접 들고 있으면 왜 안 되나?</summary>

파드는 재시작·재스케줄될 때마다 **IP 가 바뀐다**(§6-③). Eureka 가 없는 세계에서 클라이언트가 파드 IP 를
캐시해 두면, 파드가 죽고 새로 뜨는 순간 그 캐시는 유효하지 않다. Service 는 **고정된 이름 + ClusterIP**
를 파드 묶음 앞에 세워, 뒤의 파드가 몇 개든 몇 번을 다시 뜨든 클라이언트는 항상 같은 주소로 붙을 수 있게
한다 — kube-proxy 가 그 주소를 **살아있는(ready) 파드**로 DNAT 해 준다(§6-②).
</details>

<details><summary>Q2. liveness 와 readiness 를 섞으면(둘 다에 db 를 넣으면) 왜 위험한가?</summary>

DB 가 잠깐 끊겼다고 하자. liveness 에 db 가 들어 있으면 **모든 파드가 동시에** 재시작 루프에 빠지고,
DB 가 살아난 순간 방금 재시작한 파드 수십 개가 동시에 커넥션 풀을 채우려 들어 DB 를 다시 눕힌다(§2·§8-⑥).
반대로 readiness 만 실패시키면 파드는 살아 있고(캐시·JIT 워밍업 유지) 트래픽만 끊기며, DB 가 돌아오면
**재시작 없이** 즉시 복귀한다 — §7-⑥ 에서 실측했다.
</details>

<details><summary>Q3. startupProbe 가 없으면 어떤 문제가 생기나?</summary>

startup 이 성공하기 전까지 liveness/readiness 는 아예 평가되지 않는다. startupProbe 없이 liveness 를
짧고 민감하게 두면 JVM 이 뜨는 동안(Flyway 마이그레이션 포함) 오탐으로 재시작될 수 있고, 반대로
`initialDelaySeconds` 를 크게 잡으면 **정상 파드도** 그만큼 늦게 트래픽을 받는다(§4-③).
</details>

<details><summary>Q4. k8s 에는 왜 depends_on 이 없나 — 그게 compose 보다 나은 점은?</summary>

순서 보장은 **기동 시점에만** 유효하고 운영 중 장애에는 무력하다(§1-②). k8s 는 대신 "실패하면
물러났다 다시 하라"(exponential backoff)를 택했다 — 기동 실패든 운영 중 장애든 **같은 메커니즘**이
동작한다(§6-①). 대가로 처음 몇 번의 `CrashLoopBackOff` 는 정상 동작이라는 것을 받아들여야 한다.
</details>

<details><summary>Q5. ConfigMap 을 고쳐도 파드가 바로 반영하지 않는 이유는?</summary>

kubelet 이 마운트된 파일 내용은 최대 ~70초 안에 갱신해 주지만, **Spring 은 이미 읽은 프로퍼티를
다시 바인딩하지 않는다**(§6-④). 그래서 `rollout restart`·`/actuator/refresh`·(Phase 18 부터는)
ConfigMap 이름에 해시를 붙여 파드 템플릿 자체를 바꾸는 방법 중 하나가 필요하다.
</details>

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
- **RWO(ReadWriteOnce)** — PVC 의 accessMode 하나. 한 번에 노드 하나에서만 마운트할 수 있다.
  롤링 업데이트가 새 파드를 **먼저** 띄우면 헌 파드와 볼륨을 동시에 못 잡아 `Pending` 에 걸린다(§4-④).
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

<a id="part-16b"></a>

---
---

# 파트 16b — Eureka·Config Server 를 삭제하고 전체 플랫폼을 올린다

## 10. 16b 에서 한 일 (요약)

- **Eureka 삭제.** `discovery-service` 모듈과 6개 서비스의 `eureka-client` 의존성을 지웠다.
  `lb://order-service` → `${services.order}` (평범한 URL). 인스턴스 선택은 이제 **플랫폼**이 한다.
- **Config Server 삭제.** `config-service` 모듈과 `config-repo/` 를 지웠다.
  설정은 ① jar 안의 로컬 기본값 ② `shared/messaging` 라이브러리의 공통 메시징 설정
  ③ `deploy/config/` 파일(ConfigMap / 바인드마운트) 세 층으로 나눴다.
- **compose 도 함께 이전.** 15 → **13 컨테이너**. compose 와 k8s 가 **같은 설정 파일 한 벌**을 공유한다.
- **전체 플랫폼 on k8s** — 13 파드(앱 6 · DB 3 · Kafka · Mongo · 관측성 · Ingress 컨트롤러).
- **Ingress**(ingress-nginx) 를 앞에 세웠다. `localhost:8000` — **compose 시절과 같은 주소**라 기존 스모크가 그대로 동작한다.
- **auth-service 복제본 2** — RSA 서명 키를 Secret 으로 빼서 16a 의 한계 #4 를 해결했다.
- 무중단 롤링 업데이트 · Saga 진행 중 파드 삭제 · **Config Server 없는 설정 방송**을 실측했다.
- **★ 두 개의 진짜 결함을 만났고 둘 다 문서에 남겼다** — KRaft 부트스트랩 교착, KafkaAdmin 토픽 미생성(§16).

---

## 11. 설정을 어디에 둘 것인가 — Config Server 를 지운 자리

Config Server 를 지우면 그 자리가 빈다. "그럼 설정을 어디에?"가 16b 의 진짜 설계 문제였다.

### 세 층으로 나눴다

```
① jar 안 src/main/resources/application.yml     ← 로컬(IDE)에서 그냥 돌아가는 기본값
        localhost:5432, localhost:9000 …          "클론하고 bootRun 하면 뜬다"
        ▲ 덮어씀
② shared/messaging 의 shopsaga-messaging-defaults.yml   ← 4개 메시징 서비스의 공통 설정
        Kafka 직렬화·trusted packages·outbox·DLQ·saga    (spring.config.import: classpath:…)
        ▲ 덮어씀
③ deploy/config/{common,<service>}.yml          ← 환경(컨테이너)에서 달라지는 것만
        서비스 주소·Kafka 브로커·probe·관측성 엔드포인트·DB 호스트
        └ compose : 바인드 마운트  ┐
        └ k8s     : ConfigMap     ┘ → /application/config/{10-common,20-service}/application.yml
```

**②가 핵심 아이디어다.** 공통 설정을 "서버"가 아니라 **라이브러리**로 공유한다.

```yaml
# services/order-service/src/main/resources/application.yml
spring:
  config:
    import: "classpath:shopsaga-messaging-defaults.yml"
```

Config Server 와 비교하면:

| | Config Server (Phase 6~16a) | 라이브러리 (16b) |
|---|---|---|
| 기동 의존성 | **있다** — 그 서버가 죽으면 아무도 못 뜬다 | 없다 |
| 버전 정합성 | 서버의 파일과 앱의 코드가 따로 논다 | 라이브러리와 함께 간다 |
| 런타임 변경 | 가능(refresh) | **불가** — 재배포해야 한다 |

그래서 **런타임에 바뀔 수 있는 값**은 ②가 아니라 ③에 둔다. ③은 ConfigMap 이므로 `kubectl edit` + refresh 로 바꿀 수 있다(§15-⑤ 에서 실측).

### 왜 `/application/config/10-common/` 인가

Spring Boot 의 **기본** 설정 탐색 경로는 이렇다(뒤쪽이 이긴다):

```
classpath:/  <  classpath:/config/  <  file:./  <  file:./config/  <  file:./config/*/
```

마지막 `file:./config/*/` 는 **하위 디렉터리들**을 이름순으로 읽는다 — 정확히 ConfigMap 여러 개를 마운트하라고 만든 경로다.
컨테이너 WORKDIR 이 `/application` 이므로 `10-common` < `20-service` 순으로 읽히고 뒤가 이긴다.

### compose 와 k8s 가 같은 파일을 쓰는 이유

**compose 서비스명과 k8s Service 이름을 똑같이 지었기 때문**이다. 두 플랫폼 다 "이름 → 주소"를 DNS 로 풀어 준다.

```yaml
# deploy/config/common.yml — 이 파일 하나가 compose 와 k8s 양쪽에서 그대로 쓰인다
services:
  auth: http://auth-service:9000
  order: http://order-service:8080
  payment: http://payment-service:8081
  inventory: http://inventory-service:8082
  orderQuery: http://order-query-service:8083
spring:
  kafka:
    bootstrap-servers: kafka:19092
```

> ⚠️ **정정(요지 — 2026-08-02 감사 이후 파일이 바뀌었다, 현재 파일 참고).** 위 인용은 16b 작성 시점의 것이다.
> 감사에서 게이트웨이의 `payments-route` 를 삭제하면서(§보안: IDOR 방지 — 결제는 오직 Saga/Kafka 커맨드로만
> 구동) `deploy/config/common.yml` 의 `payment:` 항목도 함께 지워졌다. 지금 실제 파일에는 그 줄이
> `# payment 주소 없음 — 결제는 Saga(Kafka)로만 구동, 게이트웨이 라우트 제거(감사 2026-08-02)` 라는
> 주석으로 남아 있다 — [deploy/config/common.yml](../deploy/config/common.yml) 참고. `auth`·`order`·
> `inventory`·`orderQuery` 네 개는 그대로다.

이것 자체가 Phase 16 의 논지다 — **디스커버리는 애플리케이션이 아니라 플랫폼의 일**이므로,
플랫폼만 바뀌고 애플리케이션 설정은 그대로다.

---

## 12. Eureka 를 지운다는 것

### 코드에서 사라진 것

```java
// 전 (Phase 4~16a)
@Bean @LoadBalanced
RestClient.Builder loadBalancedRestClientBuilder(...) { ... }

@Bean
RestClient inventoryRestClient(@LoadBalanced RestClient.Builder b) {
    return b.baseUrl("http://inventory-service").build();   // '논리 이름'
}
```
```java
// 후 (16b)
@Bean
RestClient inventoryRestClient(RestClientBuilderConfigurer configurer,
                               @Value("${services.inventory}") String baseUrl) {
    return configurer.configure(RestClient.builder()...).baseUrl(baseUrl).build();  // 그냥 URL
}
```

게이트웨이도 같다: `uri: lb://order-service` → `uri: ${services.order}`.

**사라진 것**: 레지스트리 클라이언트 라이브러리, 30초마다의 하트비트, 인스턴스 목록 캐시,
그리고 "레지스트리가 죽으면?"이라는 걱정(Phase 4 에서 실제로 다뤘던 문제).

### 공짜는 아니다 — 무엇을 잃었나

| | Eureka (앱이 한다) | k8s Service (플랫폼이 한다) |
|---|---|---|
| 부하분산 단위 | **요청**마다 인스턴스 선택 | **커넥션**마다(iptables DNAT) |
| 인스턴스 목록 | 앱이 안다 → 커스텀 정책 가능(존 우선, 가중치) | 앱은 모른다 |
| 지연 | 로컬 캐시 → 0 | DNS + iptables → 무시할 수준 |
| 장애 전파 | 레지스트리가 SPOF 후보 | 컨트롤플레인이 죽어도 **기존 규칙은 계속 동작** |
| 이종 환경 | k8s 밖 VM 도 등록 가능 | 클러스터 안으로 한정 |

**커넥션 단위 분산**이 실무에서 가장 자주 무는 지점이다. HTTP keep-alive 로 커넥션을 오래 물고 있으면
스케일 아웃을 해도 새 파드로 트래픽이 잘 안 간다. 그래서 gRPC 처럼 커넥션을 유지하는 프로토콜은
클라이언트 사이드 LB 나 서비스 메시(Envoy)가 여전히 필요하다.

---

## 13. 구성 — 13 파드

```
                         macOS :8000                      macOS :30080
                              │                                │ (게이트웨이 우회 디버깅 경로)
  ┌── kind 노드 (shopsaga-control-plane) ─────────────────────────────────────┐
  │   :80 ─▶ ingress-nginx 컨트롤러                             :30080        │
  │              │  /grafana ─▶ otel-lgtm:3000                    │           │
  │              │  /        ─▶ gateway-service:8000              │           │
  │              ▼                                                │           │
  │        gateway-service  (엣지 JWT · 라우트별 회로차단기 · 과부하 차단)      │
  │           │      │        │            │                      │           │
  │      /auth│ /orders│ /inventory│ /order-views│                 │           │
  │           ▼      ▼        ▼            ▼                      ▼           │
  │      auth-service(×2)  order-service ─ inventory-service   order-service   │
  │            │              │  │              │                              │
  │            │       order-db  │        inventory-db                         │
  │            │                 │                                            │
  │            │            payment-service ─ payment-db                       │
  │            │                 │                                            │
  │            │            order-query-service ─ order-query-mongo            │
  │            │                 │                                            │
  │            └──── 전부 ───▶ kafka (KRaft 단일) ◀── outbox 릴레이·Saga·Bus    │
  │                              │                                            │
  │                         otel-lgtm (Tempo·Loki·Prometheus·Grafana)          │
  └───────────────────────────────────────────────────────────────────────────┘
```

**Ingress 가 있는데 게이트웨이를 왜 남기나** — 층이 다르다.

- **Ingress** = L7 라우팅·TLS 종료. 클러스터 밖에서 안으로 들어오는 문. **플랫폼 기능**.
- **Gateway** = 엣지 **애플리케이션 로직**: JWT 선검증(Phase 5) · 라우트별 회로차단기와 fallback(Phase 14) ·
  과부하 차단(Phase 14). 이건 k8s 가 대신해 주지 않는다.

즉 Ingress 는 Eureka 처럼 "앱에서 플랫폼으로 넘어간" 것이 아니라, 게이트웨이 **앞에 한 겹 더 생긴 것**이다.
(이 로직까지 플랫폼으로 넘기고 싶다면 그게 서비스 메시나 Gateway API 의 영역이다 — 이 학습 범위 밖.)

---

## 14. 코드 — 16b 의 결정적인 부분

### ① auth-service 서명 키를 Secret 으로 (16a 한계 #4 해결)

16a 까지 auth-service 는 기동할 때마다 RSA 키쌍을 새로 만들었다. 그래서 **복제본이 1이 상한**이었다 —
파드가 둘이면 A 가 발급한 토큰을 B 의 JWKS 로 검증할 수 없어 산발적 401 이 난다.

```java
@Bean
public RSAKey rsaKey(@Value("${auth.jwt.private-key:}") String privateKeyPem,
                     @Value("${auth.jwt.key-id:}") String keyId) throws Exception {
    String kid = StringUtils.hasText(keyId) ? keyId : UUID.randomUUID().toString();
    if (!StringUtils.hasText(privateKeyPem)) {
        log.warn("auth.jwt.private-key 가 비어 있다 — 임시 키쌍을 생성한다(kid={}). …", kid);
        return generateEphemeral(kid);          // 로컬 개발 편의는 유지
    }
    RSAPrivateCrtKey privateKey = readPkcs8(privateKeyPem);
    // 공개키를 따로 받지 않는다 — CRT 개인키가 modulus 와 publicExponent 를 이미 들고 있다.
    RSAPublicKey publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA")
            .generatePublic(new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
    return new RSAKey.Builder(publicKey).privateKey((RSAPrivateKey) privateKey).keyID(kid).build();
}
```

키는 `apply.sh` 가 `openssl` 로 만들어 Secret 에 넣는다 — **리포지토리에 개인키를 커밋하지 않기 위해서**다.
그리고 이미 있으면 다시 만들지 않는다(새로 만들면 발급된 토큰이 전부 무효가 된다).

> ⚠️ PKCS#1(`BEGIN RSA PRIVATE KEY`)이 아니라 **PKCS#8**(`BEGIN PRIVATE KEY`)이어야 한다.
> `openssl genpkey` 는 PKCS#8 로 낸다. 예전 `openssl genrsa` 는 PKCS#1 이라 파싱이 깨진다.

### ② health 그룹 검증을 꺼야 하는 이유

```yaml
# deploy/config/common.yml
management:
  endpoint:
    health:
      validate-group-membership: false     # ★ 이 줄이 없으면 서비스 절반이 기동 실패
      group:
        readiness: { include: readinessState,db,mongo }
        liveness:  { include: livenessState }
```

기본값(true)이면 그룹에 적은 이름이 실제로 존재하는지 검사하고, 없으면
`NoSuchHealthContributorException` 으로 **애플리케이션이 아예 뜨지 않는다**.
그런데 공통 파일 하나를 6개 서비스가 공유한다:

- order/payment/inventory → `db` 는 있지만 `mongo` 는 없다
- order-query → `mongo` 는 있지만 `db` 는 없다
- gateway/auth → 둘 다 없다

검사를 끄면 "있는 것만 포함"으로 관대하게 동작한다. (대안은 서비스별로 그룹을 따로 정의하는 것인데
그러면 6곳에 같은 블록이 중복된다 — 공통 설정을 공유하는 이득이 사라진다.)

### ③ initContainer — "순서가 정말 필요한" 한 곳

k8s 에 `depends_on` 이 없는 건 대개 옳다. 그런데 **재시도되지 않는 기동 작업**이 하나 있었다(§16-② 참고).

```yaml
      initContainers:
        - name: wait-for-kafka
          image: busybox:1.37
          command: ['sh', '-c', 'until nc -z kafka 19092; do echo "kafka 대기..."; sleep 3; done']
```

initContainer 는 **성공할 때까지 반복**되고, 끝나야 본 컨테이너가 시작된다.
"기본은 crash-and-retry, 예외적으로 initContainer" — 이 판단이 이 Phase 의 실무 감각이다.

---

## 15. 검증 — 16b 실측

> 환경: macOS 18GB / Colima 12GB·6CPU / kind v0.32.0 / k8s v1.36.1 arm64.
> compose 는 내려둔 상태. 파드 13개 + ingress-nginx 1개.

### ① 전부 Running

```
auth-service-…-h84fj            1/1  Running  restarts=0     ← 복제본 2
auth-service-…-qvzxk            1/1  Running  restarts=0
gateway-service-…               1/1  Running  restarts=0
order-service-… / order-db-…    1/1  Running  restarts=0
payment-service-… / payment-db-…        1/1  Running  restarts=0
inventory-service-… / inventory-db-…    1/1  Running  restarts=0
order-query-service-… / order-query-mongo-…  1/1  Running
kafka-…                         1/1  Running  restarts=0
otel-lgtm-…                     1/1  Running  restarts=0
ingress-nginx-controller-…      1/1  Running  (ns: ingress-nginx)
```

### ② Eureka·Config Server 가 정말 사라졌는가

```
파드 중 discovery/config     : 0 개
jar 안 eureka 클래스         : 0 개      (unzip -l order-service.jar | grep -ci eureka)
소스 트리 모듈               : auth · gateway · inventory · order-query · order · payment   (6개, discovery/config 없음)
```

### ③ Saga end-to-end — Ingress 를 통해

```
$ curl localhost:8000/actuator/health                → HTTP 200        (Ingress → gateway)
$ POST /auth/login                                   → 토큰 525자      (Ingress → gateway → auth-service)
$ POST /orders (3개)                                 → 201 PENDING
  [3s] CONFIRMED                                     ← Saga 완주

$ GET /order-views?customerId=…                      (CQRS 읽기 모델, Mongo 투영)
[{"orderId":"1fb68e2f-…","status":"CONFIRMED","totalAmount":30.00,
  "lines":[{"productId":"2222…","quantity":3,"unitPrice":10.00,"lineTotal":30.00}]}]

$ GET /inventory/22222222-…                          {"availableQuantity":94}
```

주소가 `localhost:8000` 그대로다 — **Phase 3~15 의 스모크 명령이 한 글자도 안 바뀌었다.**
그 사이에 디스커버리·설정·오케스트레이터가 전부 교체됐는데도.

### ④ auth-service 복제본 2 — 16a 한계 #4 해결

```
auth-service-…-h84fj  1/1
auth-service-…-qvzxk  1/1
8회 로그인 → 다른 서비스에서 토큰 검증:  성공 8 · 실패 0
```

16a 였다면 파드마다 키가 달라 **절반이 401** 이었을 것이다.

### ⑤ ★ Config Server 를 지웠는데 설정 방송이 살아있는가

Phase 15 의 Spring Cloud Bus 가 Config Server 없이도 쓸모 있는지 — 이게 16b 의 가장 궁금한 지점이었다.

```
① deploy/config/order-service.yml 의 reject-on-insufficient: false
   → POST /orders(99999개)                              HTTP 201

② ConfigMap 만 true 로 갱신(파드 재시작 없음)
   파드 안 마운트 파일 확인:  reject-on-insufficient: true     ← kubelet 이 파일을 갱신함
   그래도 아직                                          HTTP 201   ← Spring 은 아직 안 읽었다

③ inventory-service '한 곳'에만 busrefresh
   (order-service 는 건드리지 않았다)

④ POST /orders(99999개)                                 HTTP 409   ✅
   order-service 재시작 횟수:  0 0                       ← 재시작 없이 바뀌었다
```

**방송의 의미가 바뀌었다.** 전에는 "Config Server 를 다시 읽어라"였고, 이제는 "마운트된 ConfigMap 파일을 다시 읽어라"다.
메커니즘(Kafka 로 `RefreshRemoteApplicationEvent` 방송 → `@ConfigurationProperties` 재바인딩)은 그대로다.

> ⚠️ 여기서 **두 단계의 지연**이 있다는 걸 봐야 한다.
> ⓐ ConfigMap 을 바꿔도 파드 안 파일이 갱신되기까지 **최대 ~70초**(kubelet 동기화 주기 + 캐시).
> ⓑ 파일이 갱신돼도 Spring 은 안 읽는다 → refresh 가 필요하다.
> ⓐ 때문에 "바꿨는데 왜 안 바뀌지?"로 헷갈리기 쉽다. 급하면 `kubectl rollout restart` 가 확실하다.

### ⑥ 무중단 롤링 업데이트

복제본 2에서 `kubectl rollout restart` 하는 동안 0.5초 간격으로 90회 요청:

```
요청 90회 응답코드 분포:
   90 404          ← 없는 주문 id 에 대한 정상 응답
(502·503·000 = 0건)
```

**다운타임 0.** 새 파드가 Ready 가 된 뒤에야 헌 파드가 Endpoints 에서 빠지기 때문이다
(RollingUpdate 기본값 `maxUnavailable=25%`, `maxSurge=25%` + readinessProbe).

### ⑦ Saga 진행 중 파드 삭제

주문 5건을 넣자마자 order-service 파드 하나를 삭제:

```
주문 최종 상태:  CONFIRMED ×5        ← 전부 완주
파드:            2개 Running, restarts=0   (삭제된 것은 새 파드로 대체)
Saga 인스턴스:   COMPLETED ×6, CANCELLED ×6
읽기 모델:       10건 투영됨
```

Saga 상태가 **DB 에** 있고(Phase 13) 이벤트가 **outbox 에** 있으므로(Phase 10),
조정자 파드가 통째로 사라져도 새 파드가 이어받는다. **k8s 가 아니라 우리가 Phase 10·13 에서 만든 성질이다** —
k8s 는 파드를 다시 만들어 줄 뿐, 상태를 지켜주지는 않는다.

### ⑧ 관측성

```
$ GET localhost:8000/grafana/api/health   → HTTP 200  {"database":"ok","version":"13.1.0"}
$ Tempo 에 order-service 트레이스          → 3 건
```

### ⑨ 자원

```
$ colima ssh -- free -h
               total   used   free   available
Mem:            11Gi   4.4Gi  330Mi      7.2Gi     ← 13 파드 + 컨트롤플레인 + ingress
```

### ⑩ ★ compose 도 여전히 동작하는가 — 설정 파일 한 벌의 값어치

Eureka·Config Server 를 지웠으니 compose 스택도 함께 이전해야 했다(사용자와 합의한 방향).
**같은 `deploy/config/` 파일**로 compose 를 띄워 확인했다.

```
$ docker compose --profile async up -d --build
컨테이너 13개 (15 → 13: discovery-service·config-service 삭제)
전부 healthy

$ docker exec shopsaga-order-service-1 ls /application/config/
10-common   20-service                       ← k8s 와 똑같은 마운트 구조
$ … grep 'order-db\|auth-service' /application/config/*/application.yml
  auth: http://auth-service:9000               ← common.yml (compose 서비스명 = k8s Service 이름)
  url: jdbc:postgresql://order-db:5432/orderdb ← order-service.yml

$ POST /auth/login          → 토큰 550자
$ POST /orders (2개)        → 201 PENDING
  [3s] CONFIRMED                               ← Saga 완주
$ GET /order-views?…        → 44건 (이전 Phase 볼륨 데이터 누적)
$ GET /inventory/2222…      → {"availableQuantity":66}
$ GET localhost:3000/api/health → 200          (Grafana)
```

**ENCRYPT_KEY 도 `.env` 도 필요 없어졌다** — 복호화할 `{cipher}` 가 없으므로.
설정 파일 한 벌이 두 플랫폼을 모두 돌린다는 것이 여기서 증명된다.

> ⚠️ 검증 중에 **"동시 금지" 규칙이 실제로 물었다.** compose 를 올리려니:
> ```
> Bind for 0.0.0.0:8000 failed: port is already allocated
> ```
> kind 의 Ingress 매핑(노드:80 → 호스트:8000)이 이미 8000 을 쥐고 있었다.
> RAM 뿐 아니라 **포트도 충돌**한다. `docker stop shopsaga-control-plane` 으로 kind 를 잠시 재운 뒤 진행했고,
> 검증 후 `docker start` 로 되살렸다 — 클러스터는 파드 13개를 그대로 재생성하며 복구됐다(restarts=0).
>
> ⚠️ 그리고 하나 더: 포트 충돌로 **생성에 실패한 컨테이너를 `up -d` 가 그냥 start 해 버린다.**
> 컨테이너는 `running healthy` 인데 `docker port` 가 비어 있어 밖에서 닿지 않는다(내부 healthcheck 는 통과하므로).
> `--force-recreate` 로 다시 만들어야 포트 매핑이 프로그래밍된다.

### ⑪ 회귀 테스트

`./gradlew build` — 전부 통과. 게이트웨이 라우트 테스트에는 **회귀 가드**를 추가했다:

```java
// 디스커버리가 플랫폼으로 넘어갔으므로 라우트 uri 는 평범한 http URL 이어야 한다.
// 누군가 lb:// 를 되살리면(= 앱이 다시 인스턴스를 고르려 들면) 여기서 깨진다.
assertThat(routes).allSatisfy(r ->
        assertThat(r.getUri().getScheme()).as("route %s", r.getId()).isEqualTo("http"));
```

---

## 16. 겪은 결함 — 감추지 않고 남긴다

### ① KRaft 부트스트랩 교착 — "Ready 여야 Ready 가 된다"

compose 설정을 그대로 옮겼더니 Kafka 가 뜨지 않고 재시작을 반복했다.

```
WARN [NodeToControllerChannelManager id=1 name=heartbeat]
     Connection to node 1 (kafka/10.96.221.146:29093) could not be established.
ERROR [ControllerRegistrationManager id=1 …] channel manager timed out before sending the request.
```

**원인.** `KAFKA_CONTROLLER_QUORUM_VOTERS=1@kafka:29093` — 브로커가 기동 중에
**자기 자신(컨트롤러)에게** 등록 요청을 보내는데, 그 주소가 Service 이름이라 ClusterIP 로 풀린다.
그런데 kube-proxy 는 **Ready 인 파드에게만** 트래픽을 보낸다. 이 파드는 "브로커가 떠야" Ready 가 되므로:

```
브로커가 뜨려면 → 컨트롤러에 등록해야 → Service 를 거쳐야 → 파드가 Ready 여야 → 브로커가 떠야 …
```

compose 에는 이 문제가 없다. compose 네트워크의 DNS 는 "준비 상태"를 따지지 않고 그냥 컨테이너 IP 를 준다.
**Service 가 Endpoints 를 준비 상태로 걸러낸다**는 k8s 고유의 성질이 만든 교착이다.

**해결.** 단일 노드 combined 모드에서는 컨트롤러가 곧 자기 자신이므로 Service 를 경유할 이유가 없다:

```yaml
- { name: KAFKA_CONTROLLER_QUORUM_VOTERS, value: "1@localhost:29093" }
```

(다중 브로커라면 StatefulSet + headless Service + `publishNotReadyAddresses: true` 로 푼다.)

### ② KafkaAdmin 은 토픽을 딱 한 번만 만든다 — 초록불인데 Saga 가 멈췄다

Kafka 를 고친 뒤 파드는 **13개 전부 `1/1 Running`** 이었는데 주문이 `PENDING` 에서 안 넘어갔다.

```
WARN  … The metadata response … reported a recoverable issue …
      {order-events=UNKNOWN_TOPIC_OR_PARTITION, saga-commands=UNKNOWN_TOPIC_OR_PARTITION}
ERROR … TimeoutException: Topic order-events not present in metadata after 5000 ms.
WARN  com.shopsaga.outbox.OutboxRelay : Outbox 발행 실패 … attempts=4/5

$ kafka-topics.sh --list
__consumer_offsets  springCloudBus          ← 도메인 토픽이 하나도 없다
```

**원인.** Spring 의 `KafkaAdmin` 은 `@Bean NewTopic` 들을 **기동 시 딱 한 번** 만든다.
앱이 Kafka 보다 먼저 떴고(k8s 에는 `depends_on` 이 없다) 그 한 번의 시도가 실패했으며, **다시 시도하지 않았다.**
`auto.create.topics.enable=false`(Phase 9 의 의도적 선택)라 브로커가 대신 만들어 주지도 않는다.

무서운 점은 **아무것도 빨간불이 아니었다는 것**이다. 파드는 Running·Ready 이고 probe 도 통과한다.
readiness 그룹에 Kafka 를 넣지 않은 우리 선택(§11)이 여기서는 증상을 감췄다 —
그러나 그 선택 자체는 여전히 옳다(브로커가 없어도 주문 접수는 성공해야 하므로).

**해결.** 이 한 곳에만 initContainer 를 뒀다(§14-③). "기본은 crash-and-retry, 예외적으로 순서 강제".

**남은 상처도 정직하게.** 이 사고로 outbox row **8건이 `attempts=5` 로 격리**됐다(Phase 14 의 장치).

```
attempts=5 topic=order-events   type=OrderPlacedEvent        created=11:51   ← 16a 시절(Kafka 자체가 없었음)
attempts=5 topic=saga-commands  type=ReserveStockCommand     created=11:52   ← sweeper 재촉 1
attempts=5 topic=saga-commands  type=ReserveStockCommand     created=12:55   ← 토픽 미생성 구간
…
```

이 이벤트들은 **실제로 유실됐다**(격리 = "건너뛰고 사람에게 알린다", 무한 재시도가 아니다).
그런데 **멈춘 Saga 는 하나도 없다**:

```
주문 상태 분포:  CONFIRMED ×6, CANCELLED ×6      (PENDING 0)
격리된 8건에 대응하는 주문:  전부 CANCELLED
```

Phase 13 의 `SagaTimeoutSweeper` 가 15초 무응답 → 재촉 3회 → 포기 → 취소로 정리한 것이다.
**Phase 13·14 에서 만든 안전장치가 Phase 16 의 플랫폼 사고를 받아냈다.** 이게 그 장치들을 만든 이유다.

### ③ 테스트가 실제 설정을 검증하지 않고 있었다

`GatewayRoutesTest` 가 깨졌는데, 원인은 **테스트 전용 `src/test/resources/application.yml`** 이었다.
Phase 6 에서 라우트가 config-repo 로 옮겨갔을 때 "테스트가 Config Server 없이 돌게" 만든 사본인데,
그 뒤로 **진짜 설정을 가리고 자기 사본을 검증**하고 있었다(라우트 4개짜리 옛 복사본 — `order-views-route` 조차 없었다).

16b 에서 라우트가 본 `application.yml` 로 돌아왔으므로 그 사본을 삭제했다.
이제 이 테스트는 **실제로 배포되는 설정**을 검증한다.

> 교훈: "테스트를 돌리기 위한 설정 사본"은 시간이 지나면 **테스트를 무의미하게 만드는** 쪽으로 썩는다.
> 사본을 둘 수밖에 없다면 원본과 어긋났을 때 깨지는 장치를 함께 둬야 한다.

---

## 17. Phase 16 전체의 한계 → 어디서 해결되나

| # | 한계 | 왜 문제인가 | 해결 |
|---|---|---|---|
| 1 | **`kubectl apply -f` + 셸 스크립트로 배포** | 환경별 차이(dev/stage/prod)를 표현할 방법이 없다. `apply.sh` 가 명령형이라 선언성이 깨진다 | Phase 18 (Helm/Kustomize) |
| 2 | **이미지 배포가 수동**(`kind load`) | 어느 커밋이 떠 있는지 추적 불가. 롤백도 수동 | **Phase 17** (CI/CD + 레지스트리) |
| 3 | **Secret 이 암호화가 아니다** | base64 일 뿐이고 etcd 에도 평문. dev 비밀번호가 리포지토리에 있다 | → [BACKLOG.md](BACKLOG.md)(**정정**: Phase 18 은 이 항목을 다루지 않았다) |
| 4 | **컨테이너가 root 로 돈다** | `runAsNonRoot`·`readOnlyRootFilesystem`·`seccompProfile` 없음 | → [BACKLOG.md](BACKLOG.md) |
| 5 | **NetworkPolicy 가 없다** | 모든 파드가 모든 파드에 접근 가능. DB 도 클러스터 안에서 무방비 | → [BACKLOG.md](BACKLOG.md) |
| 6 | **DB·Kafka 가 Deployment** | StatefulSet 이 정석(안정적 신원·순서·파드별 볼륨). 지금은 `Recreate` 로 우회 | 학습 범위 밖 |
| 7 | **PVC 가 `local-path`** | 노드 로컬 디렉터리. 노드가 죽으면 데이터도 죽는다 | 학습 범위 밖(운영은 CSI) |
| 8 | **단일 노드** | 진짜 스케줄링·안티어피니티·노드 장애를 볼 수 없다 | 학습 범위 밖 |
| 9 | **`kubectl top`·HPA 불가** | metrics-server 미설치 → 자동 스케일을 실습할 수 없다 | → [BACKLOG.md](BACKLOG.md) |
| 10 | **ConfigMap 반영이 최대 ~70초** | "바꿨는데 왜 안 바뀌지"의 흔한 원인 | 구조적 특성(rollout restart 로 우회) |
| 11 | **Kafka 단일 브로커·replication 1** | 브로커가 죽으면 이벤트 유실. ISR·리더 선출을 볼 수 없다 | 학습 범위 밖 |
| 12 | **격리된 outbox 8건이 남아 있다** | 실제 유실분. 재처리(replay) 도구가 없다 | → [BACKLOG.md](BACKLOG.md)(재처리 도구 — Phase 18 은 만들지 않았다) |
| 13 | **관측성 데이터가 휘발** | otel-lgtm 에 PVC 없음 → 파드 재생성 시 트레이스 소실 | 의도적(학습용) |

---

## 18. 용어 (16b 추가분)

- **Ingress / Ingress 컨트롤러** — 전자는 "HTTP 라우팅 규칙" 오브젝트, 후자는 그 규칙을 실제로 수행하는 파드.
  **컨트롤러 없이 Ingress 만 만들면 아무 일도 일어나지 않는다.**
- **initContainer** — 본 컨테이너보다 먼저, 성공할 때까지 실행되는 컨테이너. 순서가 꼭 필요할 때의 탈출구.
- **Downward API** — 파드가 자기 메타데이터(이름·노드·라벨)를 환경변수/파일로 받는 방법.
- **KRaft** — Kafka 4.x 의 ZooKeeper 없는 합의 모드. 브로커가 컨트롤러 역할을 겸할 수 있다(combined).
- **maxSurge / maxUnavailable** — RollingUpdate 시 "추가로 더 띄울 수 있는 수 / 동시에 없어도 되는 수".
  둘의 조합이 무중단 여부를 결정한다.
- **headless Service** — `clusterIP: None`. 로드밸런싱 없이 파드 IP 를 그대로 DNS 로 노출한다.
  StatefulSet 의 안정적 신원과 짝을 이룬다.

---

## 19. 참고 (16b)

- 코드: `deploy/k8s/` · `deploy/config/` · [k8s README](../deploy/k8s/README.md)
- 삭제된 것: `services/discovery-service`(Phase 4) · `services/config-service`·`config-repo/`(Phase 6)
  → 두 Phase 의 문서는 **"그때는 왜 필요했나"** 의 기록으로 남는다:
  [Phase 4](SERVICE-DISCOVERY.md) · [Phase 6](PHASE-6-CONFIG.md)
- 공식 문서:
  - [Kubernetes — Ingress](https://kubernetes.io/docs/concepts/services-networking/ingress/) ·
    [Init Containers](https://kubernetes.io/docs/concepts/workloads/pods/init-containers/)
  - [Spring Boot — Externalized Configuration (우선순위·`config/*/`)](https://docs.spring.io/spring-boot/reference/features/external-config.html)
  - [Spring Boot — Health Groups](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.health.groups)
  - [Apache Kafka — KRaft Configuration](https://kafka.apache.org/documentation/#kraft_config)
