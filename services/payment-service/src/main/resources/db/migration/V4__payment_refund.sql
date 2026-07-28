-- Phase 14: 고아 결제 보상(환불). Saga가 끝난 뒤 뒤늦게 성립한 결제를 되돌린 시각을 남긴다.
--
-- ⚠️ 결제 row 를 DELETE 하지 않는 이유: 돈이 실제로 움직인 사실은 지워지는 것이 아니다.
--    보상은 rollback 이 아니라 semantic undo — "취소된 결제"라는 상태로 남겨야 감사(audit)가 가능하다.
ALTER TABLE payments ADD COLUMN refunded_at TIMESTAMPTZ;

COMMENT ON COLUMN payments.refunded_at IS 'Phase 14: 환불(보상) 시각. NULL = 정상 결제.';
