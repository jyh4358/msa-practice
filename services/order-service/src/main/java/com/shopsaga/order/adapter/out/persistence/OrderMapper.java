package com.shopsaga.order.adapter.out.persistence;

import com.shopsaga.order.domain.Order;
import com.shopsaga.order.domain.OrderItem;
import com.shopsaga.order.domain.Payment;

import java.util.List;

/** 도메인 ↔ JPA 영속 모델 매핑(어댑터 내부). */
final class OrderMapper {

    private OrderMapper() {
    }

    static OrderJpaEntity toJpaEntity(Order order) {
        OrderJpaEntity entity = new OrderJpaEntity(
                order.getCustomerId(),
                order.getStatus(),
                order.getTotalAmount(),
                order.getCreatedAt()
        );
        order.getItems().forEach(i ->
                entity.addItem(i.getProductId(), i.getQuantity(), i.getUnitPrice()));
        Payment payment = order.getPayment();
        if (payment != null) {
            entity.setPayment(payment.getAmount(), payment.getStatus(), payment.getCapturedAt());
        }
        return entity;
    }

    static Order toDomain(OrderJpaEntity entity) {
        List<OrderItem> items = entity.getItems().stream()
                .map(i -> new OrderItem(i.getProductId(), i.getQuantity(), i.getUnitPrice()))
                .toList();
        PaymentJpaEntity paymentEntity = entity.getPayment();
        Payment payment = paymentEntity == null ? null
                : Payment.restore(paymentEntity.getAmount(), paymentEntity.getStatus(), paymentEntity.getCapturedAt());
        return Order.restore(
                entity.getId(),
                entity.getCustomerId(),
                entity.getStatus(),
                entity.getTotalAmount(),
                entity.getCreatedAt(),
                items,
                payment
        );
    }
}
