package com.shopsaga.payment.domain;

import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 결제 애그리거트 — 순수 도메인. payment-service가 소유한다(Phase 2에서 order-service로부터 분리).
 * 가짜 결제 게이트웨이(합계 .99 → 거절)가 여기로 이동했다.
 */
@Getter
public class Payment {

    /** 가짜 게이트웨이 stub: 합계가 .99로 끝나면 거절. 실제로는 외부 PG 호출이 들어갈 자리. */
    private static final BigDecimal DECLINE_REMAINDER = new BigDecimal("0.99");

    private UUID id;
    private final UUID orderId;
    private final BigDecimal amount;
    private final PaymentStatus status;
    private final Instant capturedAt;

    private Payment(UUID id, UUID orderId, BigDecimal amount, PaymentStatus status, Instant capturedAt) {
        this.id = id;
        this.orderId = orderId;
        this.amount = amount;
        this.status = status;
        this.capturedAt = capturedAt;
    }

    /** 결제 캡처. 가짜 게이트웨이가 거절하면 PaymentDeclinedException. */
    public static Payment capture(UUID orderId, BigDecimal amount) {
        if (orderId == null) {
            throw new IllegalArgumentException("orderId must not be null");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive: " + amount);
        }
        if (amount.remainder(BigDecimal.ONE).compareTo(DECLINE_REMAINDER) == 0) {
            throw new PaymentDeclinedException(amount);
        }
        return new Payment(null, orderId, amount, PaymentStatus.CAPTURED, Instant.now());
    }

    public static Payment restore(UUID id, UUID orderId, BigDecimal amount, PaymentStatus status, Instant capturedAt) {
        return new Payment(id, orderId, amount, status, capturedAt);
    }
}
