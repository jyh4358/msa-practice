package com.shopsaga.order.domain;

import lombok.Getter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 주문 항목 — 순수 도메인 값 객체. 생성자에서 불변식을 강제한다(어떤 어댑터가 호출하든 동일).
 */
@Getter
public class OrderItem {

    private final UUID productId;
    private final int quantity;
    private final BigDecimal unitPrice;

    public OrderItem(UUID productId, int quantity, BigDecimal unitPrice) {
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
}
