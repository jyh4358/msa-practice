package com.shopsaga.order.adapter.out.persistence;

import com.shopsaga.order.application.port.out.LoadOrderPort;
import com.shopsaga.order.application.port.out.SaveOrderPort;
import com.shopsaga.order.domain.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 아웃바운드 영속 어댑터: 애플리케이션의 SaveOrderPort/LoadOrderPort를 구현한다.
 * 도메인 ↔ JPA 매핑을 어댑터 안에서 끝내므로, 애플리케이션·웹 계층은 LAZY 로딩을 신경 쓸 필요가 없다.
 */
@Component
class OrderPersistenceAdapter implements SaveOrderPort, LoadOrderPort {

    private final OrderJpaRepository repository;

    OrderPersistenceAdapter(OrderJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Order save(Order order) {
        // Phase 0: 신규 주문 INSERT 전용 — 도메인 id=null 이라 @GeneratedValue 가 식별자를 부여한다.
        // TODO(Phase 12/13): 기존 애그리거트의 상태 전이(PENDING→PAID 등) 저장 시에는 id!=null 이므로
        //   load-then-mutate(관리 엔티티를 찾아 필드 갱신 → dirty checking 으로 UPDATE)가 필요하다.
        //   지금처럼 새 엔티티를 만들어 save 하면 새 행이 INSERT 된다. (docs/HEXAGONAL.md §영속 규칙)
        OrderJpaEntity saved = repository.save(OrderMapper.toJpaEntity(order));
        return OrderMapper.toDomain(saved);
    }

    @Override
    public Optional<Order> loadById(UUID id) {
        return repository.findWithItemsById(id).map(OrderMapper::toDomain);
    }

    @Override
    public List<Order> loadAll() {
        return repository.findAllWithItems().stream().map(OrderMapper::toDomain).toList();
    }
}
