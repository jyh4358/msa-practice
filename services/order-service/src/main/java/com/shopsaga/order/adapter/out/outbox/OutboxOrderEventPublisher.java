package com.shopsaga.order.adapter.out.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopsaga.events.OrderPlacedEvent;
import com.shopsaga.order.application.port.out.PublishOrderEventPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 10: {@link PublishOrderEventPort} 의 <b>Outbox</b> 구현.
 *
 * <p>핵심: Kafka 로 <b>직접 보내지 않는다</b>. 현재 트랜잭션(= 주문 {@code save()} 와 동일)에 outbox row 를
 * 기록만 한다. 따라서 "주문 저장"과 "이벤트 발행 의도"가 <b>한 커밋으로 원자적</b>이다 —
 * 이중 쓰기(commit 후 크래시 → 유실 / send 후 롤백 → 유령)가 원천 차단된다.
 * 실제 Kafka 발행은 {@link OutboxRelay} 가 별도로 수행한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class OutboxOrderEventPublisher implements PublishOrderEventPort {

    private final OutboxJpaRepository repository;
    private final ObjectMapper objectMapper;

    @Override
    public void orderPlaced(OrderPlacedEvent event) {
        write(event.orderId(), event, OutboxRelay.ORDER_PLACED_TOPIC);
    }

    private void write(UUID aggregateId, Object event, String topic) {
        UUID messageId = UUID.randomUUID();
        String payload = serialize(event);
        repository.save(new OutboxMessageJpaEntity(
                messageId, aggregateId, event.getClass().getName(), topic, payload, Instant.now()));
        log.info("Outbox 기록 messageId={} type={} aggregate={}",
                messageId, event.getClass().getSimpleName(), aggregateId);
    }

    private String serialize(Object event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            // 직렬화 실패는 트랜잭션을 되돌려야 한다(주문도 함께 롤백) — 부분 상태 방지.
            throw new IllegalStateException("이벤트 직렬화 실패: " + event.getClass().getName(), e);
        }
    }
}
