-- Phase 10: 멱등 소비자용 dedup 테이블.
-- 목적: at-least-once 배달(릴레이 크래시·재시도로 같은 메시지가 두 번 올 수 있음)에서
--       부수효과(재고 예약)를 정확히 한 번만 적용한다 = effectively-once.
-- 소비 처리와 이 row 의 INSERT 를 같은 트랜잭션에 커밋한다 → 원자적 dedup.

CREATE TABLE processed_messages (
    message_id UUID         PRIMARY KEY,          -- 발행자(order outbox)의 id. 헤더 messageId 로 전달됨.
    consumer   VARCHAR(100) NOT NULL,             -- 소비자 식별(여러 그룹이 같은 토픽을 볼 때 구분).
    handled_at TIMESTAMPTZ  NOT NULL              -- 처리 시각.
);
