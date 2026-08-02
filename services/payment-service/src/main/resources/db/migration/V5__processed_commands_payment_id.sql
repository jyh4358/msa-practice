-- 감사(2026-08-02)에서 발견된 결함 수정: 재전송 리플라이의 paymentId 유실.
--
-- 문제: 중복 ChargePayment 를 만나면 저장해 둔 결과로 리플라이를 "재전송"하는데,
--   결과 테이블에 payment_id 가 없어 paymentId=null 로 보낼 수밖에 없었다.
--   조정자는 confirm(null) 에서 실패하고, 고아 결제 보상도 paymentId 가 없어 발동하지 못한다
--   → "청구는 됐는데 환불이 영영 안 되는" 경로가 열려 있었다.
-- 교훈: 멱등 재전송은 결과를 '재구성'하지 말고 '저장한 그대로' 보내야 한다.
ALTER TABLE processed_commands ADD COLUMN payment_id UUID;
