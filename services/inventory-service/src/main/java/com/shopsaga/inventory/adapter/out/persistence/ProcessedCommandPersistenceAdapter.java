package com.shopsaga.inventory.adapter.out.persistence;

import com.shopsaga.events.commands.SagaReply;
import com.shopsaga.inventory.application.port.out.ProcessedCommandPort;
import com.shopsaga.outbox.ProcessedCommand;
import com.shopsaga.outbox.ProcessedCommandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 아웃바운드 어댑터: processed_commands 로 커맨드 멱등 처리(결과까지 보관 — 재전송 시 같은 응답). */
@Component
@RequiredArgsConstructor
class ProcessedCommandPersistenceAdapter implements ProcessedCommandPort {

    private final ProcessedCommandRepository repository;

    @Override
    public Optional<PriorOutcome> findOutcome(UUID commandKey) {
        return repository.findById(commandKey)
                .map(pc -> new PriorOutcome(SagaReply.Kind.valueOf(pc.getReplyKind()), pc.getReason()));
    }

    @Override
    public void record(UUID commandKey, UUID sagaId, UUID orderId, SagaReply.Kind kind, String reason) {
        repository.save(new ProcessedCommand(commandKey, sagaId, orderId, kind.name(), reason, Instant.now()));
    }
}
