package com.shopsaga.order.adapter.out.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopsaga.events.OrderPlacedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.Message;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Phase 10: 릴레이가 브로커 ack 후에만 발행완료로 표시하고, 실패 시 미발행으로 남겨 재시도하는지 검증
 * (= at-least-once 배달 보장의 핵심).
 */
@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    @Mock
    OutboxJpaRepository repository;
    @Mock
    KafkaTemplate<String, Object> kafkaTemplate;
    // Spring 이 주입하는 ObjectMapper 와 동일하게 JSR-310(Instant) 모듈을 등록한 매퍼를 쓴다.
    @Spy
    ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    @InjectMocks
    OutboxRelay relay;

    private OutboxMessageJpaEntity unpublishedRow() {
        UUID orderId = UUID.randomUUID();
        String payload = serialize(new OrderPlacedEvent(orderId, UUID.randomUUID(),
                List.of(new OrderPlacedEvent.Item(UUID.randomUUID(), 1, new BigDecimal("10.00"))),
                new BigDecimal("10.00"), Instant.parse("2026-07-18T10:00:00Z")));
        return new OutboxMessageJpaEntity(UUID.randomUUID(), orderId,
                OrderPlacedEvent.class.getName(), OutboxRelay.ORDER_PLACED_TOPIC, payload, Instant.now());
    }

    private String serialize(OrderPlacedEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void relay_publishesAndMarksPublished_onAck() {
        OutboxMessageJpaEntity row = unpublishedRow();
        doReturn(List.of(row)).when(repository).findTop100ByPublishedAtIsNullOrderByCreatedAtAsc();
        doReturn(CompletableFuture.completedFuture(null)).when(kafkaTemplate).send(any(Message.class));

        relay.relay();

        verify(kafkaTemplate, times(1)).send(any(Message.class));
        assertThat(row.getPublishedAt()).isNotNull();   // ack 확인 → 발행완료
        assertThat(row.getAttempts()).isZero();
    }

    @Test
    void relay_keepsRowUnpublished_whenSendFails() {
        OutboxMessageJpaEntity row = unpublishedRow();
        doReturn(List.of(row)).when(repository).findTop100ByPublishedAtIsNullOrderByCreatedAtAsc();
        doReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")))
                .when(kafkaTemplate).send(any(Message.class));

        relay.relay();

        assertThat(row.getPublishedAt()).isNull();       // 미발행 유지 → 다음 폴링에서 재시도(at-least-once)
        assertThat(row.getAttempts()).isEqualTo(1);
    }
}
