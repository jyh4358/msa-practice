package com.shopsaga.messaging;

import com.shopsaga.events.Topics;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 14: <b>소비 실패 처리 — 유한 재시도 후 DLQ</b>. 소비하는 서비스가
 * {@code @Import(KafkaErrorHandlingConfiguration.class)} 로 켠다.
 *
 * <h2>왜 필요한가 (Phase 13까지의 실제 동작)</h2>
 * Kafka 소비자는 오프셋을 "처리 성공"으로 넘긴다. 리스너가 예외를 던지면 오프셋이 전진하지 못하고
 * <b>같은 레코드가 영원히 다시 배달</b>된다. 그 파티션 뒤에 줄 선 정상 메시지는 전부 함께 멈춘다
 * (head-of-line blocking). 잘못된 메시지 <b>하나가 서비스 전체를 마비</b>시키는 것 —
 * 이런 메시지를 <b>poison pill</b>(독약)이라고 부른다.
 *
 * <h2>정책 두 단계</h2>
 * <ol>
 *   <li><b>일시적 결함이면 재시도한다</b> — DB 커넥션 순단 같은 건 잠시 뒤 성공한다.
 *       단 <b>유한하게</b>(지수 백오프 + 최대 횟수). 무한 재시도는 장애를 감출 뿐이다.</li>
 *   <li><b>그래도 안 되면 치운다</b> — {@code <원본토픽>.DLT} 로 옮기고 오프셋을 전진시킨다.
 *       그 메시지는 잃지 않고(나중에 사람이 보거나 재투입), 파티션은 다시 흐른다.</li>
 * </ol>
 *
 * <h2>역직렬화 실패는 재시도하지 않는다</h2>
 * JSON 이 깨졌거나 신뢰하지 않는 타입이면 <b>백 번 다시 해도 똑같이 실패</b>한다.
 * {@link DefaultErrorHandler} 는 {@code DeserializationException} 등을 기본 <b>비재시도</b> 목록에 두고
 * 즉시 DLT 로 보낸다. 단, 이게 동작하려면 컨슈머가
 * {@code ErrorHandlingDeserializer} 로 감싸져 있어야 한다(설정은 {@code config-repo/application.yml}) —
 * 그렇지 않으면 예외가 <b>리스너에 도달하기 전</b> 컨슈머 스레드에서 터져 이 핸들러가 손도 못 댄다.
 *
 * <h2>DLT 레코드에 실린 정보</h2>
 * {@link DeadLetterPublishingRecoverer} 가 원본 토픽/파티션/오프셋, 예외 타입·메시지·스택트레이스를
 * {@code kafka_dlt-*} 헤더로 붙인다 → kafka-ui 에서 "왜 죽었나"를 바로 볼 수 있다.
 *
 * <p>⚠️ {@code messaging.dlq.enabled=false} 로 끌 수 있다. Kafka 자동설정을 제외한 컨텍스트
 * (예: 브로커 없이 도는 투영 통합 테스트)에는 {@code KafkaTemplate} 자체가 없어 이 설정이 기동을 깨뜨리기 때문이다.
 */
@Configuration
@ConditionalOnProperty(name = "messaging.dlq.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class KafkaErrorHandlingConfiguration {

    /** DLT 토픽 이름 규칙 — Spring Kafka 관례와 동일하게 원본 토픽 + 접미사. */
    public static final String DLT_SUFFIX = ".DLT";

    /** 소비 실패가 도달할 수 있는 모든 원본 토픽(= 이 플랫폼의 전체 토픽). */
    private static final List<String> SOURCE_TOPICS = List.of(
            Topics.ORDER_EVENTS, Topics.INVENTORY_EVENTS, Topics.PAYMENT_EVENTS,
            Topics.SAGA_COMMANDS, Topics.SAGA_REPLIES);

    /**
     * 최종 실패 레코드를 {@code <원본토픽>.DLT} 로 옮긴다.
     *
     * <p><b>템플릿이 둘인 이유</b>가 이 설정의 핵심이다: 역직렬화가 실패한 레코드는 자바 객체가 아니라
     * <b>원본 바이트</b> 그대로 DLT 에 실어야 나중에 원문을 볼 수 있다. 평소 쓰는 {@code JsonSerializer} 로는
     * byte[] 를 base64 문자열로 뭉개 버리므로 byte[] 전용 템플릿을 따로 만든다.
     *
     * <p>⚠️ 그 템플릿을 {@code @Bean} 으로 노출하지 않는 이유: Boot 의 {@code KafkaTemplate} 자동설정은
     * {@code @ConditionalOnMissingBean(KafkaTemplate.class)} 라서, 우리가 KafkaTemplate 빈을 하나라도
     * 선언하면 <b>자동설정 템플릿이 통째로 사라진다</b>(→ outbox 릴레이의 주입 실패). 지역 변수로만 쓴다.
     *
     * <p>파티션을 {@code -1} 로 두는 이유: DLT 의 파티션 수가 원본과 다를 수 있어 원본 파티션 번호를
     * 그대로 쓰면 존재하지 않는 파티션을 가리킬 수 있다. 브로커가 고르게 둔다.
     */
    @Bean
    DeadLetterPublishingRecoverer deadLetterPublishingRecoverer(
            KafkaTemplate<String, Object> jsonTemplate,
            ProducerFactory<String, Object> producerFactory) {

        KafkaTemplate<Object, Object> bytesTemplate = new KafkaTemplate<>(copyWithByteArrayValues(producerFactory));

        // 값 타입 → 템플릿 매핑. 순서가 중요하다(먼저 매칭되는 것이 선택된다).
        Map<Class<?>, KafkaOperations<?, ?>> templates = new LinkedHashMap<>();
        templates.put(byte[].class, bytesTemplate);   // 역직렬화 실패 → 원본 바이트
        templates.put(Object.class, jsonTemplate);    // 그 외 → 평소대로 JSON

        return new DeadLetterPublishingRecoverer(templates,
                (record, ex) -> new TopicPartition(record.topic() + DLT_SUFFIX, -1));
    }

    @SuppressWarnings("unchecked")
    private ProducerFactory<Object, Object> copyWithByteArrayValues(ProducerFactory<String, Object> source) {
        return (ProducerFactory<Object, Object>) (ProducerFactory<?, ?>) source.copyWithConfigurationOverride(
                Map.of(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class));
    }

    /**
     * 컨테이너 공통 에러 핸들러. Boot 자동설정이 {@code CommonErrorHandler} 빈 하나를 찾아
     * 모든 {@code @KafkaListener} 컨테이너에 꽂아 준다(별도 팩토리 설정 불필요).
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(
            DeadLetterPublishingRecoverer recoverer,
            @Value("${messaging.dlq.max-retries:3}") int maxRetries,
            @Value("${messaging.dlq.initial-interval:500}") long initialInterval,
            @Value("${messaging.dlq.multiplier:2.0}") double multiplier,
            @Value("${messaging.dlq.max-interval:5000}") long maxInterval) {

        // ⚠️ 유한 백오프. FixedBackOff(interval, Long.MAX_VALUE) 같은 무한 재시도는
        //    "장애가 났는데도 아무 일 없어 보이는" 최악의 상태를 만든다.
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(maxRetries);
        backOff.setInitialInterval(initialInterval);
        backOff.setMultiplier(multiplier);
        backOff.setMaxInterval(maxInterval);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        // 로그만 봐도 "몇 번 시도했고 결국 어디로 갔는지"가 드러나야 한다.
        // ⚠️ failedDelivery 는 '재시도할 것'이라는 뜻이 아니다 — 비재시도 예외(역직렬화 실패 등)도
        //    1회 호출된 뒤 곧장 recovered(DLT 이동)로 넘어간다. 그래서 문구를 '배달 시도'로 쓴다.
        handler.setRetryListeners(new RetryListener() {
            @Override
            public void failedDelivery(ConsumerRecord<?, ?> record, Exception ex, int deliveryAttempt) {
                log.warn("소비 실패 — 배달 시도 {}/{} topic={} partition={} offset={} 원인={}",
                        deliveryAttempt, maxRetries + 1, record.topic(), record.partition(), record.offset(),
                        describe(ex));
            }

            @Override
            public void recovered(ConsumerRecord<?, ?> record, Exception ex) {
                log.error("★ DLT 이동 topic={} → {}{} partition={} offset={} 원인={}",
                        record.topic(), record.topic(), DLT_SUFFIX, record.partition(), record.offset(),
                        describe(ex));
            }

            @Override
            public void recoveryFailed(ConsumerRecord<?, ?> record, Exception original, Exception failure) {
                // 여기까지 오면 파티션이 다시 막힌다 — 보통 DLT 토픽 미생성이 원인.
                log.error("‼ DLT 이동 실패 topic={} offset={} — {}",
                        record.topic(), record.offset(), failure.toString());
            }
        });
        return handler;
    }

    /**
     * DLT 토픽 선언. 브로커에 auto-create 가 꺼져 있으므로 명시하지 않으면
     * <b>DLT 발행 자체가 실패</b>해서 결국 파티션이 다시 막힌다(가장 흔한 함정).
     */
    @Bean
    KafkaAdmin.NewTopics deadLetterTopics() {
        // ⚠️ List<NewTopic> 빈은 KafkaAdmin 이 수집하지 않는다 — 여러 개를 한 빈으로 낼 땐 NewTopics 를 쓴다.
        return new KafkaAdmin.NewTopics(SOURCE_TOPICS.stream()
                .map(t -> TopicBuilder.name(t + DLT_SUFFIX).partitions(1).replicas(1).build())
                .toArray(NewTopic[]::new));
    }

    /**
     * "무엇이 죽였는가"를 한 줄로. <b>바깥 예외 타입까지</b> 보여 준다 —
     * 근본 원인만 찍으면 {@code DeserializationException}(= 비재시도로 분류된 이유)이 로그에서 사라져
     * "왜 재시도를 안 했지?"를 알 수 없게 된다(실제로 겪었다).
     */
    private static String describe(Throwable ex) {
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String outer = ex.getClass().getSimpleName();
        String inner = root.getClass().getSimpleName();
        return outer.equals(inner)
                ? outer + ": " + root.getMessage()
                : outer + " ← " + inner + ": " + root.getMessage();
    }
}
