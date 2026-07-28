package com.shopsaga.order.adapter.out.persistence;

import com.shopsaga.order.domain.saga.SagaState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** Phase 13: Saga 인스턴스 영속 모델(JPA). id는 도메인이 생성한 값을 그대로 쓴다. */
@Entity
@Table(name = "saga_instance")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class SagaInstanceJpaEntity {

    @Id
    @Column(name = "saga_id")
    private UUID sagaId;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private SagaState state;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    SagaInstanceJpaEntity(UUID sagaId, UUID orderId, SagaState state, int attempts, Instant updatedAt) {
        this.sagaId = sagaId;
        this.orderId = orderId;
        this.state = state;
        this.attempts = attempts;
        this.updatedAt = updatedAt;
    }

    /** 상태 전이 반영(load-then-mutate → dirty checking UPDATE). */
    void apply(SagaState state, int attempts, Instant updatedAt) {
        this.state = state;
        this.attempts = attempts;
        this.updatedAt = updatedAt;
    }
}
