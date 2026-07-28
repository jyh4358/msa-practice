package com.shopsaga.gateway.resilience;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator;
import io.github.resilience4j.reactor.ratelimiter.operator.RateLimiterOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * Phase 14: 엣지의 <b>과부하 차단</b> — RateLimiter(속도)와 Bulkhead(동시성)를 글로벌 필터로 얹는다.
 *
 * <h2>왜 게이트웨이 필터로 직접 만들었나</h2>
 * Spring Cloud Gateway 기본 {@code RequestRateLimiter} 필터는 <b>Redis 를 요구</b>한다.
 * 이 학습 플랫폼에 Redis 를 들이는 것보다, Resilience4j 의 리액터 연산자를 직접 붙이는 편이
 * "이 패턴이 실제로 무엇을 하는지"가 훨씬 잘 보인다.
 * (⚠️ 대신 <b>인스턴스별 로컬 카운터</b>다 — 게이트웨이를 2개 띄우면 한도도 2배가 된다.
 *  분산 한도가 필요하면 Redis 기반이 맞다.)
 *
 * <h2>둘의 차이</h2>
 * <ul>
 *   <li><b>RateLimiter</b>: "초당 몇 건" — 시간당 유입량을 자른다. 초과 → 429.</li>
 *   <li><b>Bulkhead</b>: "동시에 몇 건" — 처리 중인 수를 자른다. 응답이 느려질수록 먼저 걸린다. 초과 → 503.</li>
 * </ul>
 * 유입은 일정한데 다운스트림이 느려지는 상황은 RateLimiter 로는 못 막는다 — 그래서 둘 다 필요하다.
 *
 * <p>순서: RateLimiter 가 바깥(먼저 거른다) → Bulkhead. 회로차단기는 라우트 필터라 이보다 안쪽에서 돈다.
 */
@Component
class EdgeThrottleFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(EdgeThrottleFilter.class);

    static final String INSTANCE = "edge";

    private final RateLimiter rateLimiter;
    private final Bulkhead bulkhead;
    private final String protectedPrefix;

    EdgeThrottleFilter(RateLimiterRegistry rateLimiterRegistry, BulkheadRegistry bulkheadRegistry,
                       @Value("${gateway.resilience.protected-path-prefix:/orders}") String protectedPrefix) {
        this.rateLimiter = rateLimiterRegistry.rateLimiter(INSTANCE);
        this.bulkhead = bulkheadRegistry.bulkhead(INSTANCE);
        this.protectedPrefix = protectedPrefix;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!path.startsWith(protectedPrefix)) {
            return chain.filter(exchange);   // 보호 대상이 아니면 그대로 통과(로그인·조회까지 막지 않는다).
        }
        return chain.filter(exchange)
                .transformDeferred(BulkheadOperator.of(bulkhead))
                .transformDeferred(RateLimiterOperator.of(rateLimiter))
                .onErrorResume(RequestNotPermitted.class,
                        e -> reject(exchange, HttpStatus.TOO_MANY_REQUESTS, "요청 속도 한도 초과(RateLimiter)"))
                .onErrorResume(BulkheadFullException.class,
                        e -> reject(exchange, HttpStatus.SERVICE_UNAVAILABLE, "동시 처리 한도 초과(Bulkhead)"));
    }

    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String reason) {
        log.warn("엣지에서 차단 status={} path={} 사유={}",
                status.value(), exchange.getRequest().getPath().value(), reason);
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = ("{\"status\":" + status.value() + ",\"detail\":\"" + reason + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }

    /**
     * 라우팅 필터들보다 <b>앞</b>에서 돌아야 차단이 의미가 있다(뒤에 있으면 이미 다운스트림에 갔다 온 뒤).
     * 회로차단기 필터는 라우트에 붙는 {@code GatewayFilter} 라 이보다 안쪽에서 동작한다.
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }
}
