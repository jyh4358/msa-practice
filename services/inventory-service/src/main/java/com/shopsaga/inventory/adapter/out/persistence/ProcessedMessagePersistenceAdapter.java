package com.shopsaga.inventory.adapter.out.persistence;

import com.shopsaga.inventory.application.port.out.ProcessedMessagePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/** 아웃바운드 어댑터: processed_messages 테이블로 멱등 dedup 을 구현한다. */
@Component
@RequiredArgsConstructor
class ProcessedMessagePersistenceAdapter implements ProcessedMessagePort {

    private static final String CONSUMER = "inventory-service";

    private final ProcessedMessageJpaRepository repository;

    @Override
    public boolean isAlreadyProcessed(UUID messageId) {
        return repository.existsById(messageId);
    }

    @Override
    public void markProcessed(UUID messageId) {
        // 부수효과와 같은 트랜잭션에서 저장된다(StockService.reserveForOrder 가 @Transactional).
        // 동시 재배달이 경합하면 PK 위반으로 한쪽 트랜잭션이 롤백 → 예약도 함께 롤백 → 재배달 시 skip.
        repository.save(new ProcessedMessageJpaEntity(messageId, CONSUMER, Instant.now()));
    }
}
