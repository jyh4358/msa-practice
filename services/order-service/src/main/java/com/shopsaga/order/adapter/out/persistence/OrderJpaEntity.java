package com.shopsaga.order.adapter.out.persistence;

import com.shopsaga.order.domain.OrderStatus;
import com.shopsaga.order.domain.PaymentStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
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
 * 영속 모델(JPA). 도메인 Order와 분리된 어댑터 내부 타입.
 * (Lombok: @Getter/@NoArgsConstructor만 — @ToString/@EqualsAndHashCode 는 JPA에서 위험하므로 미사용)
 */
@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class OrderJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
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

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItemJpaEntity> items = new ArrayList<>();

    @OneToOne(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private PaymentJpaEntity payment;

    OrderJpaEntity(UUID customerId, OrderStatus status, BigDecimal totalAmount, Instant createdAt) {
        this.customerId = customerId;
        this.status = status;
        this.totalAmount = totalAmount;
        this.createdAt = createdAt;
    }

    void addItem(UUID productId, int quantity, BigDecimal unitPrice) {
        items.add(new OrderItemJpaEntity(this, productId, quantity, unitPrice));
    }

    void setPayment(BigDecimal amount, PaymentStatus status, Instant capturedAt) {
        this.payment = new PaymentJpaEntity(this, amount, status, capturedAt);
    }
}
