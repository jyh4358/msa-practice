package com.shopsaga.order.application.port.out;

import java.util.UUID;

/**
 * 아웃바운드 포트: 멱등 소비 지원(inbox 패턴). Phase 10에서 inventory에 도입한 것과 같은 장치를
 * Phase 12에서 order도 갖는다 — Saga 이벤트를 소비하게 됐기 때문이다.
 *
 * <p>dedup 조회·부수효과·처리기록이 <b>모두 같은 트랜잭션</b>이어야 effectively-once 가 성립한다.
 */
public interface ProcessedMessagePort {

    boolean isAlreadyProcessed(UUID messageId);

    void markProcessed(UUID messageId);
}
