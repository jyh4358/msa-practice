package com.shopsaga.order.adapter.out.persistence;

import com.shopsaga.order.domain.PaymentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** 결제 영속 모델(JPA). Order 애그리거트의 자식(주문당 1건). */
@Entity
@Table(name = "payments")
class PaymentJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(optional = false)
    @JoinColumn(name = "order_id", nullable = false, unique = true)
    private OrderJpaEntity order;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    protected PaymentJpaEntity() {
        // JPA 전용
    }

    PaymentJpaEntity(OrderJpaEntity order, BigDecimal amount, PaymentStatus status, Instant capturedAt) {
        this.order = order;
        this.amount = amount;
        this.status = status;
        this.capturedAt = capturedAt;
    }

    BigDecimal getAmount() {
        return amount;
    }

    PaymentStatus getStatus() {
        return status;
    }

    Instant getCapturedAt() {
        return capturedAt;
    }
}
