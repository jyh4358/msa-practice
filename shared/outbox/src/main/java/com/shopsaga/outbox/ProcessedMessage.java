package com.shopsaga.outbox;

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
 * Phase 10에서 inventory-service에 만든 dedup 테이블을 Phase 12에서 공유 라이브러리로 승격한 것
 * (Saga가 되면서 order·payment도 이벤트를 소비하므로 같은 멱등 장치가 3곳에 필요해졌다).
 *
 * <p>outbox 가 "보내는 쪽"의 신뢰성 장치라면, 이건 "받는 쪽"의 장치다(inbox 패턴).
 * {@code message_id} 가 PK 라서 같은 메시지의 두 번째 INSERT 는 PK 위반으로 막힌다 —
 * 부수효과와 같은 트랜잭션에 커밋하므로 <b>효과는 정확히 한 번</b>(effectively-once).
 */
@Entity
@Table(name = "processed_messages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProcessedMessage {

    @Id
    @Column(name = "message_id")
    private UUID messageId;

    @Column(nullable = false, length = 100)
    private String consumer;

    @Column(name = "handled_at", nullable = false)
    private Instant handledAt;

    public ProcessedMessage(UUID messageId, String consumer, Instant handledAt) {
        this.messageId = messageId;
        this.consumer = consumer;
        this.handledAt = handledAt;
    }
}
