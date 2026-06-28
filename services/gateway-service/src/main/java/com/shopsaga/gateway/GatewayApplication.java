package com.shopsaga.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * API Gateway (Phase 3) — 외부 클라이언트의 단일 진입점.
 *
 * <p>라우팅 규칙은 application.yml(spring.cloud.gateway.server.webflux.routes)에 선언적으로 둔다.
 * 게이트웨이는 north-south(클라이언트→서비스) 트래픽만 담당하며,
 * order→payment 같은 east-west(서비스↔서비스) 호출은 게이트웨이를 거치지 않는다.
 */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
