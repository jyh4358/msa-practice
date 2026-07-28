package com.shopsaga.payment.adapter.out.persistence;

import com.shopsaga.payment.domain.PaymentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class PaymentJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    /** Phase 14: 보상(환불) 시각. NULL = 정상 결제. */
    @Column(name = "refunded_at")
    private Instant refundedAt;

    PaymentJpaEntity(UUID orderId, BigDecimal amount, PaymentStatus status, Instant capturedAt) {
        this.orderId = orderId;
        this.amount = amount;
        this.status = status;
        this.capturedAt = capturedAt;
    }

    /** Phase 14: 영속 상태를 환불로 전이(더티 체킹으로 UPDATE). */
    void markRefunded(Instant when) {
        this.status = PaymentStatus.REFUNDED;
        this.refundedAt = when;
    }
}
