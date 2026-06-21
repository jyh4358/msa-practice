package com.shopsaga.payment.adapter.out.persistence;

import com.shopsaga.payment.domain.Payment;

final class PaymentMapper {

    private PaymentMapper() {
    }

    static PaymentJpaEntity toJpaEntity(Payment payment) {
        return new PaymentJpaEntity(payment.getOrderId(), payment.getAmount(),
                payment.getStatus(), payment.getCapturedAt());
    }

    static Payment toDomain(PaymentJpaEntity entity) {
        return Payment.restore(entity.getId(), entity.getOrderId(), entity.getAmount(),
                entity.getStatus(), entity.getCapturedAt());
    }
}
