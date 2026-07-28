package com.shopsaga.order.application.port.out;

import com.shopsaga.order.domain.saga.SagaInstance;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 아웃바운드 포트: Saga 인스턴스 상태 저장소(Phase 13). */
public interface SagaInstanceRepositoryPort {

    void save(SagaInstance instance);

    /** 상태 전이 반영(load-then-mutate). */
    void update(SagaInstance instance);

    Optional<SagaInstance> findBySagaId(UUID sagaId);

    Optional<SagaInstance> findByOrderId(UUID orderId);

    /**
     * 응답을 기다린 채 {@code deadline} 이 지나도록 움직이지 않은 Saga들 — 타임아웃 sweep의 대상.
     * 종료 상태(COMPLETED/CANCELLED)는 제외된다.
     */
    List<SagaInstance> findStalled(Instant now, Duration deadline, int limit);
}
