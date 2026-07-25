-- Phase 12(Saga): inventory-service도 이벤트를 발행하게 되어 자기 outbox 테이블이 필요해졌다.
-- (order-service의 V5__outbox.sql 과 같은 구조 — 공유 라이브러리의 엔티티가 매핑되지만 테이블은 서비스별로 따로다.)

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

-- 보상을 위한 예약 원장(ledger).
-- 결제가 거절되면 "그 주문에 대해 무엇을 얼마나 잡아뒀는지" 알아야 되돌릴 수 있다.
-- PaymentDeclined 이벤트에는 orderId·금액만 있으므로, 예약 내역은 inventory가 스스로 기억해야 한다.
-- 해제 시 row 를 삭제한다 → 두 번 해제해도 되돌릴 게 없어 자연히 멱등해진다.
CREATE TABLE stock_reservations (
    order_id   UUID    NOT NULL,
    product_id UUID    NOT NULL,
    quantity   INTEGER NOT NULL,
    PRIMARY KEY (order_id, product_id)
);
