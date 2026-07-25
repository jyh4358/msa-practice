package com.shopsaga.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 12(Saga): <b>보상(compensation)</b> 사실 — 잡아뒀던 재고를 되돌렸다.
 *
 * <p>이것이 <b>semantic undo</b> 다: DB 롤백이 아니라 "예약을 취소한다"는 <b>새로운 업무 행위</b>로
 * 이전 효과를 상쇄한다(이미 커밋된 트랜잭션은 되돌릴 수 없으므로). 결제가 거절될 때 실행된다.
 */
public record InventoryReleasedEvent(
        UUID orderId,
        Instant occurredAt
) {
}
