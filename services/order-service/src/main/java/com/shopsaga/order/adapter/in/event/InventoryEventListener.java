package com.shopsaga.order.adapter.in.event;

import com.shopsaga.events.InventoryFailedEvent;
import com.shopsaga.events.InventoryReservedEvent;
import com.shopsaga.events.Topics;
import com.shopsaga.order.application.port.in.OrderSagaUseCase;
import com.shopsaga.outbox.OutboxRelay;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Phase 12: 인바운드 이벤트 어댑터 — inventory-events 토픽을 소비한다.
 *
 * <p>한 토픽에 여러 이벤트 타입이 흐르므로 <b>클래스 레벨 {@code @KafkaListener} + 타입별 {@code @KafkaHandler}</b>
 * 로 분기한다. 분기는 JSON 직렬화 시 붙는 타입 헤더({@code __TypeId__})를 보고 spring-kafka 가 처리한다.
 * 모르는 타입(다른 서비스가 나중에 추가한 이벤트 등)은 기본 핸들러가 조용히 무시한다 —
 * 그래야 소비자가 발행자의 변경에 깨지지 않는다.
 */
@Component
@ConditionalOnProperty(name = "saga.mode", havingValue = "choreography")   // Phase 13: 오케스트레이션에선 조정자가 대신한다
@KafkaListener(topics = Topics.INVENTORY_EVENTS, groupId = "order-service")
@RequiredArgsConstructor
@Slf4j
class InventoryEventListener {

    private final OrderSagaUseCase orderSagaUseCase;

    @KafkaHandler
    void onReserved(InventoryReservedEvent event,
                    @Header(name = OutboxRelay.HEADER_MESSAGE_ID, required = false) String messageId) {
        log.info("InventoryReserved 수신 orderId={} messageId={}", event.orderId(), messageId);
        orderSagaUseCase.onInventoryReserved(MessageIds.resolve(messageId, event.orderId()), event);
    }

    @KafkaHandler
    void onFailed(InventoryFailedEvent event,
                  @Header(name = OutboxRelay.HEADER_MESSAGE_ID, required = false) String messageId) {
        log.info("InventoryFailed 수신 orderId={} reason={} messageId={}", event.orderId(), event.reason(), messageId);
        orderSagaUseCase.onInventoryFailed(MessageIds.resolve(messageId, event.orderId()), event);
    }

    @KafkaHandler(isDefault = true)
    void onUnknown(Object event) {
        // 이 서비스가 관심 없는 타입 — 무시(오프셋은 진행된다).
        log.debug("관심 없는 inventory 이벤트 무시 type={}", event.getClass().getSimpleName());
    }
}
