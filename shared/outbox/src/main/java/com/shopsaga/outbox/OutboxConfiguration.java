package com.shopsaga.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.beans.factory.ObjectProvider;
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
                                   ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
        return new OutboxRelay(repository, kafkaTemplate, objectMapper,
                tracer.getIfAvailable(), propagator.getIfAvailable());
    }
}
