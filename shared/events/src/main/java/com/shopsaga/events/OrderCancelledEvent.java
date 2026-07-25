package com.shopsaga.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 12(Saga): 주문 취소 사실. order-service가 발행한다(Saga 실패 종료).
 * 읽기 모델이 듣고 status 를 CANCELLED 로 전이시킨다. {@code reason} 으로 왜 취소됐는지 보인다.
 */
public record OrderCancelledEvent(
        UUID orderId,
        String reason,
        Instant occurredAt
) {
}
