package com.shopsaga.payment.adapter.out.messaging;

import com.shopsaga.events.PaymentChargedEvent;
import com.shopsaga.events.PaymentDeclinedEvent;
import com.shopsaga.events.Topics;
import com.shopsaga.outbox.OutboxWriter;
import com.shopsaga.payment.application.port.out.PublishPaymentEventPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Phase 12: 결제 이벤트 발행 어댑터 — 공유 {@link OutboxWriter} 로 현재 트랜잭션에 outbox row 만 남긴다.
 * key 는 orderId(결제 id가 아님) — 같은 주문의 Saga 이벤트가 같은 파티션에서 순서를 유지하게.
 */
@Component
@RequiredArgsConstructor
class PaymentEventOutboxPublisher implements PublishPaymentEventPort {

    private final OutboxWriter outboxWriter;

    @Override
    public void paymentCharged(PaymentChargedEvent event) {
        outboxWriter.write(event.orderId(), event, Topics.PAYMENT_EVENTS);
    }

    @Override
    public void paymentDeclined(PaymentDeclinedEvent event) {
        outboxWriter.write(event.orderId(), event, Topics.PAYMENT_EVENTS);
    }
}
