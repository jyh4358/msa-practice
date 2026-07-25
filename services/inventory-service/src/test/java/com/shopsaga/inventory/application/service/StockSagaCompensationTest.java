package com.shopsaga.inventory.application.service;

import com.shopsaga.events.OrderPlacedEvent;
import com.shopsaga.events.PaymentDeclinedEvent;
import com.shopsaga.inventory.application.port.out.LoadStockPort;
import com.shopsaga.inventory.domain.InsufficientStockException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 12: 재고의 Saga 참여 검증 — 예약 실패가 <b>이벤트로 알려지는지</b>(Phase 9의 구멍 해결),
 * 보상이 별도 트랜잭션으로 나뉘는지, 중복 배달이 흡수되는지.
 */
@ExtendWith(MockitoExtension.class)
class StockSagaCompensationTest {

    @Mock
    LoadStockPort loadStockPort;
    @Mock
    StockSagaTransactions transactions;
    @InjectMocks
    StockService service;

    private static final Instant NOW = Instant.parse("2026-07-18T10:00:00Z");

    private OrderPlacedEvent placed(UUID orderId, UUID productId, int qty) {
        return new OrderPlacedEvent(orderId, UUID.randomUUID(),
                List.of(new OrderPlacedEvent.Item(productId, qty, new BigDecimal("10.00"))),
                new BigDecimal("10.00").multiply(BigDecimal.valueOf(qty)), NOW);
    }

    @Test
    void onOrderPlaced_reservesInSingleTransaction() {
        UUID orderId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        OrderPlacedEvent event = placed(orderId, productId, 3);

        service.onOrderPlaced(messageId, event);

        verify(transactions).reserve(eq(messageId), eq(event), eq(Map.of(productId, 3)));
        verify(transactions, never()).recordFailure(any(), any(), any());
    }

    @Test
    void insufficientStock_rollsBackReservation_thenPublishesFailureSeparately() {
        UUID orderId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        OrderPlacedEvent event = placed(orderId, productId, 999);
        doThrow(new InsufficientStockException(productId, 999, 5))
                .when(transactions).reserve(any(), any(), anyMap());

        service.onOrderPlaced(messageId, event);

        // 예약 트랜잭션은 롤백됐고(부분 차감 없음), 실패 사실은 새 트랜잭션에서 발행된다.
        // → 이 이벤트가 있어야 order 가 주문을 취소한다(Phase 9에서는 로그만 남아 주문이 매달렸다).
        verify(transactions).recordFailure(eq(messageId), eq(event), any());
    }

    @Test
    void onPaymentDeclined_releasesReservation_compensation() {
        UUID orderId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();

        service.onPaymentDeclined(messageId, new PaymentDeclinedEvent(
                orderId, new BigDecimal("10.99"), "결제 거절", NOW));

        verify(transactions).release(messageId, orderId);
    }

    @Test
    void duplicateDelivery_skipsBothReserveAndRelease() {
        UUID messageId = UUID.randomUUID();
        when(transactions.alreadyProcessed(messageId)).thenReturn(true);

        service.onOrderPlaced(messageId, placed(UUID.randomUUID(), UUID.randomUUID(), 1));
        service.onPaymentDeclined(messageId, new PaymentDeclinedEvent(
                UUID.randomUUID(), new BigDecimal("10.00"), "결제 거절", NOW));

        verify(transactions, never()).reserve(any(), any(), anyMap());
        verify(transactions, never()).release(any(), any());
    }
}
