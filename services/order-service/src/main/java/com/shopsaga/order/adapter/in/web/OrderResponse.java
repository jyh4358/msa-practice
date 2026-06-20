package com.shopsaga.order.adapter.in.web;

import com.shopsaga.order.domain.Order;
import com.shopsaga.order.domain.OrderStatus;
import com.shopsaga.order.domain.Payment;
import com.shopsaga.order.domain.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 웹 어댑터의 출력 DTO. 도메인 Order → 응답 변환. */
public record OrderResponse(
        UUID id,
        UUID customerId,
        OrderStatus status,
        BigDecimal totalAmount,
        Instant createdAt,
        List<Line> items,
        PaymentView payment
) {
    public record Line(UUID productId, int quantity, BigDecimal unitPrice) {
    }

    public record PaymentView(BigDecimal amount, PaymentStatus status, Instant capturedAt) {
    }

    public static OrderResponse from(Order order) {
        List<Line> lines = order.getItems().stream()
                .map(i -> new Line(i.getProductId(), i.getQuantity(), i.getUnitPrice()))
                .toList();
        Payment payment = order.getPayment();
        PaymentView paymentView = payment == null ? null
                : new PaymentView(payment.getAmount(), payment.getStatus(), payment.getCapturedAt());
        return new OrderResponse(
                order.getId(),
                order.getCustomerId(),
                order.getStatus(),
                order.getTotalAmount(),
                order.getCreatedAt(),
                lines,
                paymentView
        );
    }
}
