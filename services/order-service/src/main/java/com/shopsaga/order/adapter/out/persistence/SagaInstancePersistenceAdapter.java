package com.shopsaga.order.adapter.out.persistence;

import com.shopsaga.order.application.port.out.SagaInstanceRepositoryPort;
import com.shopsaga.order.domain.saga.SagaInstance;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 아웃바운드 영속 어댑터: Saga 인스턴스 저장/조회/전이(Phase 13). */
@Component
@RequiredArgsConstructor
class SagaInstancePersistenceAdapter implements SagaInstanceRepositoryPort {

    private final SagaInstanceJpaRepository repository;
    private final SagaInstanceQueryRepository queryRepository;

    @Override
    public void save(SagaInstance instance) {
        repository.save(new SagaInstanceJpaEntity(
                instance.getSagaId(), instance.getOrderId(), instance.getState(),
                instance.getAttempts(), instance.getUpdatedAt()));
    }

    @Override
    public void update(SagaInstance instance) {
        // load-then-mutate — managed 엔티티의 필드만 바꿔 dirty checking UPDATE.
        repository.findById(instance.getSagaId()).ifPresent(managed ->
                managed.apply(instance.getState(), instance.getAttempts(), instance.getUpdatedAt()));
    }

    @Override
    public Optional<SagaInstance> findBySagaId(UUID sagaId) {
        return repository.findById(sagaId).map(this::toDomain);
    }

    @Override
    public Optional<SagaInstance> findByOrderId(UUID orderId) {
        return repository.findByOrderId(orderId).map(this::toDomain);
    }

    @Override
    public List<SagaInstance> findStalled(Instant now, Duration deadline, int limit) {
        return queryRepository.findStalled(now.minus(deadline), limit).stream()
                .map(this::toDomain)
                .toList();
    }

    private SagaInstance toDomain(SagaInstanceJpaEntity entity) {
        return SagaInstance.restore(entity.getSagaId(), entity.getOrderId(),
                entity.getState(), entity.getUpdatedAt(), entity.getAttempts());
    }
}
