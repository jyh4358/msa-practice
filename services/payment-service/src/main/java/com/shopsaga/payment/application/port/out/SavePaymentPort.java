package com.shopsaga.payment.application.port.out;

import com.shopsaga.payment.domain.Payment;

/** 아웃바운드 포트: 결제 저장. */
public interface SavePaymentPort {

    Payment save(Payment payment);
}
