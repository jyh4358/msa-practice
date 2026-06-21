package com.shopsaga.order.application.service;

import java.math.BigDecimal;

/** 원격 결제가 거절됨(payment-service 402). → 402 로 전파. */
public class PaymentDeclinedException extends RuntimeException {

    public PaymentDeclinedException(BigDecimal amount) {
        super("Payment declined for amount " + amount);
    }
}
