package com.shopsaga.inventory.adapter.in.event;

import com.shopsaga.events.OrderPlacedEvent;
import com.shopsaga.inventory.application.port.in.ReserveStockUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 9: 인바운드 이벤트 어댑터 — OrderPlaced 를 소비해 재고 예약 유스케이스를 호출한다.
 * observation-enabled 리스너라, producer(order)의 traceparent 를 이어받아 같은 트레이스로 처리된다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class OrderPlacedListener {

    private final ReserveStockUseCase reserveStockUseCase;

    @KafkaListener(topics = KafkaTopicConfig.ORDER_PLACED_TOPIC, groupId = "inventory-service")
    void on(OrderPlacedEvent event) {
        log.info("OrderPlaced 수신 orderId={} 품목수={}", event.orderId(), event.items().size());
        Map<UUID, Integer> quantityByProduct = new HashMap<>();
        event.items().forEach(i -> quantityByProduct.merge(i.productId(), i.quantity(), Integer::sum));
        reserveStockUseCase.reserveForOrder(event.orderId(), quantityByProduct);
    }
}
