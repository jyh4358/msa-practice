package com.shopsaga.payment.application.port.out;

import com.shopsaga.events.commands.SagaReply;

import java.util.Optional;
import java.util.UUID;

/**
 * 아웃바운드 포트: 커맨드 멱등 처리(Phase 13).
 *
 * <p>결제에서 이 장치는 <b>이중 청구를 막는 최후 방어선</b>이다. 타임아웃 sweep이 커맨드를 재전송하면
 * 메시지 id가 새로 생기므로 메시지 기반 dedup으로는 못 막는다 — 결정적 커맨드 키로 판단해야 한다.
 */
public interface ProcessedCommandPort {

    Optional<PriorOutcome> findOutcome(UUID commandKey);

    void record(UUID commandKey, UUID sagaId, UUID orderId, SagaReply.Kind kind, String reason);

    record PriorOutcome(SagaReply.Kind kind, String reason) {}
}
