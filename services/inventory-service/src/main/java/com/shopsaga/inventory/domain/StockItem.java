package com.shopsaga.inventory.domain;

import lombok.Getter;

import java.util.UUID;

/**
 * 재고 애그리거트 — 순수 도메인. 상품별 가용 수량을 소유하고 예약(차감) 불변식을 보호한다.
 * (Phase 9에서 order-service로부터 inventory-service로 분리됨.)
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

    /**
     * Phase 12(Saga): <b>보상(semantic undo)</b> — 예약했던 수량을 되돌린다.
     *
     * <p>이건 DB 롤백이 아니다. 예약 트랜잭션은 이미 오래전에 커밋됐으므로 되돌릴 수 없고,
     * "다시 더한다"는 <b>새로운 업무 행위</b>로 효과를 상쇄한다. 그래서 보상은 도메인 연산이다.
     */
    public void release(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("release quantity must be positive: " + quantity);
        }
        this.availableQuantity += quantity;
    }
}
