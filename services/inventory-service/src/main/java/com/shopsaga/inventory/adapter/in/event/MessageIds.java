package com.shopsaga.inventory.adapter.in.event;

import java.util.UUID;

/**
 * 소비 어댑터 공통 헬퍼: dedup 키로 쓸 messageId 를 결정한다.
 * 헤더가 없으면(수동 발행 등) 애그리거트 id 로 대체해 그래도 멱등하게 만든다.
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
