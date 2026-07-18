package com.shopsaga.order.adapter.out.payment;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * payment-service 호출용 RestClient 설정(아웃바운드 어댑터).
 *
 * <p>Phase 4: baseUrl 이 하드코딩 host:port 가 아니라 <b>서비스 이름</b>(http://payment-service)이다.
 * {@code @LoadBalanced} 가 붙은 빌더로 만든 RestClient 는 호스트(payment-service)를 Eureka 레지스트리에서
 * 실제 인스턴스로 해석하고 클라이언트 사이드 로드밸런싱한다.
 * (게이트웨이 라우트는 {@code lb://} 스킴을 쓰지만, @LoadBalanced RestClient 는 {@code http://} 스킴을 쓴다 — 주의.)
 */
@Configuration
class PaymentClientConfig {

    // 이 빈을 선언하면 Boot 의 기본 RestClient.Builder(@ConditionalOnMissingBean)는 물러나고,
    // 로드밸런싱이 적용된 이 빌더만 남는다.
    @Bean
    @LoadBalanced
    RestClient.Builder loadBalancedRestClientBuilder(ObservationRegistry observationRegistry) {
        // Phase 5: 인바운드 JWT를 payment-service 호출로 전파(인터셉터는 build() 전에 등록해야 함).
        // Phase 8: ObservationRegistry 를 주입해야 클라이언트 스팬이 생기고 traceparent 가 payment 로 전파된다.
        //          (커스텀 @LoadBalanced 빌더를 선언하면 Boot 자동구성 빌더가 물러나며 관측 계측도 함께 빠지므로 직접 설정.)
        return RestClient.builder()
                .observationRegistry(observationRegistry)
                .requestInterceptor(new BearerTokenRelayInterceptor());
    }

    @Bean
    RestClient paymentRestClient(RestClient.Builder loadBalancedRestClientBuilder,
                                 @Value("${payment.service.url}") String baseUrl) {
        return loadBalancedRestClientBuilder.baseUrl(baseUrl).build();
    }
}
