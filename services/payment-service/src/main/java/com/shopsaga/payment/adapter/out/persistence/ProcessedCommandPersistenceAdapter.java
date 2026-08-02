package com.shopsaga.payment.adapter.out.persistence;

import com.shopsaga.events.commands.SagaReply;
import com.shopsaga.outbox.ProcessedCommand;
import com.shopsaga.outbox.ProcessedCommandRepository;
import com.shopsaga.payment.application.port.out.ProcessedCommandPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 아웃바운드 어댑터: processed_commands 로 커맨드 멱등 처리(이중 청구 방지). */
@Component
@RequiredArgsConstructor
class ProcessedCommandPersistenceAdapter implements ProcessedCommandPort {

    private final ProcessedCommandRepository repository;

    @Override
    public Optional<PriorOutcome> findOutcome(UUID commandKey) {
        return repository.findById(commandKey)
                .map(pc -> new PriorOutcome(SagaReply.Kind.valueOf(pc.getReplyKind()),
                        pc.getPaymentId(), pc.getReason()));
    }

    @Override
    public void record(UUID commandKey, UUID sagaId, UUID orderId, SagaReply.Kind kind,
                       UUID paymentId, String reason) {
        repository.save(new ProcessedCommand(commandKey, sagaId, orderId, kind.name(),
                paymentId, reason, Instant.now()));
    }
}
