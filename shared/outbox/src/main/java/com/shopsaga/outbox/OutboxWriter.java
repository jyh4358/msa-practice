package com.shopsaga.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 12: outbox row 기록기 — <b>Kafka로 직접 보내지 않고</b> 호출자의 현재 트랜잭션에 row 만 남긴다.
 *
 * <p>따라서 "업무 데이터 변경"과 "이벤트 발행 의도"가 <b>한 커밋으로 원자적</b>이다(이중 쓰기 제거).
 * 실제 발행은 {@link OutboxRelay} 가 별도로 수행한다.
 *
 * <p>기록 시점에 <b>현재 트레이스 컨텍스트를 traceparent 로 캡처</b>해 함께 저장한다 —
 * 나중에 릴레이가 이걸 복원해 발행하므로 요청 → outbox → Kafka → 소비자가 한 트레이스로 이어진다.
 */
@RequiredArgsConstructor
@Slf4j
public class OutboxWriter {

    private final OutboxRepository repository;
    private final ObjectMapper objectMapper;
    private final Tracer tracer;
    private final Propagator propagator;

    /**
     * 이벤트를 outbox 에 적는다. <b>반드시 업무 변경과 같은 트랜잭션 안에서</b> 호출해야 원자성이 성립한다.
     *
     * @param aggregateId 이벤트가 속한 애그리거트 id → Kafka 메시지 key(같은 애그리거트는 같은 파티션 = 순서 보장)
     * @return 기록된 messageId(= outbox row id, 소비자 dedup 키)
     */
    public UUID write(UUID aggregateId, Object event, String topic) {
        UUID messageId = UUID.randomUUID();
        repository.save(new OutboxMessage(
                messageId, aggregateId, event.getClass().getName(), topic,
                serialize(event), currentTraceparent(), Instant.now()));
        log.info("Outbox 기록 messageId={} type={} aggregate={} topic={}",
                messageId, event.getClass().getSimpleName(), aggregateId, topic);
        return messageId;
    }

    /**
     * 현재 스팬의 W3C traceparent 문자열.
     * 트레이싱이 꺼져 있거나(테스트 등) 트레이스 컨텍스트가 없으면(스케줄러 등) null — 신뢰성에는 영향 없다.
     */
    private String currentTraceparent() {
        if (tracer == null || propagator == null) {
            return null;
        }
        Span current = tracer.currentSpan();
        if (current == null) {
            return null;
        }
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(current.context(), carrier, Map::put);
        return carrier.get("traceparent");
    }

    private String serialize(Object event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            // 직렬화 실패는 트랜잭션을 되돌려야 한다(업무 변경도 함께 롤백) — 부분 상태 방지.
            throw new IllegalStateException("이벤트 직렬화 실패: " + event.getClass().getName(), e);
        }
    }
}
