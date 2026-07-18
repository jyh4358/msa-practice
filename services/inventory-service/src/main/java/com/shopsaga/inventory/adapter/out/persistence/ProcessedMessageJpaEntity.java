package com.shopsaga.inventory.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 10: 멱등 소비자 dedup 레코드(JPA). message_id 가 PK 라 같은 메시지의 두 번째 INSERT 는
 * PK 위반으로 막힌다(부수효과와 같은 트랜잭션 → 이중 예약 없음).
 */
@Entity
@Table(name = "processed_messages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class ProcessedMessageJpaEntity {

    @Id
    @Column(name = "message_id")
    private UUID messageId;

    @Column(nullable = false, length = 100)
    private String consumer;

    @Column(name = "handled_at", nullable = false)
    private Instant handledAt;

    ProcessedMessageJpaEntity(UUID messageId, String consumer, Instant handledAt) {
        this.messageId = messageId;
        this.consumer = consumer;
        this.handledAt = handledAt;
    }
}
