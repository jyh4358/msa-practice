-- Phase 2: 결제를 payment-service로 분리. order-service는 paymentId(참조)만 보관하고
-- 로컬 payments 테이블을 제거한다.
ALTER TABLE orders ADD COLUMN payment_id UUID;
DROP TABLE payments;
