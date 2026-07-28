package com.shopsaga.events.commands;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Phase 13(오케스트레이션): 오케스트레이터가 inventory에게 보내는 <b>커맨드</b>.
 *
 * <p><b>이벤트와의 차이가 이 Phase의 핵심이다:</b>
 * <ul>
 *   <li><b>이벤트</b>("재고가 예약됐다") = 이미 일어난 사실. 발행자는 <b>누가 듣는지 모른다</b>.</li>
 *   <li><b>커맨드</b>("재고를 예약해라") = 특정 수신자에게 시키는 일. 발신자는 <b>누구에게 보내는지 안다</b>.</li>
 * </ul>
 * 코레오그래피(Phase 12)는 사실만 주고받아 결합이 느슨했지만 흐름이 흩어졌다.
 * 오케스트레이션은 조정자가 명시적으로 지시해 <b>흐름을 한곳에 모으는</b> 대신 조정자에 대한 결합이 생긴다.
 *
 * <p>{@code sagaId} 는 이 Saga 인스턴스를 관통하는 상관 ID다 — 리플라이가 어느 Saga의 것인지 식별한다.
 */
public record ReserveStockCommand(
        UUID sagaId,
        UUID orderId,
        UUID customerId,
        BigDecimal totalAmount,
        List<Item> items,
        Instant occurredAt
) {
    public record Item(UUID productId, int quantity, BigDecimal unitPrice) {}
}
