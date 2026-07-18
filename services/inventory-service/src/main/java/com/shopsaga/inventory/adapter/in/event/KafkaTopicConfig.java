package com.shopsaga.inventory.adapter.in.event;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Phase 9: 토픽을 명시적으로 생성한다(브로커 auto-create 비활성).
 * 소비자(inventory)가 토픽을 소유·선언하며, KafkaAdmin이 기동 시 브로커에 생성한다.
 * 단일 노드 KRaft 라 replication-factor=1 필수(3으로 두면 생성 실패).
 */
@Configuration
class KafkaTopicConfig {

    static final String ORDER_PLACED_TOPIC = "order-placed";

    @Bean
    NewTopic orderPlacedTopic() {
        return TopicBuilder.name(ORDER_PLACED_TOPIC)
                .partitions(1)
                .replicas(1)
                .build();
    }
}
