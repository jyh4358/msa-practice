-- Phase 1 (모놀리스): order-service가 재고·결제를 직접 소유한다.
-- (Phase 2+에서 inventory-service / payment-service 로 분리되며 이 테이블들이 그쪽으로 이동한다.)

CREATE TABLE stock_items (
    product_id         UUID    PRIMARY KEY,
    available_quantity INTEGER NOT NULL
);

CREATE TABLE payments (
    id          UUID          PRIMARY KEY,
    order_id    UUID          NOT NULL UNIQUE REFERENCES orders(id) ON DELETE CASCADE,
    amount      NUMERIC(12,2) NOT NULL,
    status      VARCHAR(20)   NOT NULL,
    captured_at TIMESTAMPTZ   NOT NULL
);

CREATE INDEX idx_payments_order_id ON payments (order_id);

-- 데모용 시드 재고(각 100개). 테스트에 쓰던 상품 UUID.
INSERT INTO stock_items (product_id, available_quantity) VALUES
    ('22222222-2222-2222-2222-222222222222', 100),
    ('33333333-3333-3333-3333-333333333333', 100);
