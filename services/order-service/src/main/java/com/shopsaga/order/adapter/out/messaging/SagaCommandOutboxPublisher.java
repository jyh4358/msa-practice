package com.shopsaga.order.adapter.out.messaging;

import com.shopsaga.events.Topics;
import com.shopsaga.events.commands.ChargePaymentCommand;
import com.shopsaga.events.commands.RefundPaymentCommand;
import com.shopsaga.events.commands.ReleaseStockCommand;
import com.shopsaga.events.commands.ReserveStockCommand;
import com.shopsaga.order.application.port.out.PublishSagaCommandPort;
import com.shopsaga.outbox.OutboxWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Phase 13: 조정자의 커맨드 발행 어댑터 — outbox 에 기록만 한다(Kafka 전송은 릴레이가).
 * Saga 상태 전이와 커맨드가 <b>한 커밋</b>이라 "상태만 바뀌고 지시는 안 나감"이 불가능하다.
 *
 * <p>메시지 key 는 orderId — 같은 주문의 커맨드는 같은 파티션에 들어가 순서가 보장된다.
 */
@Component
@RequiredArgsConstructor
class SagaCommandOutboxPublisher implements PublishSagaCommandPort {

    private final OutboxWriter outboxWriter;

    @Override
    public void reserveStock(ReserveStockCommand command) {
        outboxWriter.write(command.orderId(), command, Topics.SAGA_COMMANDS);
    }

    @Override
    public void chargePayment(ChargePaymentCommand command) {
        outboxWriter.write(command.orderId(), command, Topics.SAGA_COMMANDS);
    }

    @Override
    public void releaseStock(ReleaseStockCommand command) {
        outboxWriter.write(command.orderId(), command, Topics.SAGA_COMMANDS);
    }

    @Override
    public void refundPayment(RefundPaymentCommand command) {
        outboxWriter.write(command.orderId(), command, Topics.SAGA_COMMANDS);
    }
}
