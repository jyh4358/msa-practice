package com.shopsaga.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Phase 12(Saga): 결제 <b>거절</b> 사실. payment-service가 발행한다.
 *
 * <p><b>두 소비자</b>가 각자 반응한다(코레오그래피의 특징 — 발행자는 누가 듣는지 모른다):
 * <ul>
 *   <li>inventory-service → 잡아둔 재고를 <b>보상</b>으로 해제({@link InventoryReleasedEvent})</li>
 *   <li>order-service → 주문을 CANCELLED 로</li>
 * </ul>
 */
public record PaymentDeclinedEvent(
        UUID orderId,
        BigDecimal amount,
        String reason,
        Instant occurredAt
) {
}
