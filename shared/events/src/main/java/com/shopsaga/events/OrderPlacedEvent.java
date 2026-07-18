package com.shopsaga.events;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Phase 9: order-service가 주문을 생성할 때 발행하는 도메인 이벤트(계약).
 *
 * <p>inventory-service가 이 이벤트를 소비해 재고를 예약한다(비동기). order와 inventory가
 * <b>같은 타입</b>을 공유하므로 JSON 직렬화의 {@code __TypeId__} 헤더가 그대로 맞아떨어진다.
 *
 * <p>이 모듈(shared/events)에는 이벤트 계약만 둔다 — JPA 엔티티/리포지토리 금지.
 */
public record OrderPlacedEvent(
        UUID orderId,
        UUID customerId,
        List<Item> items
) {
    public record Item(UUID productId, int quantity, BigDecimal unitPrice) {}
}
