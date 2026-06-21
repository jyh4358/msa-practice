package com.shopsaga.order.domain;

import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 주문 애그리거트 루트 — <b>순수 도메인</b>.
 * Phase 2: 결제는 payment-service가 소유한다. 주문은 결제 결과를 paymentId(참조)로만 보관한다.
 * 식별자는 <b>앱에서 생성</b>(create 시점) — 원격 결제 호출에 orderId를 넘기려면 저장 전에 id가 필요하기 때문.
 */
@Getter
public class Order {

    private final UUID id;
    private final UUID customerId;
    private OrderStatus status;
    private BigDecimal totalAmount;
    private final Instant createdAt;
    private final List<OrderItem> items;
    private UUID paymentId;

    private Order(UUID id, UUID customerId, OrderStatus status, BigDecimal totalAmount,
                  Instant createdAt, List<OrderItem> items, UUID paymentId) {
        this.id = id;
        this.customerId = customerId;
        this.status = status;
        this.totalAmount = totalAmount;
        this.createdAt = createdAt;
        this.items = items;
        this.paymentId = paymentId;
    }

    public static Order create(UUID customerId) {
        return new Order(UUID.randomUUID(), customerId, OrderStatus.PENDING,
                BigDecimal.ZERO, Instant.now(), new ArrayList<>(), null);
    }

    public static Order restore(UUID id, UUID customerId, OrderStatus status, BigDecimal totalAmount,
                                Instant createdAt, List<OrderItem> items, UUID paymentId) {
        return new Order(id, customerId, status, totalAmount, createdAt, new ArrayList<>(items), paymentId);
    }

    public void addItem(UUID productId, int quantity, BigDecimal unitPrice) {
        items.add(new OrderItem(productId, quantity, unitPrice));
        recalculateTotal();
    }

    private void recalculateTotal() {
        this.totalAmount = items.stream()
                .map(OrderItem::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 원격 결제 성공 후 주문 확정. paymentId(payment-service가 발급한 결제 식별자)를 보관한다.
     * (결제 자체는 더 이상 이 애그리거트가 하지 않는다 — payment-service의 책임.)
     */
    public void confirm(UUID paymentId) {
        if (status != OrderStatus.PENDING) {
            throw new IllegalStateException("order is not PENDING: " + status);
        }
        if (items.isEmpty()) {
            throw new IllegalStateException("cannot confirm an empty order");
        }
        if (paymentId == null) {
            throw new IllegalArgumentException("paymentId must not be null");
        }
        this.paymentId = paymentId;
        this.status = OrderStatus.CONFIRMED;
    }

    /** 외부 변경 방지를 위해 불변 뷰를 반환 — Lombok @Getter 는 이 메서드를 덮어쓰지 않는다. */
    public List<OrderItem> getItems() {
        return Collections.unmodifiableList(items);
    }
}
