package com.shopsaga.inventory.application.port.out;

import com.shopsaga.events.commands.SagaReply;

import java.util.Optional;
import java.util.UUID;

/**
 * 아웃바운드 포트: 커맨드 멱등 처리(Phase 13).
 *
 * <p>메시지 id가 아니라 <b>결정적 커맨드 키</b>로 판단한다 — 타임아웃 sweep이 재전송하면 메시지 id는 새로 생기지만,
 * 같은 (sagaId, 커맨드 타입)이면 키는 같기 때문이다.
 *
 * <p>또한 중복일 때 <b>이전에 보낸 리플라이 종류</b>를 돌려준다 — 조용히 무시하면 조정자가 영영 기다리므로,
 * 같은 응답을 다시 보내 진행시켜야 한다.
 */
public interface ProcessedCommandPort {

    /** 이미 처리한 커맨드면 그때 보낸 리플라이 종류와 사유를 반환. */
    Optional<PriorOutcome> findOutcome(UUID commandKey);

    void record(UUID commandKey, UUID sagaId, UUID orderId, SagaReply.Kind kind, String reason);

    record PriorOutcome(SagaReply.Kind kind, String reason) {}
}
