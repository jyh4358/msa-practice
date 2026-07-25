package com.shopsaga.orderquery.adapter.in.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 메타데이터. Swagger UI: /swagger-ui/index.html · OpenAPI JSON: /v3/api-docs
 */
@Configuration
class OpenApiConfig {

    @Bean
    OpenAPI orderQueryServiceOpenApi() {
        return new OpenAPI().info(new Info()
                .title("ShopSaga order-query-service API")
                .description("Phase 11 CQRS 읽기 모델 — OrderPlaced 이벤트를 MongoDB에 투영해 조회 전용으로 제공. 쓰기 엔드포인트 없음.")
                .version("v1"));
    }
}
