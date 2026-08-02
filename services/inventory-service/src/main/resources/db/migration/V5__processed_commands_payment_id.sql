-- 공유 라이브러리(shared/outbox) ProcessedCommand 엔티티에 payment_id 가 추가됨(결제 리플라이 재전송 결함 수정).
-- 재고 커맨드에는 결제 id 개념이 없어 항상 NULL 이지만, ddl-auto=validate 가 스키마 일치를 요구한다.
ALTER TABLE processed_commands ADD COLUMN payment_id UUID;
