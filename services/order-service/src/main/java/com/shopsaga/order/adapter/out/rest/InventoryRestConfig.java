package com.shopsaga.order.adapter.out.rest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.web.client.RestClientBuilderConfigurer;
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
 * <p><b>Phase 16b 에서 바뀐 것 — {@code @LoadBalanced} 를 걷어냈다.</b>
 * 전에는 {@code http://inventory-service} 라는 <b>논리 이름</b>으로 호출하면
 * Eureka + spring-cloud-loadbalancer 가 레지스트리에서 인스턴스 목록을 받아 직접 하나를 골랐다.
 * 즉 <b>디스커버리와 부하분산이 이 애플리케이션의 코드 안에</b> 있었다.
 *
 * <p>이제는 평범한 URL({@code services.inventory}) 이고, 그 이름을 <b>플랫폼</b>이 푼다 —
 * compose 네트워크의 DNS, 또는 k8s 의 Service + kube-proxy. 애플리케이션에서 사라진 것:
 * 레지스트리 클라이언트 라이브러리, 하트비트, 인스턴스 캐시, 그리고 "레지스트리가 죽으면?"이라는 걱정.
 * (대신 부하분산이 커넥션 단위로 일어난다 — 트레이드오프는 docs/PHASE-16-KUBERNETES.md §6 참고.)
 *
 * <p>나머지 둘은 그대로 유지한다.
 * <ol>
 *   <li><b>{@link RestClientBuilderConfigurer}</b> — Boot 의 기본 커스터마이저(특히 <b>관측 계측</b>)를 그대로 입힌다.
 *       ⚠️ {@code RestClient.builder()} 를 맨손으로 만들면 트레이스가 끊긴다(Phase 8에서 겪은 함정).</li>
 *   <li><b>낮은 소켓 타임아웃</b> — Resilience4j 의 {@code TimeLimiter} 와 <b>이중 방어</b>다.
 *       스레드를 오래 붙잡지 않도록 전송 계층에서도 상한을 둔다.</li>
 * </ol>
 */
@Configuration
class InventoryRestConfig {

    @Bean
    RestClient inventoryRestClient(RestClientBuilderConfigurer configurer,
                                   @Value("${services.inventory}") String inventoryBaseUrl) {
        return configurer.configure(RestClient.builder()
                        .requestFactory(ClientHttpRequestFactoryBuilder.detect()
                                .build(ClientHttpRequestFactorySettings.defaults()
                                        .withConnectTimeout(Duration.ofSeconds(1))
                                        .withReadTimeout(Duration.ofSeconds(2)))))
                .baseUrl(inventoryBaseUrl)
                .build();
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
