package com.shopsaga.order.adapter.out.persistence;

import com.shopsaga.order.domain.Order;
import com.shopsaga.order.domain.OrderItem;

import java.util.List;

/** 도메인 ↔ JPA 영속 모델 매핑(어댑터 내부). */
final class OrderMapper {

    private OrderMapper() {
    }

    static OrderJpaEntity toJpaEntity(Order order) {
        OrderJpaEntity entity = new OrderJpaEntity(
                order.getId(),
                order.getCustomerId(),
                order.getStatus(),
                order.getTotalAmount(),
                order.getCreatedAt(),
                order.getPaymentId()
        );
        order.getItems().forEach(i ->
                entity.addItem(i.getProductId(), i.getQuantity(), i.getUnitPrice()));
        return entity;
    }

    static Order toDomain(OrderJpaEntity entity) {
        List<OrderItem> items = entity.getItems().stream()
                .map(i -> new OrderItem(i.getProductId(), i.getQuantity(), i.getUnitPrice()))
                .toList();
        return Order.restore(
                entity.getId(),
                entity.getCustomerId(),
                entity.getStatus(),
                entity.getTotalAmount(),
                entity.getCreatedAt(),
                items,
                entity.getPaymentId()
        );
    }
}
