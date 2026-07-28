package com.shopsaga.order.adapter.out.rest;

import org.springframework.boot.autoconfigure.web.client.RestClientBuilderConfigurer;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.concurrent.Executor;

/**
 * Phase 14: 재고 사전 확인용 동기 HTTP 클라이언트 설정.
 *
 * <p>세 가지가 의도적으로 들어 있다.
 * <ol>
 *   <li><b>{@code @LoadBalanced}</b> — {@code http://inventory-service} 라는 <b>논리 이름</b>으로 호출하고
 *       Eureka + spring-cloud-loadbalancer 가 실제 인스턴스로 해석한다(Phase 4의 장치 재사용).</li>
 *   <li><b>{@link RestClientBuilderConfigurer}</b> — Boot 의 기본 커스터마이저(특히 <b>관측 계측</b>)를 그대로 입힌다.
 *       ⚠️ {@code RestClient.builder()} 를 맨손으로 만들면 트레이스가 끊긴다(Phase 8에서 겪은 함정).</li>
 *   <li><b>낮은 소켓 타임아웃</b> — Resilience4j 의 {@code TimeLimiter} 와 <b>이중 방어</b>다.
 *       스레드를 오래 붙잡지 않도록 전송 계층에서도 상한을 둔다.</li>
 * </ol>
 */
@Configuration
class InventoryRestConfig {

    @Bean
    @LoadBalanced
    RestClient.Builder loadBalancedRestClientBuilder(RestClientBuilderConfigurer configurer) {
        return configurer.configure(RestClient.builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.detect()
                        .build(ClientHttpRequestFactorySettings.defaults()
                                .withConnectTimeout(Duration.ofSeconds(1))
                                .withReadTimeout(Duration.ofSeconds(2)))));
    }

    @Bean
    RestClient inventoryRestClient(@LoadBalanced RestClient.Builder builder) {
        return builder.baseUrl("http://inventory-service").build();
    }

    /**
     * 사전 확인 전용 스레드 풀. {@code @TimeLimiter} 가 {@code CompletableFuture} 를 요구하므로
     * 호출을 별도 스레드에서 돌린다.
     *
     * <p>⚠️ {@link ContextPropagatingTaskDecorator} 가 없으면 <b>트레이스 컨텍스트가 그 경계에서 끊긴다</b>
     * (Phase 12의 outbox 릴레이에서 겪은 것과 같은 문제 — 스레드가 바뀌면 컨텍스트는 따라가지 않는다).
     *
     * <p>풀 크기를 작게 잡은 것도 의도적이다: 부가 기능이 주문 처리용 스레드를 잠식하면 안 된다
     * (스레드 격리 = Bulkhead 와 같은 문제의식, 다만 이건 인프라 레벨 격리).
     */
    @Bean
    Executor stockPrecheckExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(16);
        executor.setThreadNamePrefix("stock-precheck-");
        executor.setTaskDecorator(new ContextPropagatingTaskDecorator());
        executor.initialize();
        return executor;
    }
}
