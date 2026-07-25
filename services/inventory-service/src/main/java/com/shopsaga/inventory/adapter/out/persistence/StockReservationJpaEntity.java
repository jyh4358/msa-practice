package com.shopsaga.inventory.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Phase 12: 예약 원장 row — "이 주문에 이 상품을 이만큼 잡아뒀다".
 * 보상(재고 해제) 시 무엇을 되돌릴지 알기 위한 기록이며, 해제하면 삭제된다.
 */
@Entity
@Table(name = "stock_reservations")
@IdClass(StockReservationJpaEntity.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class StockReservationJpaEntity {

    @Id
    @Column(name = "order_id")
    private UUID orderId;

    @Id
    @Column(name = "product_id")
    private UUID productId;

    @Column(nullable = false)
    private int quantity;

    StockReservationJpaEntity(UUID orderId, UUID productId, int quantity) {
        this.orderId = orderId;
        this.productId = productId;
        this.quantity = quantity;
    }

    /** 복합 키(주문 + 상품) — 한 주문에 같은 상품은 한 row 로 합산해 기록한다. */
    @NoArgsConstructor
    @Getter
    static class Key implements Serializable {
        private UUID orderId;
        private UUID productId;

        Key(UUID orderId, UUID productId) {
            this.orderId = orderId;
            this.productId = productId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key other)) {
                return false;
            }
            return Objects.equals(orderId, other.orderId) && Objects.equals(productId, other.productId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(orderId, productId);
        }
    }
}
