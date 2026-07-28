package com.shopsaga.events.commands;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Phase 13: 오케스트레이터가 payment에게 보내는 커맨드 — "이 주문에 이 금액을 청구해라".
 *
 * <p>Phase 12에서 payment는 {@code InventoryReserved}(사실)를 듣고 <b>스스로</b> 결제를 시작했다.
 * 이제는 지시를 받고서야 움직인다 — 결제 시점의 결정권이 조정자에게 있다.
 */
public record ChargePaymentCommand(
        UUID sagaId,
        UUID orderId,
        BigDecimal amount,
        Instant occurredAt
) {
}
