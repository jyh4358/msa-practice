package com.shopsaga.order.application.port.out;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 아웃바운드 포트: 결제 게이트웨이(원격 payment-service). 통신 수단(REST 등)은 어댑터의 구현 세부사항.
 * 성공 시 발급된 paymentId 반환. 거절 → PaymentDeclinedException, 통신 실패 → PaymentGatewayException.
 */
public interface PaymentGatewayPort {

    UUID capture(UUID orderId, BigDecimal amount);
}
