package com.shopsaga.payment.application.port.in;

import com.shopsaga.payment.domain.Payment;
import com.shopsaga.payment.domain.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** 인바운드 포트 출력 모델(불변 뷰). */
public record PaymentView(
        UUID paymentId,
        UUID orderId,
        BigDecimal amount,
        PaymentStatus status,
        Instant capturedAt
) {
    public static PaymentView from(Payment payment) {
        return new PaymentView(payment.getId(), payment.getOrderId(), payment.getAmount(),
                payment.getStatus(), payment.getCapturedAt());
    }
}
