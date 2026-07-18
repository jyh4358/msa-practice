package com.shopsaga.inventory.adapter.in.event;

import com.shopsaga.events.OrderPlacedEvent;
import com.shopsaga.inventory.application.port.in.ReserveStockUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 9: 인바운드 이벤트 어댑터 — OrderPlaced 를 소비해 재고 예약 유스케이스를 호출한다.
 * observation-enabled 리스너라, producer(order)의 traceparent 를 이어받아 같은 트레이스로 처리된다.
 *
 * <p>Phase 10: order 의 Outbox 릴레이가 실어 보낸 {@code messageId} 헤더를 읽어 유스케이스에 전달한다 →
 * 멱등 처리(같은 메시지 재배달 시 예약 1회). 헤더가 없으면(예: 수동 발행) orderId 로 대체해 dedup 한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class OrderPlacedListener {

    /** order-service OutboxRelay.HEADER_MESSAGE_ID 와 이름이 일치해야 한다. */
    static final String HEADER_MESSAGE_ID = "messageId";

    private final ReserveStockUseCase reserveStockUseCase;

    @KafkaListener(topics = KafkaTopicConfig.ORDER_PLACED_TOPIC, groupId = "inventory-service")
    void on(OrderPlacedEvent event,
            @Header(name = HEADER_MESSAGE_ID, required = false) String messageIdHeader) {
        UUID messageId = resolveMessageId(messageIdHeader, event.orderId());
        log.info("OrderPlaced 수신 messageId={} orderId={} 품목수={}", messageId, event.orderId(), event.items().size());

        Map<UUID, Integer> quantityByProduct = new HashMap<>();
        event.items().forEach(i -> quantityByProduct.merge(i.productId(), i.quantity(), Integer::sum));
        reserveStockUseCase.reserveForOrder(messageId, event.orderId(), quantityByProduct);
    }

    private UUID resolveMessageId(String header, UUID fallbackOrderId) {
        if (header == null || header.isBlank()) {
            log.warn("messageId 헤더 없음 — orderId 로 dedup 대체 orderId={}", fallbackOrderId);
            return fallbackOrderId;
        }
        return UUID.fromString(header);
    }
}
