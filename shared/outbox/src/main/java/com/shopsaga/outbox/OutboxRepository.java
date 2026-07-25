package com.shopsaga.outbox;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxMessage, UUID> {

    /** 미발행 outbox row 를 오래된 순서로 최대 100건 — 릴레이 폴링용(idx_outbox_unpublished 사용). */
    List<OutboxMessage> findTop100ByPublishedAtIsNullOrderByCreatedAtAsc();
}
