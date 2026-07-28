package com.shopsaga.gateway.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Phase 14: 게이트웨이(엣지)의 회로차단기 설정.
 *
 * <h2>왜 엣지에 회로차단기를 두는가</h2>
 * 다운스트림이 죽으면 모든 요청이 <b>타임아웃까지 기다렸다가</b> 실패한다. 요청은 계속 쌓이고,
 * 게이트웨이의 커넥션/스레드가 고갈되면 <b>멀쩡한 다른 라우트까지 함께 죽는다</b>(캐스케이드 장애).
 * 회로가 열리면 즉시 fallback 으로 빠지므로 자원이 묶이지 않고, 죽은 상대를 더 때리지도 않는다.
 *
 * <h2>설정을 자바로 쓴 이유</h2>
 * {@code spring-cloud-circuitbreaker} 는 기본 TimeLimiter 가 <b>1초</b>다. 이 값을 모르고 두면
 * 조금만 느린 정상 요청도 잘려 나가 "이유를 알 수 없는 간헐적 503"이 된다.
 * 기본값에 의존하지 않고 <b>보이는 곳에 명시</b>한다.
 *
 * <p>{@code slidingWindow(10, 5, COUNT_BASED)}: 최근 10건 중 5건 이상 모였을 때부터 판단하고,
 * 실패율 50% 이상이면 OPEN. 10초 뒤 HALF_OPEN 으로 <b>스스로</b> 3건을 시험 호출해 회복을 확인한다
 * (사람이 개입하지 않아도 되살아나는 것이 핵심).
 */
@Configuration
public class EdgeCircuitBreakerConfig {

    @Bean
    Customizer<ReactiveResilience4JCircuitBreakerFactory> defaultCircuitBreakerCustomizer() {
        return factory -> factory.configureDefault(id -> new Resilience4JConfigBuilder(id)
                .circuitBreakerConfig(CircuitBreakerConfig.custom()
                        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                        .slidingWindowSize(10)
                        .minimumNumberOfCalls(5)
                        .failureRateThreshold(50)
                        .waitDurationInOpenState(Duration.ofSeconds(10))
                        .permittedNumberOfCallsInHalfOpenState(3)
                        .build())
                .timeLimiterConfig(TimeLimiterConfig.custom()
                        .timeoutDuration(Duration.ofSeconds(3))   // 기본 1초는 주문 쓰기에 너무 빡빡하다.
                        .build())
                .build());
    }
}
