package com.shopsaga.order.domain.saga;

import java.util.Set;

/**
 * Phase 13: Saga 인스턴스의 상태.
 *
 * <p><b>이 enum 하나가 Phase 12와의 가장 큰 차이다.</b> 코레오그래피에서는 "지금 어디까지 갔나"를
 * 알려면 여러 서비스의 로그와 DB를 뒤져야 했다. 여기서는 조정자가 이 상태를 <b>한 테이블에</b> 기록하므로
 * {@code SELECT state FROM saga_instance WHERE order_id=…} 한 줄로 알 수 있다.
 *
 * <pre>
 *   STARTED ─▶ AWAITING_INVENTORY ─▶ AWAITING_PAYMENT ─▶ COMPLETED
 *                    │                      │
 *                    │(재고 실패)            │(결제 거절)
 *                    ▼                      ▼
 *                CANCELLED  ◀─── COMPENSATING_INVENTORY (재고 해제 지시 후 대기)
 * </pre>
 */
public enum SagaState {

    /** 생성 직후(아직 첫 커맨드를 내보내기 전). */
    STARTED,

    /** 재고 예약을 지시하고 리플라이를 기다리는 중. */
    AWAITING_INVENTORY,

    /** 결제를 지시하고 리플라이를 기다리는 중. */
    AWAITING_PAYMENT,

    /** 결제가 거절되어 재고 해제를 지시하고 리플라이를 기다리는 중(보상 진행). */
    COMPENSATING_INVENTORY,

    /** 성공 종료. */
    COMPLETED,

    /** 실패 종료(보상까지 끝난 상태 포함). */
    CANCELLED;

    private static final Set<SagaState> TERMINAL = Set.of(COMPLETED, CANCELLED);

    /** 더 이상 진행할 것이 없는 상태 — 리플라이가 늦게 와도 무시하고, sweep 대상도 아니다. */
    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /** 참여 서비스의 응답을 기다리는 상태 — 응답이 오지 않으면 타임아웃 sweep의 대상이 된다. */
    public boolean isAwaitingReply() {
        return this == AWAITING_INVENTORY || this == AWAITING_PAYMENT || this == COMPENSATING_INVENTORY;
    }
}
