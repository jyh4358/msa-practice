package com.shopsaga.payment.application.port.in;

import com.shopsaga.events.commands.ChargePaymentCommand;

/**
 * Phase 13: 결제 서비스의 <b>커맨드 핸들러</b>.
 *
 * <p>Phase 12에서는 {@code InventoryReserved}(사실)를 듣고 "재고가 잡혔으니 이제 결제할 차례군"이라고
 * <b>스스로 판단</b>했다. 이제는 "이 금액을 청구해라"라는 지시를 받고서야 움직인다 —
 * 결제 시점의 결정권이 조정자에게 있다.
 */
public interface PaymentCommandUseCase {

    void onChargePayment(ChargePaymentCommand command);
}
