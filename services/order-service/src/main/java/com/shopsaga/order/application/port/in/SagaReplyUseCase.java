package com.shopsaga.order.application.port.in;

import com.shopsaga.events.commands.SagaReply;

import java.util.UUID;

/**
 * 인바운드 포트: 참여 서비스의 리플라이를 받아 Saga를 다음 단계로 진행시킨다(Phase 13).
 *
 * <p>코레오그래피에서는 "누가 무엇을 듣는가"가 여러 서비스에 흩어져 있었다.
 * 여기서는 <b>이 메서드 하나가 Saga 전체의 분기점</b>이다 — 흐름을 읽으려면 여기만 보면 된다.
 */
public interface SagaReplyUseCase {

    void onReply(UUID messageId, SagaReply reply);
}
