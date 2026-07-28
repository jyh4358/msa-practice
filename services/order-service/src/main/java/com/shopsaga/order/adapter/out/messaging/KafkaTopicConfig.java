package com.shopsaga.order.adapter.out.messaging;

import com.shopsaga.events.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * 브로커에 auto-create 가 꺼져 있으므로 토픽을 명시 생성한다(KafkaAdmin 이 기동 시 반영).
 * 각 서비스는 자기가 발행하는 토픽만 선언한다 — order 는 order-events(사실) + saga-commands(지시, Phase 13).
 */
@Configuration
class KafkaTopicConfig {

    @Bean
    NewTopic orderEventsTopic() {
        return TopicBuilder.name(Topics.ORDER_EVENTS).partitions(1).replicas(1).build();
    }

    /** Phase 13: 조정자가 내보내는 커맨드 토픽(참여 서비스가 구독). */
    @Bean
    NewTopic sagaCommandsTopic() {
        return TopicBuilder.name(Topics.SAGA_COMMANDS).partitions(1).replicas(1).build();
    }
}
