package com.shopsaga.order.adapter.out.persistence;

import com.shopsaga.order.application.port.out.LoadOrderPort;
import com.shopsaga.order.application.port.out.SaveOrderPort;
import com.shopsaga.order.application.port.out.UpdateOrderPort;
import com.shopsaga.order.application.service.OrderNotFoundException;
import com.shopsaga.order.domain.Order;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 아웃바운드 영속 어댑터: 주문 저장/조회/상태전이.
 * 저장은 Spring Data(JpaRepository), 조회는 QueryDSL(OrderQueryRepository)로 fetch join.
 */
@Component
@RequiredArgsConstructor
class OrderPersistenceAdapter implements SaveOrderPort, LoadOrderPort, UpdateOrderPort {

    private final OrderJpaRepository repository;
    private final OrderQueryRepository queryRepository;

    @Override
    public Order save(Order order) {
        // 신규 주문 INSERT. id는 도메인이 생성한 값을 쓰므로 save()는 merge 경로(신규는 SELECT 후 INSERT).
        OrderJpaEntity saved = repository.save(OrderMapper.toJpaEntity(order));
        return OrderMapper.toDomain(saved);
    }

    @Override
    public void update(Order order) {
        // Phase 12: load-then-mutate — managed 엔티티를 불러와 상태 필드만 바꾼다(자식 컬렉션 무손상).
        OrderJpaEntity managed = repository.findById(order.getId())
                .orElseThrow(() -> new OrderNotFoundException(order.getId()));
        managed.applyTransition(order.getStatus(), order.getPaymentId());
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
