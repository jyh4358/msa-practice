package com.shopsaga.order.domain;

import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 주문 애그리거트 루트 — <b>순수 도메인</b>. 프레임워크 런타임 의존 없음(Lombok은 컴파일타임 전용).
 * Phase 1(모놀리스): 결제(Payment)를 이 애그리거트가 직접 보유하며 capturePayment()로 캡처+확정한다.
 */
@Getter
public class Order {

    /**
     * 가짜 결제 게이트웨이 stub: 합계가 .99로 끝나면 거절(롤백 시연용).
     * 진짜 주문 불변식이 아니라 외부 PG를 흉내낸 것 — Phase 2에서 원격 호출/별도 서비스로 빠져나갈 seam.
     * (양수 합계 전제 — OrderItem 불변식이 보장.)
     */
    private static final BigDecimal DECLINE_REMAINDER = new BigDecimal("0.99");

    private UUID id;
    private final UUID customerId;
    private OrderStatus status;
    private BigDecimal totalAmount;
    private final Instant createdAt;
    private final List<OrderItem> items;
    private Payment payment;

    private Order(UUID id, UUID customerId, OrderStatus status, BigDecimal totalAmount,
                  Instant createdAt, List<OrderItem> items, Payment payment) {
        this.id = id;
        this.customerId = customerId;
        this.status = status;
        this.totalAmount = totalAmount;
        this.createdAt = createdAt;
        this.items = items;
        this.payment = payment;
    }

    public static Order create(UUID customerId) {
        return new Order(null, customerId, OrderStatus.PENDING,
                BigDecimal.ZERO, Instant.now(), new ArrayList<>(), null);
    }

    public static Order restore(UUID id, UUID customerId, OrderStatus status, BigDecimal totalAmount,
                                Instant createdAt, List<OrderItem> items, Payment payment) {
        return new Order(id, customerId, status, totalAmount, createdAt, new ArrayList<>(items), payment);
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
     * 결제 캡처 + 주문 확정(모놀리스 in-process). 가짜 게이트웨이가 거절하면 PaymentDeclinedException →
     * 호출자의 @Transactional 전체가 롤백된다(재고 차감 포함).
     */
    public void capturePayment() {
        if (status != OrderStatus.PENDING) {
            throw new IllegalStateException("order is not PENDING: " + status);
        }
        if (items.isEmpty()) {
            throw new IllegalStateException("cannot capture payment on an empty order");
        }
        if (totalAmount.remainder(BigDecimal.ONE).compareTo(DECLINE_REMAINDER) == 0) {
            throw new PaymentDeclinedException(totalAmount);
        }
        this.payment = Payment.capture(totalAmount);
        this.status = OrderStatus.CONFIRMED;
    }

    /** 외부 변경 방지를 위해 불변 뷰를 반환 — Lombok @Getter 는 이미 존재하는 이 메서드를 덮어쓰지 않는다. */
    public List<OrderItem> getItems() {
        return Collections.unmodifiableList(items);
    }
}
