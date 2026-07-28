-- Phase 13(오케스트레이션): 커맨드 멱등 처리 테이블.
--
-- Phase 10의 processed_messages 로는 부족한 이유:
--   조정자의 타임아웃 sweep이 커맨드를 "재전송"하면 메시지 id가 새로 생겨 dedup을 통과해 버린다
--   → 같은 지시인데 재고를 두 번 잡게 된다.
-- 그래서 (sagaId + 커맨드 타입)에서 결정적으로 유도한 키로 판단한다.
--
-- 결과(reply_kind/reason)까지 저장하는 이유:
--   중복 커맨드를 조용히 무시하면 조정자는 리플라이를 영영 못 받는다.
--   저장해 둔 결과로 "같은 응답"을 다시 보내 Saga를 진행시켜야 한다.

CREATE TABLE processed_commands (
    command_key UUID         PRIMARY KEY,          -- CommandKeys.of(sagaId, 커맨드타입)
    saga_id     UUID         NOT NULL,
    order_id    UUID         NOT NULL,
    reply_kind  VARCHAR(40)  NOT NULL,             -- 이 커맨드에 대해 보낸 리플라이 종류
    reason      VARCHAR(500),                      -- 실패 사유(성공 시 NULL)
    handled_at  TIMESTAMPTZ  NOT NULL
);
