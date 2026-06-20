package com.shopsaga.order.domain;

/**
 * 주문 생명주기 상태.
 * Phase 0에서는 PENDING만 쓰지만, Saga(Phase 12·13)에서 나머지 전이를 채운다.
 */
public enum OrderStatus {
    PENDING,
    INVENTORY_RESERVED,
    PAID,
    CONFIRMED,
    SHIPPED,
    CANCELLED
}
