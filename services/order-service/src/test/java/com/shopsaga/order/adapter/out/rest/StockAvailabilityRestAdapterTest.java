package com.shopsaga.order.adapter.out.rest;

import com.shopsaga.order.application.port.in.StockPrecheck;
import com.shopsaga.order.application.port.out.StockAvailabilityPort;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Phase 14: <b>우아한 강등(graceful degradation)</b> 회귀 가드.
 *
 * <p>이 어댑터의 계약은 단 하나다 — <b>절대 예외를 던지지 않는다</b>.
 * 부가 기능(재고 사전 확인)의 실패가 본 기능(주문 접수)을 막는 순간,
 * 동기 호출을 다시 들인 판단 자체가 틀린 것이 되기 때문이다.
 */
@ExtendWith(MockitoExtension.class)
class StockAvailabilityRestAdapterTest {

    @Mock
    InventoryStockClient client;
    @InjectMocks
    StockAvailabilityRestAdapter adapter;

    private static final UUID PRODUCT = UUID.randomUUID();

    private List<StockAvailabilityPort.Line> lines(int quantity) {
        return List.of(new StockAvailabilityPort.Line(PRODUCT, quantity));
    }

    @Test
    void enoughStock_isAvailable() {
        when(client.availableQuantity(eq(PRODUCT), any())).thenReturn(CompletableFuture.completedFuture(10));

        assertThat(adapter.precheck(lines(2)).status()).isEqualTo(StockPrecheck.Status.AVAILABLE);
    }

    @Test
    void notEnoughStock_isInsufficient_withDetail() {
        when(client.availableQuantity(eq(PRODUCT), any())).thenReturn(CompletableFuture.completedFuture(1));

        StockPrecheck result = adapter.precheck(lines(5));

        assertThat(result.status()).isEqualTo(StockPrecheck.Status.INSUFFICIENT);
        assertThat(result.detail()).contains("가용=1");
    }

    @Test
    void openCircuit_degradesToUnknown_insteadOfThrowing() {
        CircuitBreaker breaker = CircuitBreaker.ofDefaults("inventory");
        breaker.transitionToOpenState();
        when(client.availableQuantity(eq(PRODUCT), any()))
                .thenReturn(CompletableFuture.failedFuture(CallNotPermittedException.createCallNotPermittedException(breaker)));

        StockPrecheck result = adapter.precheck(lines(1));

        assertThat(result.status()).isEqualTo(StockPrecheck.Status.UNKNOWN);
        // ⚠️ ASCII 만 — 이 값이 응답 헤더에 실리기 때문(한글이면 헤더가 통째로 사라진다).
        assertThat(result.detail()).isEqualTo("CIRCUIT_OPEN");
    }

    @Test
    void timeout_degradesToUnknown() {
        when(client.availableQuantity(eq(PRODUCT), any()))
                .thenReturn(CompletableFuture.failedFuture(new TimeoutException("느림")));

        StockPrecheck result = adapter.precheck(lines(1));

        assertThat(result.status()).isEqualTo(StockPrecheck.Status.UNKNOWN);
        assertThat(result.detail()).isEqualTo("TIMEOUT");
    }

    @Test
    void insufficientWins_overUnknown_whenMultipleItems() {
        UUID other = UUID.randomUUID();
        when(client.availableQuantity(eq(other), any()))
                .thenReturn(CompletableFuture.failedFuture(new TimeoutException("느림")));
        when(client.availableQuantity(eq(PRODUCT), any())).thenReturn(CompletableFuture.completedFuture(0));

        StockPrecheck result = adapter.precheck(List.of(
                new StockAvailabilityPort.Line(other, 1),
                new StockAvailabilityPort.Line(PRODUCT, 1)));

        // '확정된 부족'이 '모름'보다 더 유용한 정보다.
        assertThat(result.status()).isEqualTo(StockPrecheck.Status.INSUFFICIENT);
    }
}
