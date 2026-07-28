package com.shopsaga.order.domain.saga;

import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 13: Saga 인스턴스 — <b>순수 도메인</b> 상태 기계.
 *
 * <p>"이 주문의 Saga가 지금 어느 단계에 있고, 마지막으로 언제 움직였고, 몇 번 재촉했는가"를 안다.
 * 전이 규칙이 여기 모여 있어 <b>Kafka 없이 단위 테스트</b>할 수 있다(코레오그래피에서는 흐름이
 * 여러 서비스 리스너에 흩어져 있어 이런 테스트가 불가능했다).
 *
 * <p>모든 전이는 {@code boolean} 을 돌려준다 — 중복 리플라이·늦게 온 리플라이는 예외가 아니라
 * "할 일 없음(false)"으로 흡수한다(at-least-once 배달 전제, Phase 12와 같은 원칙).
 */
@Getter
public class SagaInstance {

    private final UUID sagaId;
    private final UUID orderId;
    private SagaState state;
    private Instant updatedAt;
    /** 현재 단계에서 커맨드를 보낸 횟수(최초 1 + sweep 재전송). 무한 재촉을 막는 근거. */
    private int attempts;

    private SagaInstance(UUID sagaId, UUID orderId, SagaState state, Instant updatedAt, int attempts) {
        this.sagaId = sagaId;
        this.orderId = orderId;
        this.state = state;
        this.updatedAt = updatedAt;
        this.attempts = attempts;
    }

    public static SagaInstance start(UUID orderId, Instant now) {
        return new SagaInstance(UUID.randomUUID(), orderId, SagaState.STARTED, now, 0);
    }

    public static SagaInstance restore(UUID sagaId, UUID orderId, SagaState state, Instant updatedAt, int attempts) {
        return new SagaInstance(sagaId, orderId, state, updatedAt, attempts);
    }

    /** 첫 커맨드(재고 예약)를 내보내며 대기 상태로. */
    public boolean awaitInventory(Instant now) {
        if (state != SagaState.STARTED) {
            return false;
        }
        return moveTo(SagaState.AWAITING_INVENTORY, now);
    }

    /** 재고 예약 성공 → 결제 지시 단계로. */
    public boolean awaitPayment(Instant now) {
        if (state != SagaState.AWAITING_INVENTORY) {
            return false;   // 중복 리플라이거나 이미 취소됨
        }
        return moveTo(SagaState.AWAITING_PAYMENT, now);
    }

    /** 결제 거절 → 재고 해제(보상) 지시 단계로. */
    public boolean startCompensation(Instant now) {
        if (state != SagaState.AWAITING_PAYMENT) {
            return false;
        }
        return moveTo(SagaState.COMPENSATING_INVENTORY, now);
    }

    /** 결제 성공 → 성공 종료. */
    public boolean complete(Instant now) {
        if (state != SagaState.AWAITING_PAYMENT) {
            return false;
        }
        return moveTo(SagaState.COMPLETED, now);
    }

    /**
     * 실패 종료. 재고 예약 실패(짧은 보상)와 보상 완료(긴 보상) 양쪽에서 도달한다.
     * 이미 종료된 Saga는 다시 종료하지 않는다(멱등).
     */
    public boolean cancel(Instant now) {
        if (state.isTerminal()) {
            return false;
        }
        return moveTo(SagaState.CANCELLED, now);
    }

    /**
     * 타임아웃 sweep이 커맨드를 재전송할 때 호출 — 시도 횟수를 올리고 시계를 리셋한다.
     * (상태는 그대로 유지: 여전히 같은 응답을 기다린다.)
     */
    public void recordRetry(Instant now) {
        this.attempts++;
        this.updatedAt = now;
    }

    /** 마지막 전이 이후 {@code deadline} 이 지나도록 응답이 없으면 정체된 것으로 본다. */
    public boolean isStalled(Instant now, java.time.Duration deadline) {
        return state.isAwaitingReply() && updatedAt.plus(deadline).isBefore(now);
    }

    private boolean moveTo(SagaState next, Instant now) {
        this.state = next;
        this.updatedAt = now;
        this.attempts = 0;   // 단계가 바뀌면 재시도 카운트를 새로 센다
        return true;
    }
}
