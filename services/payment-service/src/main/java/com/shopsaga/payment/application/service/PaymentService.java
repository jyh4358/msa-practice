package com.shopsaga.payment.application.service;

import com.shopsaga.payment.application.UseCase;
import com.shopsaga.payment.application.port.in.CapturePaymentCommand;
import com.shopsaga.payment.application.port.in.CapturePaymentUseCase;
import com.shopsaga.payment.application.port.in.PaymentView;
import com.shopsaga.payment.application.port.out.SavePaymentPort;
import com.shopsaga.payment.domain.Payment;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

/** 결제 캡처 유스케이스. 도메인이 가짜 게이트웨이를 거쳐 캡처하고, 거절은 예외로 전파된다(→ 402). */
@UseCase
@RequiredArgsConstructor
class PaymentService implements CapturePaymentUseCase {

    private final SavePaymentPort savePaymentPort;

    @Override
    @Transactional
    public PaymentView capture(CapturePaymentCommand command) {
        Payment payment = Payment.capture(command.orderId(), command.amount());
        return PaymentView.from(savePaymentPort.save(payment));
    }
}
