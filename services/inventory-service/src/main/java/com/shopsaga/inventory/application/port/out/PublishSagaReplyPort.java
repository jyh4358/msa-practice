package com.shopsaga.inventory.application.port.out;

import com.shopsaga.events.commands.SagaReply;

/**
 * 아웃바운드 포트: 조정자에게 커맨드 처리 결과를 돌려준다(Phase 13).
 *
 * <p>참여 서비스의 <b>의무</b>다 — 성공이든 실패든 반드시 응답해야 조정자가 다음 단계로 갈 수 있다.
 * 응답하지 않으면 그 Saga는 정체되고, 조정자의 타임아웃 sweep이 개입하게 된다.
 */
public interface PublishSagaReplyPort {

    void reply(SagaReply reply);
}
