package com.shopsaga.orderquery.adapter.in.event;

import com.shopsaga.events.OrderPlacedEvent;
import com.shopsaga.orderquery.application.port.in.ProjectOrderPlacedUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Phase 11: 인바운드 이벤트 어댑터 — {@code OrderPlaced} 를 소비해 읽기 모델에 투영한다.
 *
 * <p><b>컨슈머 그룹이 inventory-service와 다르다</b>(`order-query-service`). Kafka는 그룹별로 오프셋을
 * 따로 관리하므로, <b>같은 토픽을 두 서비스가 각자 처음부터</b> 읽을 수 있다 —
 * 이게 이벤트 로그를 여러 소비자가 독립적으로 활용하는 방식이고, 읽기 모델 재구축(리플레이)의 근거다.
 *
 * <p>observation-enabled 리스너라 발행자(order 릴레이)의 traceparent 를 이어받는다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class OrderPlacedProjectionListener {

    static final String ORDER_PLACED_TOPIC = "order-placed";

    private final ProjectOrderPlacedUseCase projectOrderPlacedUseCase;

    @KafkaListener(topics = ORDER_PLACED_TOPIC, groupId = "order-query-service")
    void on(OrderPlacedEvent event) {
        log.info("OrderPlaced 수신(투영) orderId={} 품목수={}", event.orderId(), event.items().size());
        projectOrderPlacedUseCase.project(event);
    }
}
