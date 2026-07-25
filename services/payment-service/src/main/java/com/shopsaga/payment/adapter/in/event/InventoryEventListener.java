package com.shopsaga.payment.adapter.in.event;

import com.shopsaga.events.InventoryReservedEvent;
import com.shopsaga.events.Topics;
import com.shopsaga.outbox.OutboxRelay;
import com.shopsaga.payment.application.port.in.PaymentSagaUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Phase 12: 인바운드 이벤트 어댑터 — inventory-events 를 소비해 Saga 2단계(결제)를 수행한다.
 *
 * <p>Phase 2~11 에서는 order가 이 서비스를 <b>REST로 호출</b>했다. 이제 payment는 아무도 호출하지 않고,
 * "재고가 예약됐다"는 사실을 스스로 듣고 청구한다 — order는 payment의 존재조차 몰라도 된다(느슨한 결합).
 */
@Component
@KafkaListener(topics = Topics.INVENTORY_EVENTS, groupId = "payment-service")
@RequiredArgsConstructor
@Slf4j
class InventoryEventListener {

    private final PaymentSagaUseCase paymentSagaUseCase;

    @KafkaHandler
    void onReserved(InventoryReservedEvent event,
                    @Header(name = OutboxRelay.HEADER_MESSAGE_ID, required = false) String messageId) {
        log.info("InventoryReserved 수신 orderId={} amount={} messageId={}",
                event.orderId(), event.totalAmount(), messageId);
        paymentSagaUseCase.onInventoryReserved(resolve(messageId, event.orderId()), event);
    }

    @KafkaHandler(isDefault = true)
    void onUnknown(Object event) {
        // InventoryFailed/InventoryReleased 는 결제가 할 일이 없는 사실 — 무시.
        log.debug("관심 없는 inventory 이벤트 무시 type={}", event.getClass().getSimpleName());
    }

    private UUID resolve(String header, UUID fallbackAggregateId) {
        return (header == null || header.isBlank()) ? fallbackAggregateId : UUID.fromString(header);
    }
}
