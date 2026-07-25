package com.shopsaga.outbox;

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
 * Phase 10에서 order-service에 만든 outbox 엔티티를 Phase 12에서 <b>공유 라이브러리로 승격</b>한 것.
 * (Saga가 되면서 inventory·payment도 이벤트를 발행해야 하므로 같은 메커니즘이 3곳에 필요해졌다.)
 *
 * <p>각 서비스는 <b>자기 DB의 자기 outbox 테이블</b>에 이 엔티티를 매핑한다 — 테이블을 공유하는 게 아니다.
 *
 * <p>{@code id} 는 곧 messageId 로, 소비자의 멱등 dedup 키가 된다(같은 row 재발행 시 동일).
 */
@Entity
@Table(name = "outbox")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxMessage {

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

    /**
     * Phase 12: W3C 트레이스 컨텍스트(예: {@code 00-<32hex traceId>-<16hex spanId>-01}, 55자).
     *
     * <p>릴레이는 요청 스레드가 아닌 별도 스레드에서 <b>나중에</b> 발행하므로 원 요청의 트레이스 컨텍스트가 없다.
     * 그래서 기록 시점의 컨텍스트를 여기 저장했다가 발행 시 <b>복원</b>한다 → Saga 전체가 한 트레이스로 이어진다.
     * (Phase 10에서 컬럼만 만들어 두고 비워 뒀던 자리를 이제 채운다.)
     */
    @Column(length = 64)
    private String traceparent;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;   // NULL = 미발행

    public OutboxMessage(UUID id, UUID aggregateId, String eventType, String topic,
                         String payload, String traceparent, Instant createdAt) {
        this.id = id;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.topic = topic;
        this.payload = payload;
        this.traceparent = traceparent;
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
