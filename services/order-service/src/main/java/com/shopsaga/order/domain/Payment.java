package com.shopsaga.order.domain;

import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 결제 — Order 애그리거트의 일부(주문당 1건). 순수 도메인.
 * Phase 1에서는 가짜 게이트웨이를 거쳐 캡처되며, 거절은 Order.capturePayment()에서 예외로 처리한다.
 */
@Getter
public class Payment {

    private final BigDecimal amount;
    private final PaymentStatus status;
    private final Instant capturedAt;

    private Payment(BigDecimal amount, PaymentStatus status, Instant capturedAt) {
        this.amount = amount;
        this.status = status;
        this.capturedAt = capturedAt;
    }

    public static Payment capture(BigDecimal amount) {
        return new Payment(amount, PaymentStatus.CAPTURED, Instant.now());
    }

    public static Payment restore(BigDecimal amount, PaymentStatus status, Instant capturedAt) {
        return new Payment(amount, status, capturedAt);
    }
}
