package com.shopsaga.order.adapter.out.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 10: Outbox 영속 모델(JPA).
 *
 * <p>주문 저장과 <b>같은 트랜잭션</b>에 기록되어(원자성), 별도 릴레이가 미발행 row 를 Kafka 로 내보낸다.
 * {@code id} 는 곧 messageId 로, 소비자(inventory)의 멱등 dedup 키가 된다(같은 row 재발행 시 동일).
 */
@Entity
@Table(name = "outbox")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class OutboxMessageJpaEntity {

    @Id
    private UUID id;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, length = 200)
    private String eventType;

    @Column(nullable = false, length = 100)
    private String topic;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(length = 64)
    private String traceparent;   // Phase 12 예약(현재 미사용).

    @Column(nullable = false)
    private int attempts;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;   // NULL = 미발행.

    OutboxMessageJpaEntity(UUID id, UUID aggregateId, String eventType, String topic,
                           String payload, Instant createdAt) {
        this.id = id;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.topic = topic;
        this.payload = payload;
        this.createdAt = createdAt;
        this.attempts = 0;
    }

    /** 브로커 ack 를 받은 뒤에만 호출 — 이후 폴링에서 제외된다. */
    void markPublished(Instant when) {
        this.publishedAt = when;
    }

    /** 발행 실패 시 재시도 카운트(at-least-once). Phase 14 에서 임계치 초과 row 격리에 사용. */
    void recordFailedAttempt() {
        this.attempts++;
    }
}
