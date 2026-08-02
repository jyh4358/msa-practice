package com.shopsaga.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * application.yml 의 라우트 정의가 실제로 로딩되는지 검증.
 *
 * <p>이 테스트는 yaml 접두사가 올바른지(2025.0.x = spring.cloud.gateway.server.webflux.routes)를
 * 사실상 보증한다 — 접두사가 틀리면 RouteDefinition 이 0개가 되어 단언이 깨진다.
 * 게이트웨이는 DB를 쓰지 않으므로 Docker/Postgres 없이 컨텍스트가 뜬다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// Phase 16b: Eureka 가 사라져 비활성화 프로퍼티가 필요 없어졌다 — 라우트는 그냥 URL 이다.
class GatewayRoutesTest {

    @Autowired
    RouteDefinitionLocator routeDefinitionLocator;

    @Test
    void configuredRoutesAreLoaded() {
        List<RouteDefinition> routes = routeDefinitionLocator.getRouteDefinitions()
                .collectList()
                .block();

        assertThat(routes)
                .extracting(RouteDefinition::getId)
                .contains("auth-route", "orders-route", "inventory-route", "order-views-route")
                // ★ 감사(2026-08-02) 회귀 가드: 결제는 Saga(Kafka)로만 구동된다 — 외부 라우트가
                //   되살아나면 Saga·멱등성·보상을 우회하는 뒷문이 다시 열린다.
                .doesNotContain("payments-route");

        // Phase 16b 회귀 가드: 디스커버리가 플랫폼으로 넘어갔으므로 라우트 uri 는 평범한 http URL 이어야 한다.
        // 누군가 lb:// 를 되살리면(= 앱이 다시 인스턴스를 고르려 들면) 여기서 깨진다.
        assertThat(routes)
                .allSatisfy(r -> assertThat(r.getUri().getScheme())
                        .as("route %s", r.getId())
                        .isEqualTo("http"));
    }
}
