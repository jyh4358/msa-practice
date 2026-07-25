package com.shopsaga.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Phase 10에서 만든 릴레이를 Phase 12에서 공유 라이브러리로 승격 + <b>트레이스 복원</b>을 추가한 것.
 *
 * <p>미발행 outbox row 를 폴링해 Kafka 로 발행한다(at-least-once). 브로커 ack 를 받은 뒤에만
 * {@code published_at} 을 채우므로, 발행 도중 크래시가 나면 그 row 는 미발행으로 남아 재시작 후 다시 발행된다
 * (→ 다운스트림 중복 → 소비자의 멱등 처리로 흡수 = effectively-once).
 *
 * <p><b>Phase 12의 핵심 추가 — 트레이스 컨텍스트 복원:</b>
 * 이 메서드는 요청 스레드가 아닌 {@code @Scheduled} 스레드에서 돈다. 그대로 발행하면 새 트레이스가 시작되어
 * "주문 요청"과 "Saga의 나머지"가 <b>서로 다른 트레이스로 끊긴다</b>(Phase 10·11의 알려진 한계).
 * 그래서 저장해 둔 {@code traceparent} 를 {@link Propagator#extract} 로 복원해 그 컨텍스트 <b>안에서</b> 발행한다.
 * 그러면 observation 계측이 만드는 producer 스팬이 원래 트레이스의 자식이 되고, Kafka 헤더에도 같은 traceId 가 실린다
 * → Grafana에서 <b>Saga 전체가 하나의 트레이스</b>로 보인다.
 */
@RequiredArgsConstructor
@Slf4j
public class OutboxRelay {

    /** 소비자가 dedup 키로 읽는 헤더. 소비 측 리스너와 이름이 일치해야 한다. */
    public static final String HEADER_MESSAGE_ID = "messageId";

    private final OutboxRepository repository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final Tracer tracer;
    private final Propagator propagator;

    @Scheduled(fixedDelayString = "${outbox.relay.fixed-delay:1000}")
    @Transactional
    public void relay() {
        List<OutboxMessage> batch = repository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc();
        if (batch.isEmpty()) {
            return;
        }
        for (OutboxMessage msg : batch) {
            try {
                publishWithRestoredTrace(msg);
                msg.markPublished(Instant.now());   // 브로커 ack 확정 후에만 발행완료 표시.
                log.info("Outbox 발행 messageId={} topic={} type={}",
                        msg.getId(), msg.getTopic(), simpleTypeOf(msg));
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

    /** 저장된 traceparent 를 복원한 스코프 안에서 발행 → Saga 전체가 한 트레이스로 이어진다. */
    private void publishWithRestoredTrace(OutboxMessage msg) throws Exception {
        if (tracer == null || propagator == null) {
            publish(msg);   // 트레이싱 미구성(테스트 등) — 신뢰성 동작은 그대로.
            return;
        }
        Span span = startRelaySpan(msg);
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            publish(msg);
        } finally {
            span.end();
        }
    }

    private Span startRelaySpan(OutboxMessage msg) {
        String traceparent = msg.getTraceparent();
        if (traceparent == null || traceparent.isBlank()) {
            // 기록 시점에 트레이스가 없었던 경우(예: 스케줄러가 만든 이벤트) — 새 트레이스로 시작.
            return tracer.nextSpan().name("outbox-relay").start();
        }
        // 원 요청의 컨텍스트를 원격 부모로 삼아 이어붙인다.
        return propagator.extract(Map.of("traceparent", traceparent), Map::get)
                .name("outbox-relay")
                .start();
    }

    private void publish(OutboxMessage msg) throws Exception {
        Object event = objectMapper.readValue(msg.getPayload(), Class.forName(msg.getEventType()));
        Message<Object> message = MessageBuilder.withPayload(event)
                .setHeader(KafkaHeaders.TOPIC, msg.getTopic())
                .setHeader(KafkaHeaders.KEY, msg.getAggregateId().toString())   // 같은 주문 → 같은 파티션(순서).
                .setHeader(HEADER_MESSAGE_ID, msg.getId().toString())           // 소비자 dedup 키.
                .build();
        // ack 까지 블로킹 — 성공을 확인한 뒤에만 markPublished. (JsonSerializer 가 __TypeId__ 헤더 자동 부착.)
        kafkaTemplate.send(message).get(5, TimeUnit.SECONDS);
    }

    private String simpleTypeOf(OutboxMessage msg) {
        String type = msg.getEventType();
        return type.substring(type.lastIndexOf('.') + 1);
    }
}
