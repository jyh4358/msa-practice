package com.shopsaga.inventory.adapter.out.messaging;

import com.shopsaga.events.InventoryFailedEvent;
import com.shopsaga.events.InventoryReleasedEvent;
import com.shopsaga.events.InventoryReservedEvent;
import com.shopsaga.events.Topics;
import com.shopsaga.inventory.application.port.out.PublishInventoryEventPort;
import com.shopsaga.outbox.OutboxWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Phase 12: 재고 이벤트 발행 어댑터 — 공유 {@link OutboxWriter} 로 현재 트랜잭션에 outbox row 만 남긴다
 * (재고 차감과 이벤트 발행이 원자적). key 는 orderId 라 같은 주문의 이벤트는 순서가 보장된다.
 */
@Component
@RequiredArgsConstructor
class InventoryEventOutboxPublisher implements PublishInventoryEventPort {

    private final OutboxWriter outboxWriter;

    @Override
    public void inventoryReserved(InventoryReservedEvent event) {
        outboxWriter.write(event.orderId(), event, Topics.INVENTORY_EVENTS);
    }

    @Override
    public void inventoryFailed(InventoryFailedEvent event) {
        outboxWriter.write(event.orderId(), event, Topics.INVENTORY_EVENTS);
    }

    @Override
    public void inventoryReleased(InventoryReleasedEvent event) {
        outboxWriter.write(event.orderId(), event, Topics.INVENTORY_EVENTS);
    }
}
