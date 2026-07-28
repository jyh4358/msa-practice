package com.shopsaga.order.application.service;

import com.shopsaga.events.OrderCancelledEvent;
import com.shopsaga.events.OrderConfirmedEvent;
import com.shopsaga.events.commands.ChargePaymentCommand;
import com.shopsaga.events.commands.RefundPaymentCommand;
import com.shopsaga.events.commands.ReleaseStockCommand;
import com.shopsaga.events.commands.SagaReply;
import com.shopsaga.order.application.port.out.LoadOrderPort;
import com.shopsaga.order.application.port.out.ProcessedMessagePort;
import com.shopsaga.order.application.port.out.PublishOrderEventPort;
import com.shopsaga.order.application.port.out.PublishSagaCommandPort;
import com.shopsaga.order.application.port.out.SagaInstanceRepositoryPort;
import com.shopsaga.order.application.port.out.UpdateOrderPort;
import com.shopsaga.order.domain.Order;
import com.shopsaga.order.domain.OrderStatus;
import com.shopsaga.order.domain.saga.SagaInstance;
import com.shopsaga.order.domain.saga.SagaState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 13: 조정자의 <b>분기 로직</b> 검증 — Saga 전체 흐름을 한 테스트 클래스에서 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class SagaOrchestratorServiceTest {

    @Mock
    SagaInstanceRepositoryPort sagaRepository;
    @Mock
    LoadOrderPort loadOrderPort;
    @Mock
    UpdateOrderPort updateOrderPort;
    @Mock
    PublishSagaCommandPort publishSagaCommandPort;
    @Mock
    PublishOrderEventPort publishOrderEventPort;
    @Mock
    ProcessedMessagePort processedMessagePort;
    @InjectMocks
    SagaOrchestratorService service;

    @Captor
    ArgumentCaptor<SagaInstance> sagaCaptor;

    private static final Instant T0 = Instant.parse("2026-07-28T10:00:00Z");

    private Order order() {
        Order order = Order.create(UUID.randomUUID());
        order.addItem(UUID.randomUUID(), 2, new BigDecimal("10.00"));
        return order;
    }

    private SagaInstance sagaAt(SagaState state, UUID orderId) {
        return SagaInstance.restore(UUID.randomUUID(), orderId, state, T0, 0);
    }

    @Test
    void stockReserved_advancesOrderAndIssuesChargeCommand() {
        Order order = order();
        SagaInstance saga = sagaAt(SagaState.AWAITING_INVENTORY, order.getId());
        when(sagaRepository.findBySagaId(saga.getSagaId())).thenReturn(Optional.of(saga));
        when(loadOrderPort.loadById(order.getId())).thenReturn(Optional.of(order));

        service.onReply(UUID.randomUUID(), SagaReply.ok(
                saga.getSagaId(), order.getId(), SagaReply.Kind.STOCK_RESERVED, T0));

        assertThat(saga.getState()).isEqualTo(SagaState.AWAITING_PAYMENT);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.INVENTORY_RESERVED);
        // ★ 조정자가 다음 단계를 '지시'한다 — 코레오그래피에선 payment 가 스스로 시작했다.
        verify(publishSagaCommandPort).chargePayment(any(ChargePaymentCommand.class));
    }

    @Test
    void stockReservationFailed_cancelsImmediately_noCompensationNeeded() {
        Order order = order();
        SagaInstance saga = sagaAt(SagaState.AWAITING_INVENTORY, order.getId());
        when(sagaRepository.findBySagaId(saga.getSagaId())).thenReturn(Optional.of(saga));
        when(loadOrderPort.loadById(order.getId())).thenReturn(Optional.of(order));

        service.onReply(UUID.randomUUID(), SagaReply.failed(
                saga.getSagaId(), order.getId(), SagaReply.Kind.STOCK_RESERVATION_FAILED, "재고 부족", T0));

        assertThat(saga.getState()).isEqualTo(SagaState.CANCELLED);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(publishOrderEventPort).orderCancelled(any(OrderCancelledEvent.class));
        verify(publishSagaCommandPort, never()).releaseStock(any());   // 되돌릴 재고가 없다
    }

    @Test
    void paymentCharged_confirmsOrderAndCompletesSaga() {
        Order order = order();
        order.markInventoryReserved();
        SagaInstance saga = sagaAt(SagaState.AWAITING_PAYMENT, order.getId());
        UUID paymentId = UUID.randomUUID();
        when(sagaRepository.findBySagaId(saga.getSagaId())).thenReturn(Optional.of(saga));
        when(loadOrderPort.loadById(order.getId())).thenReturn(Optional.of(order));

        service.onReply(UUID.randomUUID(), new SagaReply(
                saga.getSagaId(), order.getId(), SagaReply.Kind.PAYMENT_CHARGED,
                paymentId, new BigDecimal("20.00"), null, T0));

        assertThat(saga.getState()).isEqualTo(SagaState.COMPLETED);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getPaymentId()).isEqualTo(paymentId);
        verify(publishOrderEventPort).orderConfirmed(any(OrderConfirmedEvent.class));
    }

    @Test
    void paymentDeclined_issuesReleaseCommandFirst_doesNotCancelYet() {
        Order order = order();
        order.markInventoryReserved();
        SagaInstance saga = sagaAt(SagaState.AWAITING_PAYMENT, order.getId());
        when(sagaRepository.findBySagaId(saga.getSagaId())).thenReturn(Optional.of(saga));

        service.onReply(UUID.randomUUID(), SagaReply.failed(
                saga.getSagaId(), order.getId(), SagaReply.Kind.PAYMENT_DECLINED, "거절", T0));

        // ★ 조정자는 보상 완료를 기다린다 — 코레오그래피에선 order 와 inventory 가 '동시에' 반응했다.
        assertThat(saga.getState()).isEqualTo(SagaState.COMPENSATING_INVENTORY);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.INVENTORY_RESERVED);   // 아직 취소 아님
        verify(publishSagaCommandPort).releaseStock(any(ReleaseStockCommand.class));
        verify(publishOrderEventPort, never()).orderCancelled(any());
    }

    @Test
    void stockReleased_finishesCompensationByCancellingOrder() {
        Order order = order();
        order.markInventoryReserved();
        SagaInstance saga = sagaAt(SagaState.COMPENSATING_INVENTORY, order.getId());
        when(sagaRepository.findBySagaId(saga.getSagaId())).thenReturn(Optional.of(saga));
        when(loadOrderPort.loadById(order.getId())).thenReturn(Optional.of(order));

        service.onReply(UUID.randomUUID(), SagaReply.ok(
                saga.getSagaId(), order.getId(), SagaReply.Kind.STOCK_RELEASED, T0));

        assertThat(saga.getState()).isEqualTo(SagaState.CANCELLED);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(publishOrderEventPort).orderCancelled(any(OrderCancelledEvent.class));
    }

    @Test
    void duplicateReply_isIgnored_noSideEffects() {
        UUID messageId = UUID.randomUUID();
        when(processedMessagePort.isAlreadyProcessed(messageId)).thenReturn(true);

        service.onReply(messageId, SagaReply.ok(
                UUID.randomUUID(), UUID.randomUUID(), SagaReply.Kind.PAYMENT_CHARGED, T0));

        verify(sagaRepository, never()).findBySagaId(any());
        verify(publishSagaCommandPort, never()).chargePayment(any());
        verify(processedMessagePort, never()).markProcessed(messageId);
    }

    @Test
    void lateReplyOnTerminalSaga_changesNothing() {
        Order order = order();
        SagaInstance saga = sagaAt(SagaState.CANCELLED, order.getId());
        when(sagaRepository.findBySagaId(saga.getSagaId())).thenReturn(Optional.of(saga));
        UUID messageId = UUID.randomUUID();

        // 이미 취소된 Saga에 결제 성공이 뒤늦게 도착(경합)
        service.onReply(messageId, new SagaReply(saga.getSagaId(), order.getId(),
                SagaReply.Kind.PAYMENT_CHARGED, UUID.randomUUID(), new BigDecimal("20.00"), null, T0));

        assertThat(saga.getState()).isEqualTo(SagaState.CANCELLED);
        verify(publishOrderEventPort, never()).orderConfirmed(any());
        verify(processedMessagePort).markProcessed(messageId);   // 처리 완료로는 기록(재시도 방지)
    }

    @Test
    void unknownSaga_isMarkedProcessed_toAvoidRetryLoop() {
        UUID messageId = UUID.randomUUID();
        UUID sagaId = UUID.randomUUID();
        when(sagaRepository.findBySagaId(sagaId)).thenReturn(Optional.empty());

        service.onReply(messageId, SagaReply.ok(sagaId, UUID.randomUUID(), SagaReply.Kind.STOCK_RESERVED, T0));

        verify(publishSagaCommandPort, never()).chargePayment(any());
        verify(processedMessagePort).markProcessed(messageId);
    }

    // ─────────── Phase 14: Phase 13이 남긴 '고아 결제' 회귀 가드 ───────────

    @Test
    void paymentChargedAfterSagaCancelled_issuesRefundCommand_insteadOfIgnoring() {
        Order order = order();
        // 타임아웃 sweep 이 포기해 이미 CANCELLED 로 끝난 Saga
        SagaInstance saga = sagaAt(SagaState.CANCELLED, order.getId());
        UUID paymentId = UUID.randomUUID();
        when(sagaRepository.findBySagaId(saga.getSagaId())).thenReturn(Optional.of(saga));

        // 되살아난 payment 가 큐에 남아 있던 ChargePayment 를 뒤늦게 수행 → 성공 리플라이가 도착
        service.onReply(UUID.randomUUID(), new SagaReply(saga.getSagaId(), order.getId(),
                SagaReply.Kind.PAYMENT_CHARGED, paymentId, new BigDecimal("20.00"), null, T0));

        ArgumentCaptor<RefundPaymentCommand> refund = ArgumentCaptor.forClass(RefundPaymentCommand.class);
        verify(publishSagaCommandPort).refundPayment(refund.capture());
        assertThat(refund.getValue().paymentId()).isEqualTo(paymentId);
        // 주문은 되살아나지 않는다 — 취소는 그대로 두고 결제만 상쇄한다.
        verify(updateOrderPort, never()).update(any());
        verify(publishOrderEventPort, never()).orderConfirmed(any());
    }

    @Test
    void paymentRefundedReply_isRecordedOnly_withoutStateChange() {
        Order order = order();
        SagaInstance saga = sagaAt(SagaState.CANCELLED, order.getId());
        when(sagaRepository.findBySagaId(saga.getSagaId())).thenReturn(Optional.of(saga));

        service.onReply(UUID.randomUUID(), new SagaReply(saga.getSagaId(), order.getId(),
                SagaReply.Kind.PAYMENT_REFUNDED, UUID.randomUUID(), null, null, T0));

        verify(sagaRepository, never()).update(any());
        verify(publishSagaCommandPort, never()).refundPayment(any());
        assertThat(saga.getState()).isEqualTo(SagaState.CANCELLED);
    }
}
