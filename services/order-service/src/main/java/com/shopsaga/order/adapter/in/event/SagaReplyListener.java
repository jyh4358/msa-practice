package com.shopsaga.order.adapter.in.event;

import com.shopsaga.events.Topics;
import com.shopsaga.events.commands.SagaReply;
import com.shopsaga.order.application.port.in.SagaReplyUseCase;
import com.shopsaga.outbox.OutboxRelay;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Phase 13: 조정자의 <b>유일한 인바운드 채널</b> — 모든 참여 서비스의 리플라이가 여기로 모인다.
 *
 * <p>코레오그래피에서는 order가 inventory-events·payment-events를 각각 듣고 타입별로 분기했다.
 * 오케스트레이션에서는 <b>한 토픽·한 타입</b>({@link SagaReply})만 들으면 되므로 조정자 쪽이 단순해진다
 * (대신 참여 서비스가 리플라이를 반드시 돌려줘야 하는 의무를 진다).
 */
@Component
@ConditionalOnProperty(name = "saga.mode", havingValue = "orchestration", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
class SagaReplyListener {

    private final SagaReplyUseCase sagaReplyUseCase;

    @KafkaListener(topics = Topics.SAGA_REPLIES, groupId = "order-service")
    void onReply(SagaReply reply,
                 @Header(name = OutboxRelay.HEADER_MESSAGE_ID, required = false) String messageId) {
        log.info("SagaReply 수신 kind={} sagaId={} orderId={} messageId={}",
                reply.kind(), reply.sagaId(), reply.orderId(), messageId);
        sagaReplyUseCase.onReply(MessageIds.resolve(messageId, reply.sagaId()), reply);
    }
}
