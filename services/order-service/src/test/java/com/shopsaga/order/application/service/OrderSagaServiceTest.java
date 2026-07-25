package com.shopsaga.order.application.service;

import com.shopsaga.events.InventoryFailedEvent;
import com.shopsaga.events.InventoryReservedEvent;
import com.shopsaga.events.OrderCancelledEvent;
import com.shopsaga.events.OrderConfirmedEvent;
import com.shopsaga.events.PaymentChargedEvent;
import com.shopsaga.events.PaymentDeclinedEvent;
import com.shopsaga.order.application.port.out.LoadOrderPort;
import com.shopsaga.order.application.port.out.ProcessedMessagePort;
import com.shopsaga.order.application.port.out.PublishOrderEventPort;
import com.shopsaga.order.application.port.out.UpdateOrderPort;
import com.shopsaga.order.domain.Order;
import com.shopsaga.order.domain.OrderStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 12: 주문의 Saga 반응 검증 — 해피패스 확정, 두 가지 보상 경로, 그리고 멱등성.
 */
@ExtendWith(MockitoExtension.class)
class OrderSagaServiceTest {

    @Mock
    LoadOrderPort loadOrderPort;
    @Mock
    UpdateOrderPort updateOrderPort;
    @Mock
    PublishOrderEventPort publishOrderEventPort;
    @Mock
    ProcessedMessagePort processedMessagePort;
    @InjectMocks
    OrderSagaService service;

    private static final Instant NOW = Instant.parse("2026-07-18T10:00:00Z");

    private Order pendingOrder() {
        Order order = Order.create(UUID.randomUUID());
        order.addItem(UUID.randomUUID(), 1, new BigDecimal("10.00"));
        return order;
    }

    @Test
    void inventoryReserved_advancesStatus_withoutPublishingAnything() {
        Order order = pendingOrder();
        when(loadOrderPort.loadById(order.getId())).thenReturn(Optional.of(order));
        UUID messageId = UUID.randomUUID();

        service.onInventoryReserved(messageId, new InventoryReservedEvent(
                order.getId(), order.getCustomerId(), new BigDecimal("10.00"), NOW));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.INVENTORY_RESERVED);
        verify(updateOrderPort).update(order);
        // 다음 단계(결제)는 payment 가 같은 InventoryReserved 를 듣고 스스로 한다 — order 는 명령하지 않는다.
        verify(publishOrderEventPort, never()).orderConfirmed(any());
        verify(publishOrderEventPort, never()).orderCancelled(any());
        verify(processedMessagePort).markProcessed(messageId);
    }

    @Test
    void paymentCharged_confirmsOrderAndPublishesConfirmed() {
        Order order = pendingOrder();
        order.markInventoryReserved();
        when(loadOrderPort.loadById(order.getId())).thenReturn(Optional.of(order));
        UUID paymentId = UUID.randomUUID();

        service.onPaymentCharged(UUID.randomUUID(), new PaymentChargedEvent(
                order.getId(), paymentId, new BigDecimal("10.00"), NOW));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getPaymentId()).isEqualTo(paymentId);
        verify(updateOrderPort).update(order);
        verify(publishOrderEventPort).orderConfirmed(any(OrderConfirmedEvent.class));
    }

    @Test
    void inventoryFailed_cancelsOrder_shortCompensation() {
        Order order = pendingOrder();   // 결제 전이므로 되돌릴 것이 없다
        when(loadOrderPort.loadById(order.getId())).thenReturn(Optional.of(order));

        service.onInventoryFailed(UUID.randomUUID(),
                new InventoryFailedEvent(order.getId(), "재고 부족", NOW));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(publishOrderEventPort).orderCancelled(any(OrderCancelledEvent.class));
    }

    @Test
    void paymentDeclined_cancelsOrder_longCompensation() {
        Order order = pendingOrder();
        order.markInventoryReserved();   // 재고를 잡은 상태 → inventory 가 별도로 해제(보상)한다
        when(loadOrderPort.loadById(order.getId())).thenReturn(Optional.of(order));

        service.onPaymentDeclined(UUID.randomUUID(), new PaymentDeclinedEvent(
                order.getId(), new BigDecimal("10.99"), "결제 거절", NOW));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(publishOrderEventPort).orderCancelled(any(OrderCancelledEvent.class));
    }

    @Test
    void duplicateDelivery_isIgnored_noSideEffects() {
        UUID messageId = UUID.randomUUID();
        when(processedMessagePort.isAlreadyProcessed(messageId)).thenReturn(true);

        service.onPaymentCharged(messageId, new PaymentChargedEvent(
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), NOW));

        verify(loadOrderPort, never()).loadById(any());
        verify(updateOrderPort, never()).update(any());
        verify(publishOrderEventPort, never()).orderConfirmed(any());
        verify(processedMessagePort, never()).markProcessed(messageId);   // 이미 기록돼 있다
    }

    @Test
    void alreadyCancelledOrder_doesNotGetConfirmed_andPublishesNothing() {
        Order order = pendingOrder();
        order.cancel();   // 재고 실패로 이미 취소됨
        when(loadOrderPort.loadById(order.getId())).thenReturn(Optional.of(order));
        UUID messageId = UUID.randomUUID();

        // 뒤늦게 결제 성공이 도착(경합) → 도메인이 전이를 거부하므로 아무 이벤트도 나가지 않는다
        service.onPaymentCharged(messageId, new PaymentChargedEvent(
                order.getId(), UUID.randomUUID(), new BigDecimal("10.00"), NOW));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(updateOrderPort, never()).update(any());
        verify(publishOrderEventPort, never()).orderConfirmed(any());
        verify(processedMessagePort, times(1)).markProcessed(messageId);   // 처리 완료로는 기록(재시도 방지)
    }

    @Test
    void unknownOrder_isMarkedProcessed_toAvoidRetryLoop() {
        UUID orderId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        when(loadOrderPort.loadById(orderId)).thenReturn(Optional.empty());

        service.onPaymentCharged(messageId, new PaymentChargedEvent(
                orderId, UUID.randomUUID(), new BigDecimal("10.00"), NOW));

        verify(updateOrderPort, never()).update(any());
        verify(processedMessagePort).markProcessed(messageId);
    }
}
