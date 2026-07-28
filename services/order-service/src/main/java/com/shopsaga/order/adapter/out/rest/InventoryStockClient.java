package com.shopsaga.order.adapter.out.rest;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Phase 14: <b>복원력 패턴 5종을 한 메서드에 겹쳐 놓은 학습용 클라이언트.</b>
 *
 * <h2>각 패턴이 막는 장애</h2>
 * <table border="1">
 *   <caption>패턴별 역할</caption>
 *   <tr><th>패턴</th><th>막는 것</th><th>없으면</th></tr>
 *   <tr><td>RateLimiter</td><td>과부하 — 초당 호출 수 상한</td><td>우리가 상대를 눌러 죽인다</td></tr>
 *   <tr><td>TimeLimiter</td><td>무한 대기 — 응답 시간 상한</td><td>느린 상대에게 스레드가 묶여 우리도 죽는다</td></tr>
 *   <tr><td>CircuitBreaker</td><td>죽은 의존성을 계속 때리는 것</td><td>매 요청이 타임아웃까지 기다린다</td></tr>
 *   <tr><td>Bulkhead</td><td>동시 호출 수 — 자원 격리</td><td>한 다운스트림이 전체 스레드를 잠식한다</td></tr>
 *   <tr><td>Retry</td><td>일시적 결함(순단·패킷 유실)</td><td>한 번 튄 오류가 그대로 실패가 된다</td></tr>
 * </table>
 *
 * <h2>⚠️ 애너테이션을 겹칠 때 반드시 알아야 하는 것 — 적용 순서</h2>
 * 애너테이션을 쓴 <b>순서는 의미가 없다</b>. 실제 중첩 순서는 각 aspect 의 order 값으로 정해지며,
 * Resilience4j 기본값은 {@code Retry(CircuitBreaker(RateLimiter(TimeLimiter(Bulkhead(호출))))) } 이다.
 * 이 프로젝트는 학습 계획에 따라 {@code config-repo/application.yml} 에서
 * <b>{@code RateLimiter → TimeLimiter → CircuitBreaker → Bulkhead → Retry}</b>(바깥→안쪽)로 바꿔 두었다.
 *
 * <p>순서가 바뀌면 <b>보이는 장애가 달라진다</b>. 지금 순서에서는
 * <ul>
 *   <li>Retry 가 가장 안쪽 → 회로차단기는 "재시도까지 마친 최종 결과" 1건만 센다(재시도가 통계를 부풀리지 않는다).</li>
 *   <li>TimeLimiter 가 회로차단기보다 <b>바깥</b> → <b>타임아웃은 회로를 열지 못한다</b>.
 *       느린 상대는 매번 타임아웃만 나고 회로는 CLOSED 로 남는다. 이걸 원한다면
 *       {@code circuitBreakerAspectOrder} 를 {@code timeLimiterAspectOrder} 보다 크게(= 더 바깥으로) 두어야 한다.</li>
 * </ul>
 * 이 트레이드오프는 문서 {@code docs/PHASE-14-RESILIENCE.md} §6 에서 실측과 함께 다룬다.
 *
 * <p>fallback 은 애너테이션이 아니라 {@link StockAvailabilityRestAdapter} 의 {@code try/catch} 로 처리한다 —
 * {@code fallbackMethod} 를 안쪽 aspect 에 달면 바깥 aspect 가 "성공"만 보게 되어
 * <b>회로가 영원히 안 열리는</b> 함정에 빠지기 때문이다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class InventoryStockClient {

    /** 모든 패턴이 공유하는 인스턴스 이름 — 설정도 메트릭 태그도 이 이름으로 묶인다. */
    static final String INSTANCE = "inventory";

    private final RestClient inventoryRestClient;
    private final Executor stockPrecheckExecutor;

    /**
     * 상품의 가용 수량을 물어본다. 실패는 예외로 <b>그대로 올린다</b>(여기서 삼키면 회로차단기가 배우지 못한다).
     *
     * @param bearerToken 호출 스레드에서 미리 꺼내 넘긴 JWT.
     *                    ⚠️ 여기서 {@code SecurityContextHolder} 를 읽으면 안 된다 —
     *                    이 메서드 본문은 <b>다른 스레드</b>에서 실행되므로 컨텍스트가 비어 있다.
     */
    @RateLimiter(name = INSTANCE)
    @TimeLimiter(name = INSTANCE)
    @CircuitBreaker(name = INSTANCE)
    @Bulkhead(name = INSTANCE)
    @Retry(name = INSTANCE)
    CompletableFuture<Integer> availableQuantity(UUID productId, String bearerToken) {
        return CompletableFuture.supplyAsync(() -> fetch(productId, bearerToken), stockPrecheckExecutor);
    }

    private Integer fetch(UUID productId, String bearerToken) {
        StockResponse body = inventoryRestClient.get()
                .uri("/inventory/{productId}", productId)
                .headers(headers -> {
                    if (bearerToken != null) {
                        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken);
                    }
                })
                .retrieve()
                .body(StockResponse.class);
        if (body == null) {
            throw new IllegalStateException("빈 응답: " + productId);
        }
        log.debug("재고 사전 확인 productId={} available={}", productId, body.availableQuantity());
        return body.availableQuantity();
    }

    /** inventory-service 의 {@code StockView} 와 맞춘 응답 DTO(계약은 HTTP 스키마로만 공유). */
    record StockResponse(UUID productId, int availableQuantity) {}
}
