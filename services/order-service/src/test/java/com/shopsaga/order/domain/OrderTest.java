package com.shopsaga.order.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 순수 도메인 단위 테스트 — DB/Spring 컨텍스트 불필요. 헥사고날에서 도메인은 프레임워크 없이 테스트된다.
 */
class OrderTest {

    @Test
    void totalAmount_isSumOfLineTotals() {
        Order order = Order.create(UUID.randomUUID());

        order.addItem(UUID.randomUUID(), 2, new BigDecimal("10.00")); // 20.00
        order.addItem(UUID.randomUUID(), 1, new BigDecimal("5.50"));  //  5.50

        assertThat(order.getTotalAmount()).isEqualByComparingTo("25.50");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getItems()).hasSize(2);
        assertThat(order.getId()).isNotNull();       // Phase 2: id는 앱에서 생성
        assertThat(order.getPaymentId()).isNull();   // 아직 결제 전
    }

    @Test
    void addItem_enforcesDomainInvariants() {
        Order order = Order.create(UUID.randomUUID());

        assertThatThrownBy(() -> order.addItem(UUID.randomUUID(), 0, new BigDecimal("1.00")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> order.addItem(UUID.randomUUID(), 1, new BigDecimal("0.00")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> order.addItem(UUID.randomUUID(), 1, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sagaHappyPath_pendingToReservedToConfirmed() {
        Order order = orderWithOneItem();
        UUID paymentId = UUID.randomUUID();

        assertThat(order.markInventoryReserved()).isTrue();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.INVENTORY_RESERVED);

        assertThat(order.confirm(paymentId)).isTrue();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getPaymentId()).isEqualTo(paymentId);
    }

    @Test
    void confirm_acceptsOutOfOrderPaymentCharged_monotonicTransition() {
        Order order = orderWithOneItem();   // PENDING — 재고 예약 이벤트를 아직 못 봤다
        UUID paymentId = UUID.randomUUID();

        // 순서가 뒤바뀐 배달: PaymentCharged 는 인과적으로 재고 예약 '뒤'의 사건이므로,
        // 먼저 도착해도 받아들인다(단조 전이). 무시하면 전이가 영영 사라져 주문이
        // "결제됐는데 미확정"으로 남는다 — 읽기 모델의 rank 와 같은 원리.
        assertThat(order.confirm(paymentId)).isTrue();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getPaymentId()).isEqualTo(paymentId);

        // 늦게 도착한 InventoryReserved 는 상태를 되돌리지 않는다
        assertThat(order.markInventoryReserved()).isFalse();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void confirm_withoutPaymentId_isIgnored_notAnException() {
        Order order = orderWithOneItem();
        order.markInventoryReserved();

        // 결제 id 없는 확정 지시: 예외를 던지면 트랜잭션 롤백 → 재시도 → DLT 로 파티션이 오염된다.
        // 다른 가드처럼 "전이 안 함"으로 다룬다.
        assertThat(order.confirm(null)).isFalse();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.INVENTORY_RESERVED);
        assertThat(order.getPaymentId()).isNull();
    }

    @Test
    void transitions_areIdempotent_forRedelivery() {
        Order order = orderWithOneItem();
        UUID paymentId = UUID.randomUUID();
        order.markInventoryReserved();
        order.confirm(paymentId);

        // 같은 이벤트가 두 번 배달돼도(at-least-once) 상태가 흔들리지 않는다
        assertThat(order.markInventoryReserved()).isFalse();
        assertThat(order.confirm(paymentId)).isFalse();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getPaymentId()).isEqualTo(paymentId);
    }

    @Test
    void cancel_worksFromPendingAndReserved_butNotAfterConfirmed() {
        // 짧은 보상: 재고 실패 → PENDING 에서 바로 취소
        Order fromPending = orderWithOneItem();
        assertThat(fromPending.cancel()).isTrue();
        assertThat(fromPending.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(fromPending.cancel()).isFalse();   // 멱등

        // 긴 보상: 결제 거절 → 재고 예약 상태에서 취소
        Order fromReserved = orderWithOneItem();
        fromReserved.markInventoryReserved();
        assertThat(fromReserved.cancel()).isTrue();
        assertThat(fromReserved.getStatus()).isEqualTo(OrderStatus.CANCELLED);

        // 확정된 주문은 취소하지 않는다(환불이라는 다른 보상의 영역 — Phase 12 범위 밖)
        Order confirmed = orderWithOneItem();
        confirmed.markInventoryReserved();
        confirmed.confirm(UUID.randomUUID());
        assertThat(confirmed.cancel()).isFalse();
        assertThat(confirmed.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void cancelledOrder_cannotBeReservedOrConfirmed() {
        Order order = orderWithOneItem();
        order.cancel();

        // 취소 후 늦게 도착한 이벤트들 — 종료 상태를 되살리지 않는다
        assertThat(order.markInventoryReserved()).isFalse();
        assertThat(order.confirm(UUID.randomUUID())).isFalse();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    private Order orderWithOneItem() {
        Order order = Order.create(UUID.randomUUID());
        order.addItem(UUID.randomUUID(), 1, new BigDecimal("10.00"));
        return order;
    }
}
