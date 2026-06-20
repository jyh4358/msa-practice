-- Phase 0: order-service가 소유하는 테이블 (database-per-service 원칙)
-- 스키마는 코드다 — Flyway가 버전 관리한다. ddl-auto=validate 와 정확히 일치해야 한다.

CREATE TABLE orders (
    id            UUID          PRIMARY KEY,
    customer_id   UUID          NOT NULL,
    status        VARCHAR(30)   NOT NULL,
    total_amount  NUMERIC(12,2) NOT NULL,
    created_at    TIMESTAMPTZ   NOT NULL
);

CREATE TABLE order_items (
    id          UUID          PRIMARY KEY,
    order_id    UUID          NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    product_id  UUID          NOT NULL,
    quantity    INTEGER       NOT NULL,
    unit_price  NUMERIC(12,2) NOT NULL
);

CREATE INDEX idx_order_items_order_id ON order_items (order_id);
