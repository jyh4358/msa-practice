package com.shopsaga.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Phase 12: outbox 메커니즘을 쓰는 서비스가 {@code @Import(OutboxConfiguration.class)} 로 켜는 설정.
 *
 * <p>⚠️ 이 설정은 <b>빈만</b> 등록한다. JPA 엔티티/리포지토리 스캔은 여기서 하지 않는다 —
 * {@code @EntityScan}/{@code @EnableJpaRepositories} 는 Boot 의 기본 스캔(= 앱 패키지)을 <b>대체</b>하므로,
 * 공유 라이브러리에서 선언하면 그 서비스 자신의 엔티티가 스캔에서 빠져 버린다.
 * 그래서 각 서비스의 애플리케이션 클래스에서 <b>자기 패키지와 outbox 패키지를 함께</b> 명시한다:
 *
 * <pre>{@code
 * @SpringBootApplication
 * @EntityScan({"com.shopsaga.order", "com.shopsaga.outbox"})
 * @EnableJpaRepositories({"com.shopsaga.order", "com.shopsaga.outbox"})
 * @Import(OutboxConfiguration.class)
 * }</pre>
 *
 * <p>트레이싱 빈({@link Tracer}·{@link Propagator})은 <b>있으면 쓰고 없으면 건너뛴다</b>({@link ObjectProvider}).
 * 관측성이 꺼진 환경(테스트의 {@code @SpringBootTest} 는 tracing 을 비활성화한다)에서도 outbox 는 동작해야 하기 때문이다 —
 * 신뢰성(원자성·at-least-once)은 트레이싱과 무관한 관심사다.
 */
@Configuration
@EnableScheduling   // 릴레이의 @Scheduled 폴링 활성화
public class OutboxConfiguration {

    @Bean
    public OutboxWriter outboxWriter(OutboxRepository repository, ObjectMapper objectMapper,
                                     ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
        return new OutboxWriter(repository, objectMapper,
                tracer.getIfAvailable(), propagator.getIfAvailable());
    }

    @Bean
    public OutboxRelay outboxRelay(OutboxRepository repository, KafkaTemplate<String, Object> kafkaTemplate,
                                   ObjectMapper objectMapper,
                                   ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator,
                                   @Value("${outbox.relay.max-attempts:5}") int maxAttempts) {
        return new OutboxRelay(repository, kafkaTemplate, objectMapper,
                tracer.getIfAvailable(), propagator.getIfAvailable(), maxAttempts);
    }

    /**
     * Phase 14: 격리된(재시도 상한 초과) outbox row 수를 게이지로 노출한다.
     *
     * <p>격리는 "조용히 버리는" 것이 아니다 — 버려도 되는 이벤트란 없다. 릴레이는 그 row 를 건너뛰어
     * 나머지를 흐르게 하되, <b>사람이 알아챌 수 있도록</b> 숫자를 남긴다. 이 값이 0 보다 크면 경보 대상이다.
     * (Grafana 에서 {@code outbox_stuck} 로 조회. 메트릭 레지스트리가 없으면 조용히 건너뛴다.)
     */
    @Bean
    public MeterBinder outboxStuckGauge(OutboxRepository repository,
                                        @Value("${outbox.relay.max-attempts:5}") int maxAttempts) {
        return registry -> Gauge.builder("outbox.stuck",
                        () -> repository.countByPublishedAtIsNullAndAttemptsGreaterThanEqual(maxAttempts))
                .description("재시도 상한을 넘겨 격리된 미발행 outbox row 수(0이어야 정상)")
                .register(registry);
    }
}
