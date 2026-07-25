package com.shopsaga.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Phase 12(Saga): 재고 예약 <b>성공</b> 사실. inventory-service가 발행한다.
 *
 * <p>Saga의 다음 단계인 payment-service가 이 이벤트를 듣고 결제를 청구한다. 그래서 결제에 필요한
 * {@code totalAmount} 를 <b>이벤트가 실어 온다</b> — payment는 주문 DB를 조회하지 않는다(서비스 간 DB 접근 금지).
 */
public record InventoryReservedEvent(
        UUID orderId,
        UUID customerId,
        BigDecimal totalAmount,
        Instant occurredAt
) {
}
