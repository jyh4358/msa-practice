package com.shopsaga.order.adapter.out.persistence;

import com.shopsaga.order.domain.OrderStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 영속 모델(JPA). Phase 2: 결제는 payment-service 소유 → payment_id(참조)만 보관.
 * id는 도메인이 생성한 값을 그대로 사용(@GeneratedValue 아님) → save()는 merge 경로(신규는 SELECT 후 INSERT).
 */
@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class OrderJpaEntity {

    @Id
    private UUID id;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OrderStatus status;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "payment_id")
    private UUID paymentId;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItemJpaEntity> items = new ArrayList<>();

    OrderJpaEntity(UUID id, UUID customerId, OrderStatus status, BigDecimal totalAmount,
                   Instant createdAt, UUID paymentId) {
        this.id = id;
        this.customerId = customerId;
        this.status = status;
        this.totalAmount = totalAmount;
        this.createdAt = createdAt;
        this.paymentId = paymentId;
    }

    void addItem(UUID productId, int quantity, BigDecimal unitPrice) {
        items.add(new OrderItemJpaEntity(this, productId, quantity, unitPrice));
    }
}
