package com.shopsaga.order.application.port.in;

import com.shopsaga.order.domain.Order;
import com.shopsaga.order.domain.OrderStatus;
import com.shopsaga.order.domain.Payment;
import com.shopsaga.order.domain.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 인바운드 포트의 출력 모델(불변 read model).
 * 유스케이스/쿼리는 가변 도메인 애그리거트(Order) 대신 이 뷰를 반환한다 → 도메인이 어댑터로 새지 않는다.
 */
public record OrderView(
        UUID id,
        UUID customerId,
        OrderStatus status,
        BigDecimal totalAmount,
        Instant createdAt,
        List<Item> items,
        PaymentView payment
) {
    public record Item(UUID productId, int quantity, BigDecimal unitPrice) {
    }

    public record PaymentView(BigDecimal amount, PaymentStatus status, Instant capturedAt) {
    }

    /** 도메인 → 뷰 매핑(애플리케이션 계층 책임). */
    public static OrderView from(Order order) {
        List<Item> items = order.getItems().stream()
                .map(i -> new Item(i.getProductId(), i.getQuantity(), i.getUnitPrice()))
                .toList();
        Payment payment = order.getPayment();
        PaymentView paymentView = payment == null ? null
                : new PaymentView(payment.getAmount(), payment.getStatus(), payment.getCapturedAt());
        return new OrderView(order.getId(), order.getCustomerId(), order.getStatus(),
                order.getTotalAmount(), order.getCreatedAt(), items, paymentView);
    }
}
