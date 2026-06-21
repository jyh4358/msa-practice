package com.shopsaga.payment.domain;

import java.math.BigDecimal;

public class PaymentDeclinedException extends RuntimeException {

    public PaymentDeclinedException(BigDecimal amount) {
        super("Payment declined for amount " + amount);
    }
}
