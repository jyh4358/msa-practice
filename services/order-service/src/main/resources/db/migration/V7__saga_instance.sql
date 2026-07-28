-- Phase 13(오케스트레이션): Saga 인스턴스 상태 테이블.
--
-- 이 테이블이 Phase 13의 핵심 산출물이다. 코레오그래피(Phase 12)에서는 "이 주문이 지금 어디까지 갔나"를
-- 알려면 여러 서비스의 로그를 뒤져야 했지만, 이제는 한 줄이면 된다:
--     SELECT state FROM saga_instance WHERE order_id = '...';

CREATE TABLE saga_instance (
    saga_id    UUID        PRIMARY KEY,
    order_id   UUID        NOT NULL,
    state      VARCHAR(40) NOT NULL,   -- STARTED→AWAITING_INVENTORY→AWAITING_PAYMENT→COMPLETED
                                       -- / COMPENSATING_INVENTORY→CANCELLED
    attempts   INTEGER     NOT NULL DEFAULT 0,   -- 현재 단계에서 커맨드를 보낸 횟수(sweep 재전송 포함)
    updated_at TIMESTAMPTZ NOT NULL              -- 마지막 전이 시각 → 정체 판단의 기준
);

-- 주문으로 Saga를 찾는 조회(운영/디버깅에서 가장 자주 쓴다).
CREATE UNIQUE INDEX idx_saga_instance_order_id ON saga_instance (order_id);

-- 타임아웃 sweep 전용 인덱스: "응답 대기 중인데 오래 안 움직인 것"만 훑는다.
-- 종료된 Saga(COMPLETED/CANCELLED)는 부분 인덱스에서 아예 제외되므로 sweep 비용이 데이터 총량과 무관해진다.
CREATE INDEX idx_saga_instance_stalled ON saga_instance (updated_at)
    WHERE state IN ('AWAITING_INVENTORY', 'AWAITING_PAYMENT', 'COMPENSATING_INVENTORY');
