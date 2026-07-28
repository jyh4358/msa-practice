package com.shopsaga.events.commands;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 13: <b>보상 커맨드</b> — 오케스트레이터가 inventory에게 "잡아둔 재고를 풀어라"라고 지시한다.
 *
 * <p>Phase 12에서는 inventory가 {@code PaymentDeclined} 를 <b>스스로 듣고</b> 보상 여부를 판단했다.
 * 여기서는 <b>오케스트레이터가 결정</b>한다 — 보상이 필요한지, 어느 시점에 할지를 중앙에서 안다.
 * 그래서 참여 서비스는 "멍청한(dumb) 커맨드 핸들러"가 된다: 시키는 대로 하고 결과만 돌려준다.
 */
public record ReleaseStockCommand(
        UUID sagaId,
        UUID orderId,
        Instant occurredAt
) {
}
