package com.shopsaga.order.application.port.in;

import com.shopsaga.order.domain.Order;
import com.shopsaga.order.domain.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 인바운드 포트의 출력 모델(불변 read model). 도메인 애그리거트가 어댑터로 새지 않도록 한다.
 * Phase 2: 결제는 payment-service 소유 → paymentId(참조)만 노출.
 */
public record OrderView(
        UUID id,
        UUID customerId,
        OrderStatus status,
        BigDecimal totalAmount,
        Instant createdAt,
        List<Item> items,
        UUID paymentId
) {
    public record Item(UUID productId, int quantity, BigDecimal unitPrice) {
    }

    public static OrderView from(Order order) {
        List<Item> items = order.getItems().stream()
                .map(i -> new Item(i.getProductId(), i.getQuantity(), i.getUnitPrice()))
                .toList();
        return new OrderView(order.getId(), order.getCustomerId(), order.getStatus(),
                order.getTotalAmount(), order.getCreatedAt(), items, order.getPaymentId());
    }
}
