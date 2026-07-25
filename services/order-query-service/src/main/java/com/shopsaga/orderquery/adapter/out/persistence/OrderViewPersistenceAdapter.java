package com.shopsaga.orderquery.adapter.out.persistence;

import com.shopsaga.orderquery.application.port.out.OrderViewRepositoryPort;
import com.shopsaga.orderquery.domain.OrderView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 아웃바운드 어댑터: 읽기 모델을 MongoDB 문서로 저장/조회하고 도메인 객체로 되돌린다. */
@Component
@RequiredArgsConstructor
class OrderViewPersistenceAdapter implements OrderViewRepositoryPort {

    private final OrderViewMongoRepository repository;

    @Override
    public void save(OrderView view) {
        // @Id(orderId)가 이미 있는 값이면 문서 전체를 덮어쓴다(replace) → 재투영/리플레이에 멱등.
        repository.save(toDocument(view));
    }

    @Override
    public Optional<OrderView> findByOrderId(UUID orderId) {
        return repository.findById(orderId).map(this::toDomain);
    }

    @Override
    public List<OrderView> findByCustomerId(UUID customerId) {
        return repository.findByCustomerIdOrderByPlacedAtDesc(customerId).stream()
                .map(this::toDomain)
                .toList();
    }

    private OrderViewDocument toDocument(OrderView view) {
        List<OrderViewDocument.LineDocument> lines = view.getLines().stream()
                .map(l -> new OrderViewDocument.LineDocument(
                        l.getProductId(), l.getQuantity(), l.getUnitPrice(), l.getLineTotal()))
                .toList();
        return new OrderViewDocument(view.getOrderId(), view.getCustomerId(), view.getStatus(),
                view.getTotalAmount(), view.getPlacedAt(), lines);
    }

    private OrderView toDomain(OrderViewDocument doc) {
        List<OrderView.Line> lines = doc.getLines().stream()
                .map(l -> new OrderView.Line(l.getProductId(), l.getQuantity(), l.getUnitPrice()))
                .toList();
        return new OrderView(doc.getOrderId(), doc.getCustomerId(), doc.getStatus(),
                doc.getTotalAmount(), doc.getPlacedAt(), lines);
    }
}
