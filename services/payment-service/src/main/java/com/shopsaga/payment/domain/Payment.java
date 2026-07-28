package com.shopsaga.payment.domain;

import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 결제 애그리거트 — 순수 도메인. payment-service가 소유한다(Phase 2에서 order-service로부터 분리).
 * 가짜 결제 게이트웨이(합계 .99 → 거절)가 여기로 이동했다.
 *
 * <p>Phase 14: {@link #refund(Instant)} 추가 — Saga 종료 후 뒤늦게 성립한 결제를 되돌리는 보상.
 */
@Getter
public class Payment {

    /** 가짜 게이트웨이 stub: 합계가 .99로 끝나면 거절. 실제로는 외부 PG 호출이 들어갈 자리. */
    private static final BigDecimal DECLINE_REMAINDER = new BigDecimal("0.99");

    private UUID id;
    private final UUID orderId;
    private final BigDecimal amount;
    private PaymentStatus status;
    private final Instant capturedAt;
    private Instant refundedAt;

    private Payment(UUID id, UUID orderId, BigDecimal amount, PaymentStatus status,
                    Instant capturedAt, Instant refundedAt) {
        this.id = id;
        this.orderId = orderId;
        this.amount = amount;
        this.status = status;
        this.capturedAt = capturedAt;
        this.refundedAt = refundedAt;
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
        return new Payment(null, orderId, amount, PaymentStatus.CAPTURED, Instant.now(), null);
    }

    public static Payment restore(UUID id, UUID orderId, BigDecimal amount, PaymentStatus status,
                                  Instant capturedAt, Instant refundedAt) {
        return new Payment(id, orderId, amount, status, capturedAt, refundedAt);
    }

    /**
     * Phase 14 보상: 결제를 되돌린다. 이미 환불된 결제면 {@code false}(멱등) —
     * 커맨드가 두 번 배달돼도 이중 환불이 일어나지 않는다.
     */
    public boolean refund(Instant when) {
        if (status == PaymentStatus.REFUNDED) {
            return false;
        }
        this.status = PaymentStatus.REFUNDED;
        this.refundedAt = when;
        return true;
    }
}
