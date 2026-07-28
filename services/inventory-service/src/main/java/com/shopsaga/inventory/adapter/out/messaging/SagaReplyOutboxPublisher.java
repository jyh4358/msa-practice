package com.shopsaga.inventory.adapter.out.messaging;

import com.shopsaga.events.Topics;
import com.shopsaga.events.commands.SagaReply;
import com.shopsaga.inventory.application.port.out.PublishSagaReplyPort;
import com.shopsaga.outbox.OutboxWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Phase 13: 리플라이 발행 어댑터 — outbox 로 기록해 <b>업무 처리와 응답이 원자적</b>이 되게 한다.
 * (재고는 깎였는데 응답을 못 보내는 상황이 없다 → 조정자가 유령 타임아웃을 겪지 않는다.)
 */
@Component
@RequiredArgsConstructor
class SagaReplyOutboxPublisher implements PublishSagaReplyPort {

    private final OutboxWriter outboxWriter;

    @Override
    public void reply(SagaReply reply) {
        outboxWriter.write(reply.orderId(), reply, Topics.SAGA_REPLIES);
    }
}
