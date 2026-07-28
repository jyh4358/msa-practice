package com.shopsaga.outbox;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxMessage, UUID> {

    /**
     * 미발행 outbox row 를 오래된 순서로 최대 100건 — 릴레이 폴링용(idx_outbox_unpublished 사용).
     *
     * <p><b>Phase 14: {@code attempts} 상한을 조건에 넣었다.</b> 어떤 row 가 구조적으로 발행 불가라면
     * (페이로드가 브로커 최대 메시지 크기를 넘음, 토픽이 없음 등) 아무리 재시도해도 실패한다.
     * 그런 row 를 계속 집어 오면 매 폴링마다 배치 앞자리를 차지해 <b>뒤의 정상 row 가 밀린다</b>
     * — 소비 쪽 poison pill 과 똑같은 문제가 발행 쪽에서 일어나는 것이다.
     * 상한을 넘긴 row 는 대상에서 제외해(= 격리) 나머지가 계속 흐르게 한다.
     */
    List<OutboxMessage> findTop100ByPublishedAtIsNullAndAttemptsLessThanOrderByCreatedAtAsc(int maxAttempts);

    /** 상한을 넘겨 격리된(사람이 봐야 하는) row 수 — 게이지 메트릭 {@code outbox.stuck} 의 소스. */
    long countByPublishedAtIsNullAndAttemptsGreaterThanEqual(int maxAttempts);
}
