-- Phase 12(Saga): payment-service가 이벤트를 소비·발행하게 되어 outbox·inbox 테이블이 필요해졌다.
-- (order/inventory와 같은 구조 — 테이블은 서비스별로 각자 자기 DB에 있다. 공유되는 건 코드뿐.)

CREATE TABLE outbox (
    id            UUID          PRIMARY KEY,            -- = messageId. 소비자 dedup 키.
    aggregate_id  UUID          NOT NULL,               -- 주문 id → Kafka 메시지 key.
    event_type    VARCHAR(200)  NOT NULL,
    topic         VARCHAR(100)  NOT NULL,
    payload       JSONB         NOT NULL,
    traceparent   VARCHAR(64),                          -- W3C 트레이스 컨텍스트(릴레이가 복원 → Saga 한 트레이스).
    attempts      INTEGER       NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ   NOT NULL,
    published_at  TIMESTAMPTZ                           -- NULL = 미발행.
);

CREATE INDEX idx_outbox_unpublished ON outbox (created_at) WHERE published_at IS NULL;

-- 멱등 소비(inbox): 같은 InventoryReserved 가 두 번 배달돼도 결제는 한 번만.
CREATE TABLE processed_messages (
    message_id UUID         PRIMARY KEY,
    consumer   VARCHAR(100) NOT NULL,
    handled_at TIMESTAMPTZ  NOT NULL
);
