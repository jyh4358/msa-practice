-- Phase 9: inventory-service가 재고를 소유한다(order-service에서 분리).
-- product_id 가 자연키(앱 할당). 예약(차감)은 비관적 쓰기 락으로 보호한다.

CREATE TABLE stock_items (
    product_id         UUID    PRIMARY KEY,
    available_quantity INTEGER NOT NULL
);

-- 데모용 시드 재고(각 100개). 기존 order-service 시드와 동일한 상품 UUID.
INSERT INTO stock_items (product_id, available_quantity) VALUES
    ('22222222-2222-2222-2222-222222222222', 100),
    ('33333333-3333-3333-3333-333333333333', 100);
