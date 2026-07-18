package com.shopsaga.order.adapter.out.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopsaga.events.OrderPlacedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * Phase 10: 발행 어댑터가 Kafka 로 직접 보내지 않고 <b>outbox row 만 기록</b>하는지 검증(원자성의 핵심).
 */
@ExtendWith(MockitoExtension.class)
class OutboxOrderEventPublisherTest {

    @Mock
    OutboxJpaRepository repository;
    @Spy
    ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks
    OutboxOrderEventPublisher publisher;
    @Captor
    ArgumentCaptor<OutboxMessageJpaEntity> captor;

    @Test
    void orderPlaced_writesUnpublishedOutboxRow() {
        UUID orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        OrderPlacedEvent event = new OrderPlacedEvent(orderId, customerId,
                List.of(new OrderPlacedEvent.Item(productId, 2, new BigDecimal("10.00"))));

        publisher.orderPlaced(event);

        verify(repository).save(captor.capture());
        OutboxMessageJpaEntity row = captor.getValue();
        assertThat(row.getId()).isNotNull();                                   // = messageId
        assertThat(row.getAggregateId()).isEqualTo(orderId);
        assertThat(row.getEventType()).isEqualTo(OrderPlacedEvent.class.getName());
        assertThat(row.getTopic()).isEqualTo(OutboxRelay.ORDER_PLACED_TOPIC);
        assertThat(row.getPayload()).contains(orderId.toString());            // 직렬화된 이벤트
        assertThat(row.getPublishedAt()).isNull();                            // 아직 미발행 — 릴레이 몫
        assertThat(row.getAttempts()).isZero();
    }
}
