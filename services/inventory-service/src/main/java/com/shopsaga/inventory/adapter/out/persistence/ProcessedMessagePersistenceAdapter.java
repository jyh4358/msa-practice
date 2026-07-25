package com.shopsaga.inventory.adapter.out.persistence;

import com.shopsaga.inventory.application.port.out.ProcessedMessagePort;
import com.shopsaga.outbox.ProcessedMessage;
import com.shopsaga.outbox.ProcessedMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * 아웃바운드 어댑터: processed_messages 테이블로 멱등 dedup 을 구현한다.
 * Phase 12에서 엔티티·리포지토리를 공유 라이브러리(`shared/outbox`)로 옮겼고, 이 어댑터만 서비스에 남는다
 * (포트 구현 = 서비스의 책임, 저장 메커니즘 = 공유 코드).
 */
@Component
@RequiredArgsConstructor
class ProcessedMessagePersistenceAdapter implements ProcessedMessagePort {

    private static final String CONSUMER = "inventory-service";

    private final ProcessedMessageRepository repository;

    @Override
    public boolean isAlreadyProcessed(UUID messageId) {
        return repository.existsById(messageId);
    }

    @Override
    public void markProcessed(UUID messageId) {
        // 부수효과와 같은 트랜잭션에서 저장된다.
        // 동시 재배달이 경합하면 PK 위반으로 한쪽 트랜잭션이 롤백 → 예약도 함께 롤백 → 재배달 시 skip.
        repository.save(new ProcessedMessage(messageId, CONSUMER, Instant.now()));
    }
}
