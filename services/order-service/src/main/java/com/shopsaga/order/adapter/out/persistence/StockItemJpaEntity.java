package com.shopsaga.order.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/** 재고 영속 모델(JPA). product_id 가 자연키(앱 할당). */
@Entity
@Table(name = "stock_items")
class StockItemJpaEntity {

    @Id
    @Column(name = "product_id")
    private UUID productId;

    @Column(name = "available_quantity", nullable = false)
    private int availableQuantity;

    protected StockItemJpaEntity() {
        // JPA 전용
    }

    StockItemJpaEntity(UUID productId, int availableQuantity) {
        this.productId = productId;
        this.availableQuantity = availableQuantity;
    }

    UUID getProductId() {
        return productId;
    }

    int getAvailableQuantity() {
        return availableQuantity;
    }
}
