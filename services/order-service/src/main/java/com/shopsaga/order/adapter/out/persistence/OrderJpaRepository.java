package com.shopsaga.order.adapter.out.persistence;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Spring Data JPA 리포지토리(어댑터 내부). 웹이 렌더링하는 연관(items·payment)은 즉시 로딩한다(HEXAGONAL.md §3.4). */
interface OrderJpaRepository extends JpaRepository<OrderJpaEntity, UUID> {

    // 단건: items + payment 즉시 로딩.
    @EntityGraph(attributePaths = {"items", "payment"})
    Optional<OrderJpaEntity> findWithItemsById(UUID id);

    // 목록: items(@OneToMany)는 join fetch 시 카테시안 곱 → distinct 로 루트 중복 제거.
    // payment(@OneToOne)는 곱이 없으므로 함께 join fetch 해도 안전(추가 @OneToMany fetch는 금지).
    @Query("select distinct o from OrderJpaEntity o left join fetch o.items left join fetch o.payment")
    List<OrderJpaEntity> findAllWithItems();
}
