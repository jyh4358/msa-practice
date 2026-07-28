package com.shopsaga.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopsaga.events.Topics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.messaging.Message;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 14: 릴레이의 <b>재시도 상한과 격리</b> 회귀 가드.
 *
 * <p>지키려는 것은 두 가지다.
 * <ol>
 *   <li>발행 실패는 {@code published_at} 을 남기지 않아 다시 시도된다(at-least-once, Phase 10).</li>
 *   <li>단, 상한을 넘긴 row 는 <b>조회 자체에서 빠져</b> 배치 앞자리를 영구 점유하지 못한다(Phase 14).</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    private static final int MAX_ATTEMPTS = 3;

    @Mock
    OutboxRepository repository;
    @Mock
    KafkaTemplate<String, Object> kafkaTemplate;

    OutboxRelay relay;

    @BeforeEach
    void setUp() {
        // 트레이서/전파기는 선택적 — 없어도 신뢰성 동작은 그대로여야 한다.
        relay = new OutboxRelay(repository, kafkaTemplate, new ObjectMapper().findAndRegisterModules(),
                null, null, MAX_ATTEMPTS);
    }

    private OutboxMessage message() {
        return new OutboxMessage(UUID.randomUUID(), UUID.randomUUID(),
                "java.lang.String", Topics.ORDER_EVENTS, "\"hello\"", null, Instant.now());
    }

    @Test
    void polls_onlyMessagesBelowAttemptLimit() {
        when(repository.findTop100ByPublishedAtIsNullAndAttemptsLessThanOrderByCreatedAtAsc(MAX_ATTEMPTS))
                .thenReturn(List.of());

        relay.relay();

        // ★ 상한을 인자로 넘겨 조회한다 = 격리된 row 는 애초에 배치에 들어오지 않는다.
        verify(repository).findTop100ByPublishedAtIsNullAndAttemptsLessThanOrderByCreatedAtAsc(MAX_ATTEMPTS);
        verify(kafkaTemplate, never()).send(any(Message.class));
    }

    @Test
    void marksPublished_onlyAfterBrokerAck() {
        OutboxMessage msg = message();
        when(repository.findTop100ByPublishedAtIsNullAndAttemptsLessThanOrderByCreatedAtAsc(MAX_ATTEMPTS))
                .thenReturn(List.of(msg));
        when(kafkaTemplate.send(any(Message.class)))
                .thenReturn(CompletableFuture.completedFuture((SendResult<String, Object>) null));

        relay.relay();

        ArgumentCaptor<Message<Object>> sent = ArgumentCaptor.captor();
        verify(kafkaTemplate).send(sent.capture());
        assertThat(sent.getValue().getHeaders().get(OutboxRelay.HEADER_MESSAGE_ID)).isEqualTo(msg.getId().toString());
        assertThat(msg.getPublishedAt()).isNotNull();
        assertThat(msg.getAttempts()).isZero();
    }

    @Test
    void publishFailure_leavesUnpublished_andCountsAttempt() {
        OutboxMessage msg = message();
        when(repository.findTop100ByPublishedAtIsNullAndAttemptsLessThanOrderByCreatedAtAsc(MAX_ATTEMPTS))
                .thenReturn(List.of(msg));
        when(kafkaTemplate.send(any(Message.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("브로커 없음")));

        relay.relay();

        // 발행 실패 → published_at 은 그대로 NULL 이라 다음 폴링에서 재시도된다.
        assertThat(msg.getPublishedAt()).isNull();
        assertThat(msg.getAttempts()).isEqualTo(1);
    }

    @Test
    void repeatedFailure_reachesLimit_andIsExcludedFromNextPoll() {
        OutboxMessage msg = message();
        when(repository.findTop100ByPublishedAtIsNullAndAttemptsLessThanOrderByCreatedAtAsc(MAX_ATTEMPTS))
                .thenReturn(List.of(msg));
        when(kafkaTemplate.send(any(Message.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("영구 실패")));

        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            relay.relay();
        }

        assertThat(msg.getAttempts()).isEqualTo(MAX_ATTEMPTS);
        // 이제 이 row 는 attempts < MAX 조건에 걸리지 않는다 = 격리(사람이 볼 때까지 DB에 남는다).
        assertThat(msg.getPublishedAt()).isNull();
    }
}
