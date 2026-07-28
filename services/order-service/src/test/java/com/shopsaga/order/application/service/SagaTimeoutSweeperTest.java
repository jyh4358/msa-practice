package com.shopsaga.order.application.service;

import com.shopsaga.events.OrderCancelledEvent;
import com.shopsaga.events.commands.ChargePaymentCommand;
import com.shopsaga.events.commands.ReleaseStockCommand;
import com.shopsaga.events.commands.ReserveStockCommand;
import com.shopsaga.order.application.port.out.LoadOrderPort;
import com.shopsaga.order.application.port.out.PublishOrderEventPort;
import com.shopsaga.order.application.port.out.PublishSagaCommandPort;
import com.shopsaga.order.application.port.out.SagaInstanceRepositoryPort;
import com.shopsaga.order.application.port.out.UpdateOrderPort;
import com.shopsaga.order.domain.Order;
import com.shopsaga.order.domain.OrderStatus;
import com.shopsaga.order.domain.saga.SagaInstance;
import com.shopsaga.order.domain.saga.SagaState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 13: 타임아웃 sweep 검증 — <b>Phase 12가 못 하던 일</b>.
 *
 * <p>코레오그래피에서는 payment가 죽어 있으면 주문이 {@code INVENTORY_RESERVED} 로 영원히 남았다.
 * 조정자는 각 Saga의 마지막 전이 시각을 알기에 개입할 수 있다: 먼저 재촉(재전송), 그래도 안 되면 종료.
 */
@ExtendWith(MockitoExtension.class)
class SagaTimeoutSweeperTest {

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
    @InjectMocks
    SagaTimeoutSweeper sweeper;

    private static final Instant T0 = Instant.parse("2026-07-28T10:00:00Z");

    @BeforeEach
    void setDeadlines() {
        // @Value 필드는 단위 테스트에서 주입되지 않으므로 직접 넣는다.
        ReflectionTestUtils.setField(sweeper, "deadline", Duration.ofSeconds(15));
        ReflectionTestUtils.setField(sweeper, "maxAttempts", 3);
    }

    private Order order() {
        Order o = Order.create(UUID.randomUUID());
        o.addItem(UUID.randomUUID(), 2, new BigDecimal("10.00"));
        return o;
    }

    private void stalled(SagaInstance saga) {
        when(sagaRepository.findStalled(any(), any(), anyInt())).thenReturn(List.of(saga));
    }

    @Test
    void stalledInventoryStep_resendsReserveCommand() {
        Order order = order();
        SagaInstance saga = SagaInstance.restore(UUID.randomUUID(), order.getId(),
                SagaState.AWAITING_INVENTORY, T0, 0);
        stalled(saga);
        when(loadOrderPort.loadById(order.getId())).thenReturn(Optional.of(order));

        sweeper.sweep();

        // 같은 커맨드를 다시 보낸다 — 참여 서비스는 결정적 키로 중복을 알아보고 이전 결과만 재응답한다.
        verify(publishSagaCommandPort).reserveStock(any(ReserveStockCommand.class));
        assertThat(saga.getAttempts()).isEqualTo(1);
        assertThat(saga.getState()).isEqualTo(SagaState.AWAITING_INVENTORY);   // 상태는 그대로
    }

    @Test
    void stalledPaymentStep_resendsChargeCommand() {
        Order order = order();
        SagaInstance saga = SagaInstance.restore(UUID.randomUUID(), order.getId(),
                SagaState.AWAITING_PAYMENT, T0, 1);
        stalled(saga);
        when(loadOrderPort.loadById(order.getId())).thenReturn(Optional.of(order));

        sweeper.sweep();

        verify(publishSagaCommandPort).chargePayment(any(ChargePaymentCommand.class));
        assertThat(saga.getAttempts()).isEqualTo(2);
    }

    @Test
    void exhaustedPaymentStep_switchesToCompensation_notPlainCancel() {
        Order order = order();
        order.markInventoryReserved();
        SagaInstance saga = SagaInstance.restore(UUID.randomUUID(), order.getId(),
                SagaState.AWAITING_PAYMENT, T0, 3);   // 재시도 한도 도달
        stalled(saga);

        sweeper.sweep();

        // ★ 재고를 이미 잡아뒀으므로 그냥 취소하면 재고가 샌다 → 보상부터 지시한다.
        assertThat(saga.getState()).isEqualTo(SagaState.COMPENSATING_INVENTORY);
        verify(publishSagaCommandPort).releaseStock(any(ReleaseStockCommand.class));
        verify(publishSagaCommandPort, never()).chargePayment(any());
        verify(publishOrderEventPort, never()).orderCancelled(any());
    }

    @Test
    void exhaustedInventoryStep_cancelsOrder_nothingToCompensate() {
        Order order = order();
        SagaInstance saga = SagaInstance.restore(UUID.randomUUID(), order.getId(),
                SagaState.AWAITING_INVENTORY, T0, 3);
        stalled(saga);
        when(loadOrderPort.loadById(order.getId())).thenReturn(Optional.of(order));

        sweeper.sweep();

        assertThat(saga.getState()).isEqualTo(SagaState.CANCELLED);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(publishOrderEventPort).orderCancelled(any(OrderCancelledEvent.class));
        verify(publishSagaCommandPort, never()).reserveStock(any());   // 더 재촉하지 않는다
    }

    @Test
    void exhaustedCompensation_cancelsOrder_soNoSagaStaysStuck() {
        Order order = order();
        order.markInventoryReserved();
        SagaInstance saga = SagaInstance.restore(UUID.randomUUID(), order.getId(),
                SagaState.COMPENSATING_INVENTORY, T0, 3);
        stalled(saga);
        when(loadOrderPort.loadById(order.getId())).thenReturn(Optional.of(order));

        sweeper.sweep();

        // 보상 응답조차 못 받아도 Saga 는 종료된다 — "멈춘 saga 없음"이 이 Phase의 약속.
        assertThat(saga.getState()).isEqualTo(SagaState.CANCELLED);
        verify(publishOrderEventPort).orderCancelled(any(OrderCancelledEvent.class));
    }

    @Test
    void noStalledSagas_doesNothing() {
        when(sagaRepository.findStalled(any(), any(), anyInt())).thenReturn(List.of());

        sweeper.sweep();

        verify(publishSagaCommandPort, never()).reserveStock(any());
        verify(publishSagaCommandPort, never()).chargePayment(any());
        verify(publishSagaCommandPort, never()).releaseStock(any());
        verify(sagaRepository, never()).update(any());
    }
}
