package com.shopsaga.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 12(Saga): 주문 확정 사실. order-service가 발행한다(Saga 성공 종료).
 * 읽기 모델(order-query-service)이 듣고 status 를 CONFIRMED 로 전이시킨다.
 */
public record OrderConfirmedEvent(
        UUID orderId,
        UUID paymentId,
        Instant occurredAt
) {
}
