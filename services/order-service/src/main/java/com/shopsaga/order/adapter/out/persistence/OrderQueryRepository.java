package com.shopsaga.order.adapter.out.persistence;

import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 주문 조회 — QueryDSL(타입세이프). 리포지토리에 JPQL 문자열을 두지 않는다.
 * items(@OneToMany)는 fetch join + distinct 로 루트 중복 제거.
 */
@Repository
@RequiredArgsConstructor
class OrderQueryRepository {

    private final JPAQueryFactory query;

    Optional<OrderJpaEntity> findByIdWithDetails(UUID id) {
        QOrderJpaEntity order = QOrderJpaEntity.orderJpaEntity;
        OrderJpaEntity result = query.selectFrom(order)
                .distinct()
                .leftJoin(order.items).fetchJoin()
                .where(order.id.eq(id))
                .fetchOne();
        return Optional.ofNullable(result);
    }

    List<OrderJpaEntity> findAllWithDetails() {
        QOrderJpaEntity order = QOrderJpaEntity.orderJpaEntity;
        return query.selectFrom(order)
                .distinct()
                .leftJoin(order.items).fetchJoin()
                .fetch();
    }
}
