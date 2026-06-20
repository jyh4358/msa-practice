package com.shopsaga.order.adapter.in.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 메타데이터. 문서화는 인바운드 웹 어댑터의 관심사 — 도메인/애플리케이션은 건드리지 않는다.
 * Swagger UI: /swagger-ui/index.html · OpenAPI JSON: /v3/api-docs
 */
@Configuration
class OpenApiConfig {

    @Bean
    OpenAPI orderServiceOpenApi() {
        return new OpenAPI().info(new Info()
                .title("ShopSaga order-service API")
                .description("Phase 1 모놀리스 — 주문 생성 + 재고 차감 + 결제 캡처가 하나의 트랜잭션(ACID). 헥사고날 구조.")
                .version("v1"));
    }
}
