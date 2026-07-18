-- Phase 9: 재고를 inventory-service(inventorydb)로 분리 → order 로컬 재고 테이블 제거.
-- (order는 이제 OrderPlaced 이벤트만 발행하고, inventory가 재고를 소유·예약한다.)
DROP TABLE stock_items;
