package com.shopsaga.payment.application.port.out;

import com.shopsaga.payment.domain.Payment;

import java.util.Optional;
import java.util.UUID;

/** 아웃바운드 포트: 결제 조회(Phase 14 환불 보상에서 대상 결제를 읽는다). */
public interface LoadPaymentPort {

    Optional<Payment> loadById(UUID paymentId);
}
