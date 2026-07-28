package com.shopsaga.order.application.service;

import com.shopsaga.events.commands.ChargePaymentCommand;
import com.shopsaga.events.commands.ReleaseStockCommand;
import com.shopsaga.events.commands.ReserveStockCommand;
import com.shopsaga.order.application.port.out.LoadOrderPort;
import com.shopsaga.order.application.port.out.PublishOrderEventPort;
import com.shopsaga.order.application.port.out.PublishSagaCommandPort;
import com.shopsaga.order.application.port.out.SagaInstanceRepositoryPort;
import com.shopsaga.order.application.port.out.UpdateOrderPort;
import com.shopsaga.events.OrderCancelledEvent;
import com.shopsaga.order.domain.Order;
import com.shopsaga.order.domain.saga.SagaInstance;
import com.shopsaga.order.domain.saga.SagaState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Phase 13: <b>타임아웃 sweep</b> — 응답이 오지 않아 멈춰 선 Saga를 깨운다.
 *
 * <p><b>Phase 12가 못 하던 일이다.</b> 코레오그래피에서 payment가 죽어 있으면 주문은
 * {@code INVENTORY_RESERVED} 상태로 <b>영원히</b> 남았다 — 아무도 "얘가 멈췄다"는 사실을 몰랐기 때문이다.
 * 조정자는 각 Saga가 <b>언제 마지막으로 움직였는지</b> 알기에, 데드라인이 지나면 개입할 수 있다.
 *
 * <p>정책은 두 단계다:
 * <ol>
 *   <li>데드라인 초과 → 같은 커맨드를 <b>재전송</b>(참여 서비스가 죽었다 살아났거나 메시지를 놓친 경우 대비).
 *       재전송해도 안전한 이유는 커맨드 dedup 키가 결정적이기 때문이다({@code CommandKeys}) —
 *       이미 처리한 서비스는 다시 일하지 않고 <b>저장해 둔 리플라이만 다시 보낸다</b>.</li>
 *   <li>재시도 한도 초과 → 더 기다리지 않고 <b>실패로 종료</b>(주문 취소). 멈춘 Saga를 남기지 않는다.</li>
 * </ol>
 *
 * <p>⚠️ 재고 확보 후 결제 단계에서 포기하는 경우엔 <b>보상</b>이 필요하다 → 취소 전에 재고 해제를 지시한다.
 */
@Component
@ConditionalOnProperty(name = "saga.mode", havingValue = "orchestration", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
class SagaTimeoutSweeper {

    private static final int BATCH_LIMIT = 50;

    private final SagaInstanceRepositoryPort sagaRepository;
    private final LoadOrderPort loadOrderPort;
    private final UpdateOrderPort updateOrderPort;
    private final PublishSagaCommandPort publishSagaCommandPort;
    private final PublishOrderEventPort publishOrderEventPort;

    /** 이 시간 동안 응답이 없으면 정체로 본다. */
    @Value("${saga.timeout.deadline:15s}")
    private Duration deadline;

    /** 이 횟수만큼 재촉해도 응답이 없으면 포기하고 실패 종료한다. */
    @Value("${saga.timeout.max-attempts:3}")
    private int maxAttempts;

    @Scheduled(fixedDelayString = "${saga.timeout.sweep-interval:5000}")
    @Transactional
    public void sweep() {
        Instant now = Instant.now();
        List<SagaInstance> stalled = sagaRepository.findStalled(now, deadline, BATCH_LIMIT);
        if (stalled.isEmpty()) {
            return;
        }
        log.warn("정체된 Saga {}건 발견 — sweep 시작(deadline={})", stalled.size(), deadline);
        for (SagaInstance saga : stalled) {
            if (saga.getAttempts() >= maxAttempts) {
                giveUp(saga, now);
            } else {
                resendCommand(saga, now);
            }
        }
    }

    /** 같은 커맨드를 다시 보낸다(결정적 dedup 키 덕분에 중복 수행되지 않는다). */
    private void resendCommand(SagaInstance saga, Instant now) {
        saga.recordRetry(now);
        sagaRepository.update(saga);

        switch (saga.getState()) {
            case AWAITING_INVENTORY -> resendReserveStock(saga, now);
            case AWAITING_PAYMENT -> publishSagaCommandPort.chargePayment(new ChargePaymentCommand(
                    saga.getSagaId(), saga.getOrderId(), totalAmountOf(saga), now));
            case COMPENSATING_INVENTORY -> publishSagaCommandPort.releaseStock(new ReleaseStockCommand(
                    saga.getSagaId(), saga.getOrderId(), now));
            default -> { /* 종료 상태는 findStalled 가 걸러낸다 */ }
        }
        log.warn("커맨드 재전송 sagaId={} orderId={} state={} attempts={}",
                saga.getSagaId(), saga.getOrderId(), saga.getState(), saga.getAttempts());
    }

    private void resendReserveStock(SagaInstance saga, Instant now) {
        Order order = loadOrderPort.loadById(saga.getOrderId()).orElseThrow();
        List<ReserveStockCommand.Item> items = order.getItems().stream()
                .map(i -> new ReserveStockCommand.Item(i.getProductId(), i.getQuantity(), i.getUnitPrice()))
                .toList();
        publishSagaCommandPort.reserveStock(new ReserveStockCommand(
                saga.getSagaId(), saga.getOrderId(), order.getCustomerId(),
                order.getTotalAmount(), items, now));
    }

    /**
     * 재시도 한도 초과 → 포기. 단, <b>이미 확보한 재고가 있으면 먼저 풀어야</b> 한다.
     * (결제 대기 중 포기라면 재고가 잡혀 있으므로 보상 단계로 넘긴다.)
     */
    private void giveUp(SagaInstance saga, Instant now) {
        if (saga.getState() == SagaState.AWAITING_PAYMENT) {
            if (saga.startCompensation(now)) {
                sagaRepository.update(saga);
                publishSagaCommandPort.releaseStock(new ReleaseStockCommand(
                        saga.getSagaId(), saga.getOrderId(), now));
                log.error("결제 응답 없음 — 재시도 한도 초과, 보상으로 전환 sagaId={} orderId={}",
                        saga.getSagaId(), saga.getOrderId());
            }
            return;
        }

        // 재고 확보 전이거나 보상까지 실패한 경우 — 더 되돌릴 것 없이 실패 종료한다.
        SagaState previous = saga.getState();
        if (saga.cancel(now)) {
            sagaRepository.update(saga);
            cancelOrder(saga, now);
            log.error("응답 없음 — 재시도 한도 초과, Saga 실패 종료 sagaId={} orderId={} 직전상태={}",
                    saga.getSagaId(), saga.getOrderId(), previous);
        }
    }

    private void cancelOrder(SagaInstance saga, Instant now) {
        loadOrderPort.loadById(saga.getOrderId()).ifPresent(order -> {
            if (order.cancel()) {
                updateOrderPort.update(order);
                publishOrderEventPort.orderCancelled(new OrderCancelledEvent(
                        saga.getOrderId(), "Saga 타임아웃(응답 없음)", now));
            }
        });
    }

    private java.math.BigDecimal totalAmountOf(SagaInstance saga) {
        return loadOrderPort.loadById(saga.getOrderId())
                .map(Order::getTotalAmount)
                .orElse(java.math.BigDecimal.ZERO);
    }
}
