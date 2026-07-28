package com.shopsaga.payment.application.port.out;

import com.shopsaga.payment.domain.Payment;

/** 아웃바운드 포트: 기존 결제 상태 변경(Phase 14 환불 보상). 신규 INSERT는 {@link SavePaymentPort}. */
public interface UpdatePaymentPort {

    void update(Payment payment);
}
