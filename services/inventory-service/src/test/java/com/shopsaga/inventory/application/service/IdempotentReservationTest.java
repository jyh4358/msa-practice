package com.shopsaga.inventory.application.service;

import com.shopsaga.inventory.application.port.out.LoadStockPort;
import com.shopsaga.inventory.application.port.out.ProcessedMessagePort;
import com.shopsaga.inventory.application.port.out.ReserveStockPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 10: 같은 메시지가 두 번 배달돼도 부수효과(예약)는 정확히 한 번만 — effectively-once.
 */
@ExtendWith(MockitoExtension.class)
class IdempotentReservationTest {

    @Mock
    LoadStockPort loadStockPort;
    @Mock
    ReserveStockPort reserveStockPort;
    @Mock
    ProcessedMessagePort processedMessagePort;
    @InjectMocks
    StockService stockService;

    @Test
    void duplicateDelivery_reservesExactlyOnce() {
        UUID messageId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        Map<UUID, Integer> lines = Map.of(productId, 2);

        // 1차 배달: 미처리 → 예약 + 처리기록. 2차 배달(같은 messageId): 이미 처리 → 건너뜀.
        when(processedMessagePort.isAlreadyProcessed(messageId)).thenReturn(false, true);

        stockService.reserveForOrder(messageId, orderId, lines);
        stockService.reserveForOrder(messageId, orderId, lines);

        verify(reserveStockPort, times(1)).reserve(eq(productId), eq(2));   // 예약은 정확히 1회
        verify(processedMessagePort, times(1)).markProcessed(messageId);    // 기록도 1회
        verify(reserveStockPort, never()).reserve(eq(productId), eq(4));
    }
}
