-- Phase 13(오케스트레이션): 커맨드 멱등 처리 테이블.
-- 결제에서는 이것이 "이중 청구 방지"의 최후 방어선이다.
-- 타임아웃 sweep이 ChargePayment 를 재전송해도 메시지 id가 아니라 (sagaId+커맨드타입) 키로 판단하므로
-- 이미 청구한 건은 다시 청구하지 않고, 저장해 둔 결과로 같은 리플라이만 다시 보낸다.

CREATE TABLE processed_commands (
    command_key UUID         PRIMARY KEY,
    saga_id     UUID         NOT NULL,
    order_id    UUID         NOT NULL,
    reply_kind  VARCHAR(40)  NOT NULL,
    reason      VARCHAR(500),
    handled_at  TIMESTAMPTZ  NOT NULL
);
