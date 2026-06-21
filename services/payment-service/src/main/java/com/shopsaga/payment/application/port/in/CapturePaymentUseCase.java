package com.shopsaga.payment.application.port.in;

/** 인바운드 포트: 결제를 캡처한다. */
public interface CapturePaymentUseCase {

    PaymentView capture(CapturePaymentCommand command);
}
