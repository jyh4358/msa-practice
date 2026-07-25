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
 * 식별자는 <b>앱에서 생성</b>(create 시점) — 이벤트에 orderId 를 실어 보내려면 저장 전에 id가 필요하기 때문.
 *
 * <p><b>Phase 12(Saga): 주문이 상태 기계가 된다.</b> 더 이상 한 번의 요청으로 확정되지 않고,
 * 다른 서비스가 보내오는 이벤트에 따라 단계적으로 전이한다:
 * <pre>
 *   PENDING ──(InventoryReserved)──▶ INVENTORY_RESERVED ──(PaymentCharged)──▶ CONFIRMED
 *      │                                    │
 *      └──(InventoryFailed)──┐              └──(PaymentDeclined)──┐
 *                            ▼                                     ▼
 *                        CANCELLED  ◀───────────────────────────────┘
 * </pre>
 *
 * <p>전이 메서드는 모두 <b>멱등</b>하다 — 같은 이벤트가 두 번 배달돼도(at-least-once) 예외를 던지지 않고
 * 아무 일도 하지 않는다. 분산 환경에서 재배달은 정상이므로, 도메인이 이를 견뎌야 한다.
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
     * Saga 1단계 성공: 재고가 예약됐다. PENDING 에서만 전이한다.
     *
     * @return 실제로 전이했으면 true, 이미 그 상태거나 종료 상태라 무시했으면 false(멱등)
     */
    public boolean markInventoryReserved() {
        if (status != OrderStatus.PENDING) {
            return false;   // 재배달·순서 뒤바뀜·이미 취소됨 → 무시
        }
        this.status = OrderStatus.INVENTORY_RESERVED;
        return true;
    }

    /**
     * Saga 2단계 성공: 결제가 됐다 → 주문 확정. 재고 예약을 거친 주문만 확정할 수 있다.
     *
     * @return 실제로 확정했으면 true, 이미 확정/취소됐으면 false(멱등)
     */
    public boolean confirm(UUID paymentId) {
        if (paymentId == null) {
            throw new IllegalArgumentException("paymentId must not be null");
        }
        if (status == OrderStatus.CONFIRMED) {
            return false;   // 재배달 → 이미 확정
        }
        if (status != OrderStatus.INVENTORY_RESERVED) {
            // PENDING(재고 예약 이벤트를 아직 못 봄) 또는 CANCELLED — 확정하지 않는다.
            return false;
        }
        if (items.isEmpty()) {
            throw new IllegalStateException("cannot confirm an empty order");
        }
        this.paymentId = paymentId;
        this.status = OrderStatus.CONFIRMED;
        return true;
    }

    /**
     * Saga 실패: 주문 취소. 재고 부족(짧은 보상)·결제 거절(긴 보상) 모두 여기로 수렴한다.
     *
     * <p>이미 CONFIRMED 된 주문은 취소하지 않는다 — 그건 환불(PaymentRefunded) 이라는 <b>다른 보상</b>의 영역이고,
     * 이 Phase의 범위가 아니다(§한계표).
     *
     * @return 실제로 취소했으면 true, 이미 취소됐거나 확정된 주문이면 false(멱등)
     */
    public boolean cancel() {
        if (status == OrderStatus.CANCELLED || status == OrderStatus.CONFIRMED) {
            return false;
        }
        this.status = OrderStatus.CANCELLED;
        return true;
    }

    /** 외부 변경 방지를 위해 불변 뷰를 반환 — Lombok @Getter 는 이 메서드를 덮어쓰지 않는다. */
    public List<OrderItem> getItems() {
        return Collections.unmodifiableList(items);
    }
}
