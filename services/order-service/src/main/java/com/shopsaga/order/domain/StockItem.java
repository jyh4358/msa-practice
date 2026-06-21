package com.shopsaga.order.domain;

import lombok.Getter;

import java.util.UUID;

/**
 * 재고 애그리거트 — 순수 도메인. 상품별 가용 수량을 소유하고 예약(차감) 불변식을 보호한다.
 * (Phase 1 모놀리스에서는 order-service가 직접 차감; Phase 2+에서 inventory-service로 분리된다.)
 */
@Getter
public class StockItem {

    private final UUID productId;
    private int availableQuantity;

    public StockItem(UUID productId, int availableQuantity) {
        if (productId == null) {
            throw new IllegalArgumentException("productId must not be null");
        }
        if (availableQuantity < 0) {
            throw new IllegalArgumentException("availableQuantity must not be negative");
        }
        this.productId = productId;
        this.availableQuantity = availableQuantity;
    }

    /** 재고 예약(차감). 가용 수량보다 많으면 InsufficientStockException → 트랜잭션 롤백 유발. */
    public void reserve(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("reserve quantity must be positive: " + quantity);
        }
        if (quantity > availableQuantity) {
            throw new InsufficientStockException(productId, quantity, availableQuantity);
        }
        this.availableQuantity -= quantity;
    }
}
