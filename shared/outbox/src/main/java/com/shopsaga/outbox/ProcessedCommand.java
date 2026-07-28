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
 * Phase 13: 참여 서비스가 <b>처리한 커맨드와 그 결과</b>를 기록한다.
 *
 * <p><b>왜 Phase 10의 {@code processed_messages} 로 부족한가:</b> 그건 메시지 id로 중복을 걸렀다.
 * 그런데 오케스트레이터의 <b>타임아웃 sweep이 커맨드를 재전송</b>하면 메시지 id가 <b>새로 생긴다</b> —
 * 같은 지시인데 dedup에 걸리지 않아 재고를 두 번 잡게 된다.
 * 그래서 (sagaId + 커맨드 타입)에서 <b>결정적으로 유도한 키</b>로 중복을 판단한다({@link CommandKeys}).
 *
 * <p>또 하나 중요한 점: 중복 커맨드를 만나면 <b>조용히 무시하면 안 된다</b>.
 * 조정자는 리플라이를 기다리고 있으므로, 저장해 둔 결과로 <b>같은 리플라이를 다시 보내</b> 진행시켜야 한다.
 * 그래서 결과(kind·reason)까지 함께 보관한다.
 */
@Entity
@Table(name = "processed_commands")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProcessedCommand {

    @Id
    @Column(name = "command_key")
    private UUID commandKey;

    @Column(name = "saga_id", nullable = false)
    private UUID sagaId;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    /** 이 커맨드에 대해 보낸 리플라이 종류(재전송 시 그대로 다시 보낸다). */
    @Column(name = "reply_kind", nullable = false, length = 40)
    private String replyKind;

    @Column(length = 500)
    private String reason;

    @Column(name = "handled_at", nullable = false)
    private Instant handledAt;

    public ProcessedCommand(UUID commandKey, UUID sagaId, UUID orderId,
                            String replyKind, String reason, Instant handledAt) {
        this.commandKey = commandKey;
        this.sagaId = sagaId;
        this.orderId = orderId;
        this.replyKind = replyKind;
        this.reason = reason;
        this.handledAt = handledAt;
    }
}
