package com.shopsaga.order.adapter.out.persistence;

import com.shopsaga.order.application.port.out.ProcessedMessagePort;
import com.shopsaga.outbox.ProcessedMessage;
import com.shopsaga.outbox.ProcessedMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/** 아웃바운드 어댑터: 공유 processed_messages 테이블로 멱등 dedup 을 구현한다(Phase 12). */
@Component
@RequiredArgsConstructor
class ProcessedMessagePersistenceAdapter implements ProcessedMessagePort {

    private static final String CONSUMER = "order-service";

    private final ProcessedMessageRepository repository;

    @Override
    public boolean isAlreadyProcessed(UUID messageId) {
        return repository.existsById(messageId);
    }

    @Override
    public void markProcessed(UUID messageId) {
        // 부수효과(상태 전이·이벤트 기록)와 같은 트랜잭션에서 저장된다.
        // 동시 재배달이 경합하면 PK 위반으로 한쪽이 롤백 → 전이도 함께 롤백 → 재배달 시 skip.
        repository.save(new ProcessedMessage(messageId, CONSUMER, Instant.now()));
    }
}
