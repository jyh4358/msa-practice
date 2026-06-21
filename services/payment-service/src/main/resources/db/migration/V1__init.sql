-- Phase 2: payment-service가 결제를 소유한다(order-service에서 분리).
CREATE TABLE payments (
    id          UUID          PRIMARY KEY,
    order_id    UUID          NOT NULL,
    amount      NUMERIC(12,2) NOT NULL,
    status      VARCHAR(20)   NOT NULL,
    captured_at TIMESTAMPTZ   NOT NULL
);

CREATE INDEX idx_payments_order_id ON payments (order_id);
