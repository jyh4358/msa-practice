-- 공유 라이브러리(shared/outbox) ProcessedCommand 엔티티에 payment_id 가 추가됨(결제 리플라이 재전송 결함 수정).
-- order-service 는 이 테이블을 실제로 쓰지 않지만(V8 주석 참고), @EntityScan + ddl-auto=validate 가
-- 스키마 일치를 요구하므로 함께 맞춘다.
ALTER TABLE processed_commands ADD COLUMN payment_id UUID;
