package com.shopsaga.inventory.application.port.out;

import java.util.UUID;

/**
 * Phase 10: 멱등 소비 지원 아웃바운드 포트.
 *
 * <p>애플리케이션은 "이 메시지를 이미 처리했는가?"를 물어보고, 처리 후 "처리했음"을 기록한다.
 * 저장 기술(processed_messages 테이블)은 어댑터의 세부사항이다. dedup 조회·기록·실제 부수효과는
 * 모두 같은 트랜잭션에서 일어나야 effectively-once 가 성립한다.
 */
public interface ProcessedMessagePort {

    boolean isAlreadyProcessed(UUID messageId);

    void markProcessed(UUID messageId);
}
