package com.shopsaga.order.adapter.in.event;

import java.util.UUID;

/**
 * 소비 어댑터 공통 헬퍼: dedup 키로 쓸 messageId 를 결정한다.
 *
 * <p>정상 경로에서는 발행자의 outbox 릴레이가 실어 보낸 {@code messageId} 헤더를 쓴다.
 * 헤더가 없는 메시지(수동 발행·외부 도구 주입 등)는 애그리거트 id 로 대체해 <b>그래도 멱등</b>하게 만든다.
 */
final class MessageIds {

    static UUID resolve(String header, UUID fallbackAggregateId) {
        if (header == null || header.isBlank()) {
            return fallbackAggregateId;
        }
        return UUID.fromString(header);
    }

    private MessageIds() {
    }
}
