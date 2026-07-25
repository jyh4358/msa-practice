package com.shopsaga.payment.adapter.out.persistence;

import com.shopsaga.outbox.ProcessedMessage;
import com.shopsaga.outbox.ProcessedMessageRepository;
import com.shopsaga.payment.application.port.out.ProcessedMessagePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/** 아웃바운드 어댑터: processed_messages 테이블로 멱등 dedup(이중 청구 방지). */
@Component
@RequiredArgsConstructor
class ProcessedMessagePersistenceAdapter implements ProcessedMessagePort {

    private static final String CONSUMER = "payment-service";

    private final ProcessedMessageRepository repository;

    @Override
    public boolean isAlreadyProcessed(UUID messageId) {
        return repository.existsById(messageId);
    }

    @Override
    public void markProcessed(UUID messageId) {
        // 결제 저장·이벤트 기록과 같은 트랜잭션 → 부분 상태 없음.
        repository.save(new ProcessedMessage(messageId, CONSUMER, Instant.now()));
    }
}
