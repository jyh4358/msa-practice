package com.shopsaga.inventory.adapter.out.messaging;

import com.shopsaga.events.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * 브로커에 auto-create 가 꺼져 있으므로 토픽을 명시 생성한다(KafkaAdmin 이 기동 시 반영).
 *
 * <p><b>토픽 소유 원칙:</b> 각 서비스는 <b>자기가 발행하는 토픽</b>만 선언한다.
 * 단일 노드 브로커라 {@code replicas(1)} — 기본값 3으로 두면 토픽 생성이 실패한다.
 */
@Configuration
class KafkaTopicConfig {

    @Bean
    NewTopic inventoryEventsTopic() {
        return TopicBuilder.name(Topics.INVENTORY_EVENTS).partitions(1).replicas(1).build();
    }

    /** Phase 13: 조정자에게 결과를 돌려주는 리플라이 토픽(payment 도 같은 토픽에 낸다 — 선언은 멱등). */
    @Bean
    NewTopic sagaRepliesTopic() {
        return TopicBuilder.name(Topics.SAGA_REPLIES).partitions(1).replicas(1).build();
    }
}
