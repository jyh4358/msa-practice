package com.shopsaga.order.application.service;

import com.shopsaga.events.commands.ReserveStockCommand;
import com.shopsaga.order.application.port.in.PlaceOrderCommand;
import com.shopsaga.order.application.port.out.PublishSagaCommandPort;
import com.shopsaga.order.application.port.out.SagaInstanceRepositoryPort;
import com.shopsaga.order.application.port.out.SagaStarterPort;
import com.shopsaga.order.domain.Order;
import com.shopsaga.order.domain.saga.SagaInstance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Phase 13: <b>오케스트레이션 모드</b>의 Saga 시작 — 인스턴스를 만들고 첫 커맨드를 내보낸다.
 *
 * <p>주문 저장과 <b>같은 트랜잭션</b>에서 실행되므로(호출자가 {@code @Transactional}),
 * "주문은 저장됐는데 Saga는 시작 안 됨" 또는 그 반대가 생기지 않는다.
 * 커맨드도 outbox 로 기록되어 원자적이다.
 */
@Component
@ConditionalOnProperty(name = "saga.mode", havingValue = "orchestration", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
class OrchestrationSagaStarter implements SagaStarterPort {

    private final SagaInstanceRepositoryPort sagaRepository;
    private final PublishSagaCommandPort publishSagaCommandPort;

    @Override
    public void start(Order order, PlaceOrderCommand command) {
        Instant now = Instant.now();
        SagaInstance saga = SagaInstance.start(order.getId(), now);
        saga.awaitInventory(now);          // STARTED → AWAITING_INVENTORY
        sagaRepository.save(saga);

        List<ReserveStockCommand.Item> items = command.items().stream()
                .map(i -> new ReserveStockCommand.Item(i.productId(), i.quantity(), i.unitPrice()))
                .toList();
        publishSagaCommandPort.reserveStock(new ReserveStockCommand(
                saga.getSagaId(), order.getId(), command.customerId(),
                order.getTotalAmount(), items, now));

        log.info("Saga 시작(오케스트레이션) sagaId={} orderId={} → AWAITING_INVENTORY (ReserveStock 지시)",
                saga.getSagaId(), order.getId());
    }
}
