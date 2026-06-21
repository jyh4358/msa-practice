package com.shopsaga.order.adapter.out.persistence;

import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** 재고 조회 — QueryDSL. 예약(차감)용 조회는 비관적 쓰기 락(SELECT … FOR UPDATE)을 건다. */
@Repository
@RequiredArgsConstructor
class StockQueryRepository {

    private final JPAQueryFactory query;

    /** 비관적 쓰기 락으로 재고 행을 잠근 채 조회 — 읽기 시점부터 잠가 동시 차감의 lost-update를 막는다. */
    Optional<StockItemJpaEntity> findByProductIdForUpdate(UUID productId) {
        QStockItemJpaEntity stock = QStockItemJpaEntity.stockItemJpaEntity;
        StockItemJpaEntity result = query.selectFrom(stock)
                .where(stock.productId.eq(productId))
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .fetchOne();
        return Optional.ofNullable(result);
    }
}
