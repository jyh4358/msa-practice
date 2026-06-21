package com.shopsaga.order.adapter.out.persistence;

import com.shopsaga.order.application.port.out.LoadOrderPort;
import com.shopsaga.order.application.port.out.SaveOrderPort;
import com.shopsaga.order.domain.Order;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 아웃바운드 영속 어댑터: SaveOrderPort/LoadOrderPort 구현.
 * 저장은 Spring Data(JpaRepository), 조회는 QueryDSL(OrderQueryRepository)로 fetch join.
 */
@Component
@RequiredArgsConstructor
class OrderPersistenceAdapter implements SaveOrderPort, LoadOrderPort {

    private final OrderJpaRepository repository;
    private final OrderQueryRepository queryRepository;

    @Override
    public Order save(Order order) {
        // Phase 0/1: 신규 주문 INSERT 전용 — 도메인 id=null 이라 @GeneratedValue 가 식별자를 부여한다.
        // (상태 전이 UPDATE 는 Phase 12/13에서 load-then-mutate 로 — docs/HEXAGONAL.md §3.3)
        OrderJpaEntity saved = repository.save(OrderMapper.toJpaEntity(order));
        return OrderMapper.toDomain(saved);
    }

    @Override
    public Optional<Order> loadById(UUID id) {
        return queryRepository.findByIdWithDetails(id).map(OrderMapper::toDomain);
    }

    @Override
    public List<Order> loadAll() {
        return queryRepository.findAllWithDetails().stream().map(OrderMapper::toDomain).toList();
    }
}
