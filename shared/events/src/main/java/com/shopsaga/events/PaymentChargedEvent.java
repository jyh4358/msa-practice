package com.shopsaga.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Phase 12(Saga): 결제 <b>성공</b> 사실. payment-service가 발행한다.
 * order-service가 듣고 주문을 CONFIRMED 로 확정한다(Saga 해피패스의 마지막 단계).
 */
public record PaymentChargedEvent(
        UUID orderId,
        UUID paymentId,
        BigDecimal amount,
        Instant occurredAt
) {
}
