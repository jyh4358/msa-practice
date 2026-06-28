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
                .contains("orders-route", "inventory-route", "payments-route");
    }
}
