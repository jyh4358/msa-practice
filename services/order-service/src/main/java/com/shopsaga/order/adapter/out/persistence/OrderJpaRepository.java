package com.shopsaga.order.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * 기본 CRUD(저장 등)만 담당. 커스텀 조회(fetch join)는 QueryDSL(OrderQueryRepository)에서 처리한다
 * — 리포지토리 인터페이스에 JPQL @Query 를 두지 않는다.
 */
interface OrderJpaRepository extends JpaRepository<OrderJpaEntity, UUID> {
}
