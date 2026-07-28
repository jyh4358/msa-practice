package com.shopsaga.inventory.adapter.in.event;

import com.shopsaga.events.OrderPlacedEvent;
import com.shopsaga.events.Topics;
import com.shopsaga.inventory.application.port.in.InventorySagaUseCase;
import com.shopsaga.outbox.OutboxRelay;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Phase 12: 인바운드 이벤트 어댑터 — order-events 를 소비해 Saga 1단계(재고 예약)를 수행한다.
 * observation-enabled 리스너라 발행자의 traceparent 를 이어받는다(릴레이가 복원해 실어 보낸 것).
 */
@Component
@ConditionalOnProperty(name = "saga.mode", havingValue = "choreography")   // Phase 13: 오케스트레이션에선 커맨드로 받는다
@KafkaListener(topics = Topics.ORDER_EVENTS, groupId = "inventory-service")
@RequiredArgsConstructor
@Slf4j
class OrderEventListener {

    private final InventorySagaUseCase inventorySagaUseCase;

    @KafkaHandler
    void onPlaced(OrderPlacedEvent event,
                  @Header(name = OutboxRelay.HEADER_MESSAGE_ID, required = false) String messageId) {
        log.info("OrderPlaced 수신 orderId={} 품목수={} messageId={}",
                event.orderId(), event.items().size(), messageId);
        inventorySagaUseCase.onOrderPlaced(MessageIds.resolve(messageId, event.orderId()), event);
    }

    @KafkaHandler(isDefault = true)
    void onUnknown(Object event) {
        // OrderConfirmed/OrderCancelled 는 재고가 관심 없는 사실 — 무시(오프셋은 진행).
        log.debug("관심 없는 order 이벤트 무시 type={}", event.getClass().getSimpleName());
    }
}
