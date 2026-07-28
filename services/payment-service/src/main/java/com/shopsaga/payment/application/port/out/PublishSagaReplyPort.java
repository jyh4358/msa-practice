package com.shopsaga.payment.application.port.out;

import com.shopsaga.events.commands.SagaReply;

/** 아웃바운드 포트: 조정자에게 결제 결과를 돌려준다(Phase 13). 성공이든 거절이든 반드시 응답한다. */
public interface PublishSagaReplyPort {

    void reply(SagaReply reply);
}
