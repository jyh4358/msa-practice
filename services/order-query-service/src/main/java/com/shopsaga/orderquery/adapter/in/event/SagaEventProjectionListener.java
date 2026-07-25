package com.shopsaga.orderquery.adapter.in.event;

import com.shopsaga.events.InventoryReservedEvent;
import com.shopsaga.events.OrderCancelledEvent;
import com.shopsaga.events.OrderConfirmedEvent;
import com.shopsaga.events.OrderPlacedEvent;
import com.shopsaga.events.Topics;
import com.shopsaga.orderquery.application.port.in.ProjectOrderEventsUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Phase 12: 읽기 모델 투영용 인바운드 어댑터 — <b>세 토픽을 모두</b> 구독한다.
 *
 * <p>읽기 모델은 Saga 전체를 지켜보는 관찰자다: 주문 접수(order-events), 재고 예약(inventory-events),
 * 확정/취소(order-events)를 모아 화면용 상태를 만든다.
 *
 * <p>컨슈머 그룹이 다른 서비스들과 다르므로({@code order-query-service}) 오프셋을 독립적으로 관리한다 →
 * 읽기 모델만 지우고 offset 0부터 <b>재구축</b>할 수 있다(Phase 11의 핵심 이점).
 *
 * <p>⚠️ 토픽이 여러 개 = 리스너 컨테이너도 여러 개 = <b>도착 순서 보장 없음</b>.
 * 그래서 투영은 상태를 단조롭게만 전진시킨다(OrderViewStatus 의 rank).
 */
@Component
@KafkaListener(
        topics = {Topics.ORDER_EVENTS, Topics.INVENTORY_EVENTS, Topics.PAYMENT_EVENTS},
        groupId = "order-query-service")
@RequiredArgsConstructor
@Slf4j
class SagaEventProjectionListener {

    private final ProjectOrderEventsUseCase projectionUseCase;

    @KafkaHandler
    void onPlaced(OrderPlacedEvent event) {
        log.info("OrderPlaced 수신(투영) orderId={} 품목수={}", event.orderId(), event.items().size());
        projectionUseCase.onOrderPlaced(event);
    }

    @KafkaHandler
    void onInventoryReserved(InventoryReservedEvent event) {
        log.info("InventoryReserved 수신(투영) orderId={}", event.orderId());
        projectionUseCase.onInventoryReserved(event);
    }

    @KafkaHandler
    void onConfirmed(OrderConfirmedEvent event) {
        log.info("OrderConfirmed 수신(투영) orderId={}", event.orderId());
        projectionUseCase.onOrderConfirmed(event);
    }

    @KafkaHandler
    void onCancelled(OrderCancelledEvent event) {
        log.info("OrderCancelled 수신(투영) orderId={} reason={}", event.orderId(), event.reason());
        projectionUseCase.onOrderCancelled(event);
    }

    @KafkaHandler(isDefault = true)
    void onUnknown(Object event) {
        // InventoryFailed·InventoryReleased·PaymentCharged/Declined 는 읽기 모델이 직접 쓰지 않는다
        // (최종 상태는 order 가 발행하는 OrderConfirmed/OrderCancelled 로 통일 — 상태의 authority 는 주문 소유자).
        log.debug("투영 대상 아님 — 무시 type={}", event.getClass().getSimpleName());
    }
}
