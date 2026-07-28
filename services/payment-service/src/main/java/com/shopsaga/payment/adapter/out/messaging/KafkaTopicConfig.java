package com.shopsaga.payment.adapter.out.messaging;

import com.shopsaga.events.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * 브로커에 auto-create 가 꺼져 있으므로 토픽을 명시 생성한다.
 * 각 서비스는 자기가 발행하는 토픽만 선언한다 — payment 는 payment-events.
 */
@Configuration
class KafkaTopicConfig {

    @Bean
    NewTopic paymentEventsTopic() {
        return TopicBuilder.name(Topics.PAYMENT_EVENTS).partitions(1).replicas(1).build();
    }

    /** Phase 13: 조정자에게 결과를 돌려주는 리플라이 토픽(inventory 도 같은 토픽에 낸다 — 선언은 멱등). */
    @Bean
    NewTopic sagaRepliesTopic() {
        return TopicBuilder.name(Topics.SAGA_REPLIES).partitions(1).replicas(1).build();
    }
}
