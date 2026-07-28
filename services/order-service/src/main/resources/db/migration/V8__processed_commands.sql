-- Phase 13: 공유 라이브러리(shared/outbox)의 스키마 계약을 맞춘다.
--
-- ⚠️ order-service 는 조정자라서 커맨드를 '보내는' 쪽이고 '받지' 않는다 → 이 테이블을 실제로 쓰지는 않는다.
--    그런데도 만드는 이유: @EntityScan("com.shopsaga.outbox") 는 그 패키지의 엔티티를 '전부' 스캔하므로
--    Hibernate 의 ddl-auto=validate 가 processed_commands 의 부재를 기동 실패로 판정한다.
--    (실제로 이걸 빠뜨려 order-service 가 크래시 루프에 빠졌다.)
--
-- 대안: 라이브러리를 하위 패키지로 쪼개 서비스별로 필요한 것만 스캔하는 방법도 있다.
--       여기서는 "라이브러리를 쓰면 그 테이블 세트를 모두 갖춘다"는 단순한 규칙을 택했다 —
--       빈 테이블 하나의 비용보다 규칙의 단순함이 학습·운영에 유리하다고 판단.

CREATE TABLE processed_commands (
    command_key UUID         PRIMARY KEY,
    saga_id     UUID         NOT NULL,
    order_id    UUID         NOT NULL,
    reply_kind  VARCHAR(40)  NOT NULL,
    reason      VARCHAR(500),
    handled_at  TIMESTAMPTZ  NOT NULL
);
