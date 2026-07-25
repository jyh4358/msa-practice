package com.shopsaga.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 12(Saga): 재고 예약 <b>실패</b> 사실(품절·미등록 상품). inventory-service가 발행한다.
 *
 * <p>이건 <b>짧은 보상</b> 경로다: 아직 결제 전이므로 되돌릴 것이 없고, order가 이 이벤트를 듣고
 * 주문을 CANCELLED 로 만들면 끝난다. (긴 보상은 {@link PaymentDeclinedEvent} 경로 — 이미 잡아둔 재고를 풀어야 한다.)
 */
public record InventoryFailedEvent(
        UUID orderId,
        String reason,
        Instant occurredAt
) {
}
