package com.shopsaga.inventory.adapter.in.event;

import com.shopsaga.events.PaymentDeclinedEvent;
import com.shopsaga.events.Topics;
import com.shopsaga.inventory.application.port.in.InventorySagaUseCase;
import com.shopsaga.outbox.OutboxRelay;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Phase 12: 인바운드 이벤트 어댑터 — payment-events 를 소비해 <b>보상</b>(재고 해제)을 수행한다.
 *
 * <p>주목할 점: payment-service는 "재고를 풀어라"라고 <b>지시하지 않았다</b>. 그냥 "결제가 거절됐다"는
 * 사실만 알렸고, 그 사실에 어떻게 반응할지는 <b>inventory가 스스로 결정</b>한다 — 코레오그래피의 본질.
 * 같은 이벤트를 order도 듣고 주문을 취소한다(두 소비자가 각자 반응).
 */
@Component
@KafkaListener(topics = Topics.PAYMENT_EVENTS, groupId = "inventory-service")
@RequiredArgsConstructor
@Slf4j
class PaymentEventListener {

    private final InventorySagaUseCase inventorySagaUseCase;

    @KafkaHandler
    void onDeclined(PaymentDeclinedEvent event,
                    @Header(name = OutboxRelay.HEADER_MESSAGE_ID, required = false) String messageId) {
        log.info("PaymentDeclined 수신(보상 트리거) orderId={} reason={} messageId={}",
                event.orderId(), event.reason(), messageId);
        inventorySagaUseCase.onPaymentDeclined(MessageIds.resolve(messageId, event.orderId()), event);
    }

    @KafkaHandler(isDefault = true)
    void onUnknown(Object event) {
        // PaymentCharged 는 재고가 할 일이 없는 사실 — 무시.
        log.debug("관심 없는 payment 이벤트 무시 type={}", event.getClass().getSimpleName());
    }
}
