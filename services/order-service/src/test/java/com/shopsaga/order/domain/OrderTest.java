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
    void confirm_setsConfirmedAndPaymentId() {
        Order order = Order.create(UUID.randomUUID());
        order.addItem(UUID.randomUUID(), 1, new BigDecimal("10.00"));
        UUID paymentId = UUID.randomUUID();

        order.confirm(paymentId);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getPaymentId()).isEqualTo(paymentId);
    }
}
