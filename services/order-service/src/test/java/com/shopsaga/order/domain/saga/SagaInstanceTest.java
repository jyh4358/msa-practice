package com.shopsaga.order.domain.saga;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 13: Saga 상태 기계 단위 테스트 — <b>Kafka도 DB도 없이</b> 흐름 전체를 검증한다.
 *
 * <p>이것이 오케스트레이션의 대표적 이점이다. 코레오그래피(Phase 12)에서는 흐름이 여러 서비스의
 * 리스너에 흩어져 있어 "재고 성공 후 결제 거절 시 어떻게 되나"를 이렇게 한 파일에서 테스트할 수 없었다.
 */
class SagaInstanceTest {

    private static final Instant T0 = Instant.parse("2026-07-28T10:00:00Z");

    private SagaInstance started() {
        SagaInstance saga = SagaInstance.start(UUID.randomUUID(), T0);
        saga.awaitInventory(T0);
        return saga;
    }

    @Test
    void happyPath_reachesCompleted() {
        SagaInstance saga = started();
        assertThat(saga.getState()).isEqualTo(SagaState.AWAITING_INVENTORY);

        assertThat(saga.awaitPayment(T0.plusSeconds(1))).isTrue();
        assertThat(saga.getState()).isEqualTo(SagaState.AWAITING_PAYMENT);

        assertThat(saga.complete(T0.plusSeconds(2))).isTrue();
        assertThat(saga.getState()).isEqualTo(SagaState.COMPLETED);
        assertThat(saga.getState().isTerminal()).isTrue();
    }

    @Test
    void shortCompensation_inventoryFailsBeforeAnyPayment() {
        SagaInstance saga = started();

        // 재고 실패 → 되돌릴 것이 없으므로 보상 없이 바로 종료
        assertThat(saga.cancel(T0.plusSeconds(1))).isTrue();
        assertThat(saga.getState()).isEqualTo(SagaState.CANCELLED);
    }

    @Test
    void longCompensation_paymentDeclinedGoesThroughCompensatingState() {
        SagaInstance saga = started();
        saga.awaitPayment(T0.plusSeconds(1));

        // 결제 거절 → 곧바로 취소하지 않고 보상 단계를 거친다(재고를 풀어야 하므로)
        assertThat(saga.startCompensation(T0.plusSeconds(2))).isTrue();
        assertThat(saga.getState()).isEqualTo(SagaState.COMPENSATING_INVENTORY);
        assertThat(saga.getState().isTerminal()).isFalse();

        // 재고 해제 완료 리플라이 → 그때서야 종료
        assertThat(saga.cancel(T0.plusSeconds(3))).isTrue();
        assertThat(saga.getState()).isEqualTo(SagaState.CANCELLED);
    }

    @Test
    void transitions_areIdempotent_forDuplicateReplies() {
        SagaInstance saga = started();
        saga.awaitPayment(T0.plusSeconds(1));

        // 같은 리플라이가 두 번 배달돼도(at-least-once) 상태가 흔들리지 않는다
        assertThat(saga.awaitPayment(T0.plusSeconds(2))).isFalse();
        assertThat(saga.getState()).isEqualTo(SagaState.AWAITING_PAYMENT);

        saga.complete(T0.plusSeconds(3));
        assertThat(saga.complete(T0.plusSeconds(4))).isFalse();
        assertThat(saga.getState()).isEqualTo(SagaState.COMPLETED);
    }

    @Test
    void terminalSaga_ignoresLateReplies() {
        SagaInstance saga = started();
        saga.cancel(T0.plusSeconds(1));

        // 취소된 뒤 늦게 도착한 리플라이들 — 종료 상태를 되살리지 않는다
        assertThat(saga.awaitPayment(T0.plusSeconds(2))).isFalse();
        assertThat(saga.complete(T0.plusSeconds(3))).isFalse();
        assertThat(saga.cancel(T0.plusSeconds(4))).isFalse();
        assertThat(saga.getState()).isEqualTo(SagaState.CANCELLED);
    }

    @Test
    void isStalled_onlyWhenAwaitingReplyAndDeadlinePassed() {
        SagaInstance saga = started();   // AWAITING_INVENTORY, updatedAt=T0
        Duration deadline = Duration.ofSeconds(15);

        assertThat(saga.isStalled(T0.plusSeconds(10), deadline)).isFalse();   // 아직 데드라인 전
        assertThat(saga.isStalled(T0.plusSeconds(20), deadline)).isTrue();    // 응답 없이 데드라인 초과

        // 종료된 Saga는 아무리 오래돼도 정체가 아니다(sweep 대상 제외)
        saga.complete(T0.plusSeconds(1));
        saga.cancel(T0.plusSeconds(1));
        SagaInstance done = SagaInstance.restore(UUID.randomUUID(), UUID.randomUUID(),
                SagaState.COMPLETED, T0, 0);
        assertThat(done.isStalled(T0.plusSeconds(9999), deadline)).isFalse();
    }

    @Test
    void recordRetry_incrementsAttemptsAndResetsClock_butKeepsState() {
        SagaInstance saga = started();
        Duration deadline = Duration.ofSeconds(15);
        Instant later = T0.plusSeconds(20);

        saga.recordRetry(later);

        assertThat(saga.getAttempts()).isEqualTo(1);
        assertThat(saga.getState()).isEqualTo(SagaState.AWAITING_INVENTORY);   // 여전히 같은 응답을 기다린다
        assertThat(saga.isStalled(later.plusSeconds(1), deadline)).isFalse();  // 시계가 리셋돼 바로 또 재촉하지 않는다
    }

    @Test
    void attempts_resetWhenStageChanges() {
        SagaInstance saga = started();
        saga.recordRetry(T0.plusSeconds(20));
        saga.recordRetry(T0.plusSeconds(40));
        assertThat(saga.getAttempts()).isEqualTo(2);

        // 단계가 바뀌면 재시도 카운트를 새로 센다(다음 단계는 처음부터 3번의 기회를 갖는다)
        saga.awaitPayment(T0.plusSeconds(41));
        assertThat(saga.getAttempts()).isZero();
    }
}
