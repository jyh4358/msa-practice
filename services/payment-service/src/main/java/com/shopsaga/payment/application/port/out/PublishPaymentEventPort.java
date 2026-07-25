package com.shopsaga.payment.application.port.out;

import com.shopsaga.events.PaymentChargedEvent;
import com.shopsaga.events.PaymentDeclinedEvent;

/** 아웃바운드 포트: 결제 이벤트 발행(Phase 12). outbox 로 기록되어 결제 저장과 원자적이다. */
public interface PublishPaymentEventPort {

    void paymentCharged(PaymentChargedEvent event);

    void paymentDeclined(PaymentDeclinedEvent event);
}
