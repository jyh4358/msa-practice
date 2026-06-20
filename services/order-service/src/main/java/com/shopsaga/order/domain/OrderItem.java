package com.shopsaga.order.domain;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 주문 항목 — 순수 도메인 값 객체. 프레임워크 의존 없음.
 */
public class OrderItem {

    private final UUID productId;
    private final int quantity;
    private final BigDecimal unitPrice;

    public OrderItem(UUID productId, int quantity, BigDecimal unitPrice) {
        // 도메인이 스스로 불변식을 보호한다 — 어떤 어댑터(웹/메시징/테스트)가 호출하든 동일하게 강제된다.
        if (productId == null) {
            throw new IllegalArgumentException("productId must not be null");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive: " + quantity);
        }
        if (unitPrice == null || unitPrice.signum() <= 0) {
            throw new IllegalArgumentException("unitPrice must be positive: " + unitPrice);
        }
        this.productId = productId;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
    }

    public BigDecimal lineTotal() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }

    public UUID getProductId() {
        return productId;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }
}
