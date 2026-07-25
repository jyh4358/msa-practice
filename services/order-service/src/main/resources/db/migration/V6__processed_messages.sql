-- Phase 12(Saga): order-service도 이벤트를 소비하게 되어 멱등 dedup 테이블이 필요해졌다.
-- (Phase 10에서 inventory에 만든 것과 동일한 구조 — 이제 공유 라이브러리의 엔티티가 매핑된다.)
-- 소비 처리(상태 전이 + 결과 이벤트 outbox 기록)와 이 row 의 INSERT 를 같은 트랜잭션에 커밋한다.

CREATE TABLE processed_messages (
    message_id UUID         PRIMARY KEY,          -- 발행자 outbox row 의 id. 헤더 messageId 로 전달됨.
    consumer   VARCHAR(100) NOT NULL,
    handled_at TIMESTAMPTZ  NOT NULL
);
