package com.shopsaga.gateway.resilience;

import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRateLimiterMetrics;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Phase 14: 엣지 RateLimiter/Bulkhead 레지스트리와 메트릭 바인딩.
 *
 * <p>게이트웨이는 {@code spring-cloud-circuitbreaker} 를 쓰므로 Resilience4j <b>스타터</b>를 넣지 않는다
 * (자동설정이 겹쳐 회로차단기 설정이 두 군데서 관리되는 혼란을 피하기 위해서다).
 * 그래서 레지스트리를 여기서 직접 만들고, 메트릭도 직접 붙인다.
 *
 * <p>기본값은 <b>일부러 낮게</b> 잡았다(초당 20건 / 동시 20건). 학습용으로 {@code hey} 같은 도구 없이
 * 반복 curl 만으로도 429·503 을 만들어 볼 수 있어야 하기 때문이다.
 */
@Configuration
public class EdgeThrottleConfig {

    @Bean
    RateLimiterRegistry rateLimiterRegistry(
            @Value("${gateway.resilience.rate-limit-per-second:20}") int limitForPeriod,
            @Value("${gateway.resilience.rate-limit-timeout-ms:0}") long timeoutMillis) {
        return RateLimiterRegistry.of(RateLimiterConfig.custom()
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .limitForPeriod(limitForPeriod)
                // 0 = 대기하지 않고 즉시 거절. 대기시키면 큐가 쌓여 지연이 늘어난다(과부하 차단의 목적과 반대).
                .timeoutDuration(Duration.ofMillis(timeoutMillis))
                .build());
    }

    @Bean
    BulkheadRegistry bulkheadRegistry(
            @Value("${gateway.resilience.max-concurrent-calls:20}") int maxConcurrentCalls) {
        return BulkheadRegistry.of(BulkheadConfig.custom()
                .maxConcurrentCalls(maxConcurrentCalls)
                .maxWaitDuration(Duration.ZERO)   // 자리가 없으면 즉시 503 — 기다리게 하면 격리 효과가 사라진다.
                .build());
    }

    /** Grafana 에서 {@code resilience4j_ratelimiter_available_permissions} 등으로 관찰. */
    @Bean
    MeterBinder rateLimiterMetrics(RateLimiterRegistry registry) {
        return TaggedRateLimiterMetrics.ofRateLimiterRegistry(registry);
    }

    /** {@code resilience4j_bulkhead_available_concurrent_calls}. */
    @Bean
    MeterBinder bulkheadMetrics(BulkheadRegistry registry) {
        return TaggedBulkheadMetrics.ofBulkheadRegistry(registry);
    }
}
