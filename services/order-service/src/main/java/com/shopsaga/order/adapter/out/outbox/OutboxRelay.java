package com.shopsaga.order.adapter.out.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Phase 10: Outbox 릴레이 — 미발행 outbox row 를 폴링해 Kafka 로 발행한다(at-least-once).
 *
 * <p>브로커 ack 를 받은 뒤에만 {@code published_at} 을 채우므로, 발행 도중 크래시가 나면 해당 row 는
 * 미발행으로 남아 재시작 후 다시 발행된다(→ 다운스트림 중복 → 소비자의 멱등 처리로 흡수 = effectively-once).
 * Kafka 가 잠시 내려가도 주문은 이미 커밋됐고 이벤트는 outbox 에 버퍼링되어, 브로커 복구 시 자동 따라잡는다.
 *
 * <p>⚠️ 이 릴레이는 요청 스레드가 아닌 별도 @Scheduled 스레드에서 실행되므로 원 요청의 트레이스 컨텍스트가 없다 →
 * 발행 스팬이 새 트레이스로 시작된다(HTTP→Kafka 단일 트레이스 끊김). W3C {@code traceparent} 를 outbox row 에
 * 저장했다가 재주입해 복원하는 것은 <b>Phase 12</b>의 몫이다(의도된 한계).
 */
@Component
@RequiredArgsConstructor
@Slf4j
class OutboxRelay {

    static final String ORDER_PLACED_TOPIC = "order-placed";
    /** 소비자(inventory)가 dedup 키로 읽는 헤더. inventory 측 리스너와 이름이 일치해야 한다. */
    static final String HEADER_MESSAGE_ID = "messageId";

    private final OutboxJpaRepository repository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelayString = "${outbox.relay.fixed-delay:1000}")
    @Transactional
    public void relay() {
        List<OutboxMessageJpaEntity> batch = repository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc();
        if (batch.isEmpty()) {
            return;
        }
        for (OutboxMessageJpaEntity msg : batch) {
            try {
                publish(msg);
                msg.markPublished(Instant.now());   // 브로커 ack 확정 후에만 발행완료 표시.
                log.info("Outbox 발행 messageId={} topic={}", msg.getId(), msg.getTopic());
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                // published_at 을 남겨두지 않는다 → 다음 폴링에서 재시도(at-least-once).
                msg.recordFailedAttempt();
                log.warn("Outbox 발행 실패 messageId={} attempts={} — {}",
                        msg.getId(), msg.getAttempts(), e.toString());
            }
        }
    }

    private void publish(OutboxMessageJpaEntity msg) throws Exception {
        Object event = objectMapper.readValue(msg.getPayload(), Class.forName(msg.getEventType()));
        Message<Object> message = MessageBuilder.withPayload(event)
                .setHeader(KafkaHeaders.TOPIC, msg.getTopic())
                .setHeader(KafkaHeaders.KEY, msg.getAggregateId().toString())   // 같은 주문 → 같은 파티션(순서).
                .setHeader(HEADER_MESSAGE_ID, msg.getId().toString())           // 소비자 dedup 키.
                .build();
        // ack 까지 블로킹 — 성공을 확인한 뒤에만 markPublished. (JsonSerializer 가 __TypeId__ 헤더 자동 부착.)
        kafkaTemplate.send(message).get(5, TimeUnit.SECONDS);
    }
}
