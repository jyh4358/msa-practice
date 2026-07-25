package com.shopsaga.order.adapter.out.messaging;

import com.shopsaga.events.OrderCancelledEvent;
import com.shopsaga.events.OrderConfirmedEvent;
import com.shopsaga.events.OrderPlacedEvent;
import com.shopsaga.events.Topics;
import com.shopsaga.order.application.port.out.PublishOrderEventPort;
import com.shopsaga.outbox.OutboxWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Phase 12: 주문 이벤트 발행 어댑터 — 공유 {@link OutboxWriter} 로 <b>현재 트랜잭션에 outbox row 만</b> 남긴다.
 * 실제 Kafka 발행은 릴레이가 별도로 수행한다(원자성 + at-least-once).
 *
 * <p>Phase 10에서 order 전용으로 만들었던 발행 어댑터를, Saga가 되며 3개 서비스가 같은 메커니즘을 쓰게 되어
 * 공유 라이브러리(`shared/outbox`) 기반으로 바꾼 것이다.
 */
@Component
@RequiredArgsConstructor
class OrderEventOutboxPublisher implements PublishOrderEventPort {

    private final OutboxWriter outboxWriter;

    @Override
    public void orderPlaced(OrderPlacedEvent event) {
        outboxWriter.write(event.orderId(), event, Topics.ORDER_EVENTS);
    }

    @Override
    public void orderConfirmed(OrderConfirmedEvent event) {
        outboxWriter.write(event.orderId(), event, Topics.ORDER_EVENTS);
    }

    @Override
    public void orderCancelled(OrderCancelledEvent event) {
        outboxWriter.write(event.orderId(), event, Topics.ORDER_EVENTS);
    }
}
