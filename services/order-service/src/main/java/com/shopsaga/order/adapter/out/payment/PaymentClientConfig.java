package com.shopsaga.order.adapter.out.payment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** payment-service 호출용 RestClient 설정(아웃바운드 어댑터). baseUrl 은 설정값(Phase 4에서 디스커버리로 대체). */
@Configuration
class PaymentClientConfig {

    @Bean
    RestClient paymentRestClient(RestClient.Builder builder,
                                 @Value("${payment.service.url}") String baseUrl) {
        return builder.baseUrl(baseUrl).build();
    }
}
