package com.shopsaga.payment.application.port.in;

import java.math.BigDecimal;
import java.util.UUID;

/** 인바운드 포트 입력 모델. */
public record CapturePaymentCommand(UUID orderId, BigDecimal amount) {
}
