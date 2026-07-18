-- Phase 10: 트랜잭셔널 Outbox.
-- 목적: 주문 row(orders)와 "이벤트 발행 의도"를 같은 트랜잭션에 원자적으로 기록한다.
--       (이전 Phase 9a의 이중 쓰기 = save()+kafka.send() 는 원자적이지 않아 유실/유령 이벤트가 가능했다.)
-- 별도 @Scheduled 릴레이가 미발행(published_at IS NULL) row 를 Kafka 로 발행한다(at-least-once).

CREATE TABLE outbox (
    id            UUID          PRIMARY KEY,            -- = messageId. 소비자 dedup 키(같은 row 재발행 시 동일).
    aggregate_id  UUID          NOT NULL,               -- 이벤트가 속한 애그리거트(주문 id) → Kafka 메시지 key.
    event_type    VARCHAR(200)  NOT NULL,               -- 이벤트 타입(FQCN) — 릴레이가 payload 역직렬화에 사용.
    topic         VARCHAR(100)  NOT NULL,               -- 발행 대상 토픽.
    payload       JSONB         NOT NULL,               -- 직렬화된 이벤트(JSON).
    traceparent   VARCHAR(64),                          -- ★ Phase 12 예약: 릴레이 트레이스 전파용(현재 미사용/NULL).
    attempts      INTEGER       NOT NULL DEFAULT 0,     -- ★ Phase 14 예약: 발행 실패 누적(poison row 격리용). Phase 10부터 카운트.
    created_at    TIMESTAMPTZ   NOT NULL,               -- 기록 시각(발행 순서 보존).
    published_at  TIMESTAMPTZ                           -- NULL = 미발행. 릴레이가 브로커 ack 후 채운다.
);

-- 릴레이 폴링(미발행 row 를 오래된 순서로)을 위한 부분 인덱스 — 이미 발행된 row 는 인덱스에서 제외.
CREATE INDEX idx_outbox_unpublished ON outbox (created_at) WHERE published_at IS NULL;
