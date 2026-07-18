package com.shopsaga.order.adapter.out.messaging;

import com.shopsaga.events.OrderPlacedEvent;
import com.shopsaga.order.application.port.out.PublishOrderEventPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * 아웃바운드 메시징 어댑터: OrderPlaced 를 Kafka 로 발행한다.
 * observation-enabled 템플릿이라 현재 트레이스의 traceparent 가 Kafka 헤더로 자동 주입된다
 * (→ inventory 소비 스팬이 같은 트레이스로 이어짐).
 */
@Component
@RequiredArgsConstructor
@Slf4j
class OrderEventKafkaAdapter implements PublishOrderEventPort {

    static final String ORDER_PLACED_TOPIC = "order-placed";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Override
    public void orderPlaced(OrderPlacedEvent event) {
        // key=orderId → 같은 주문의 이벤트는 같은 파티션(순서 보장).
        kafkaTemplate.send(ORDER_PLACED_TOPIC, event.orderId().toString(), event);
        log.info("OrderPlaced 발행 orderId={} topic={}", event.orderId(), ORDER_PLACED_TOPIC);
    }
}
