package com.shopsaga.order.adapter.in.event;

import com.shopsaga.events.PaymentChargedEvent;
import com.shopsaga.events.PaymentDeclinedEvent;
import com.shopsaga.events.Topics;
import com.shopsaga.order.application.port.in.OrderSagaUseCase;
import com.shopsaga.outbox.OutboxRelay;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Phase 12: 인바운드 이벤트 어댑터 — payment-events 토픽을 소비한다.
 * 결제 성공은 주문 확정(Saga 성공 종료), 거절은 주문 취소(긴 보상 경로)로 이어진다.
 */
@Component
@KafkaListener(topics = Topics.PAYMENT_EVENTS, groupId = "order-service")
@RequiredArgsConstructor
@Slf4j
class PaymentEventListener {

    private final OrderSagaUseCase orderSagaUseCase;

    @KafkaHandler
    void onCharged(PaymentChargedEvent event,
                   @Header(name = OutboxRelay.HEADER_MESSAGE_ID, required = false) String messageId) {
        log.info("PaymentCharged 수신 orderId={} paymentId={} messageId={}",
                event.orderId(), event.paymentId(), messageId);
        orderSagaUseCase.onPaymentCharged(MessageIds.resolve(messageId, event.orderId()), event);
    }

    @KafkaHandler
    void onDeclined(PaymentDeclinedEvent event,
                    @Header(name = OutboxRelay.HEADER_MESSAGE_ID, required = false) String messageId) {
        log.info("PaymentDeclined 수신 orderId={} reason={} messageId={}",
                event.orderId(), event.reason(), messageId);
        orderSagaUseCase.onPaymentDeclined(MessageIds.resolve(messageId, event.orderId()), event);
    }

    @KafkaHandler(isDefault = true)
    void onUnknown(Object event) {
        log.debug("관심 없는 payment 이벤트 무시 type={}", event.getClass().getSimpleName());
    }
}
