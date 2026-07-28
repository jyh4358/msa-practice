package com.shopsaga.order.adapter.out.persistence;

import com.querydsl.jpa.impl.JPAQueryFactory;
import com.shopsaga.order.domain.saga.SagaState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

import static com.shopsaga.order.adapter.out.persistence.QSagaInstanceJpaEntity.sagaInstanceJpaEntity;

/**
 * Phase 13: 타임아웃 sweep 용 조회 — QueryDSL(하우스 룰: 리포지토리 JPQL 금지).
 */
@Repository
@RequiredArgsConstructor
class SagaInstanceQueryRepository {

    private final JPAQueryFactory queryFactory;

    /**
     * "응답을 기다리는 중인데 {@code threshold} 이후로 움직이지 않은" Saga들을 오래된 순으로.
     * 부분 인덱스(idx_saga_instance_stalled)와 조건이 일치해 종료된 Saga는 스캔하지 않는다.
     */
    List<SagaInstanceJpaEntity> findStalled(Instant threshold, int limit) {
        return queryFactory
                .selectFrom(sagaInstanceJpaEntity)
                .where(sagaInstanceJpaEntity.state.in(
                                SagaState.AWAITING_INVENTORY,
                                SagaState.AWAITING_PAYMENT,
                                SagaState.COMPENSATING_INVENTORY)
                        .and(sagaInstanceJpaEntity.updatedAt.lt(threshold)))
                .orderBy(sagaInstanceJpaEntity.updatedAt.asc())
                .limit(limit)
                .fetch();
    }
}
