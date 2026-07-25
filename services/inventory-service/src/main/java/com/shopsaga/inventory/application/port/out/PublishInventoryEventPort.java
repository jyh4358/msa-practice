package com.shopsaga.inventory.application.port.out;

import com.shopsaga.events.InventoryFailedEvent;
import com.shopsaga.events.InventoryReleasedEvent;
import com.shopsaga.events.InventoryReservedEvent;

/**
 * 아웃바운드 포트: 재고 이벤트 발행(Phase 12). outbox 로 기록되므로 재고 변경과 원자적이다.
 */
public interface PublishInventoryEventPort {

    void inventoryReserved(InventoryReservedEvent event);

    void inventoryFailed(InventoryFailedEvent event);

    void inventoryReleased(InventoryReleasedEvent event);
}
