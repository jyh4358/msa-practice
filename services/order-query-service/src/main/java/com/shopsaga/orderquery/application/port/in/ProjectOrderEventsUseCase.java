package com.shopsaga.orderquery.application.port.in;

import com.shopsaga.events.InventoryReservedEvent;
import com.shopsaga.events.OrderCancelledEvent;
import com.shopsaga.events.OrderConfirmedEvent;
import com.shopsaga.events.OrderPlacedEvent;

/**
 * 인바운드 포트: Saga 이벤트들을 읽기 모델에 <b>투영(project)</b>한다.
 *
 * <p>Phase 11에서는 {@code OrderPlaced} 하나만 투영했다. Phase 12에서 Saga가 생기며
 * 주문이 <b>상태를 거쳐 흐르므로</b>, 읽기 모델도 그 전이를 따라가야 한다:
 * <pre>PENDING → INVENTORY_RESERVED → CONFIRMED | CANCELLED</pre>
 *
 * <p>투영은 계속 <b>멱등·결정적</b>이어야 한다(리플레이로 재구축 가능해야 하므로):
 * 값은 이벤트에서만 가져오고, 상태는 <b>단조롭게</b>만 전진시킨다.
 */
public interface ProjectOrderEventsUseCase {

    void onOrderPlaced(OrderPlacedEvent event);

    void onInventoryReserved(InventoryReservedEvent event);

    void onOrderConfirmed(OrderConfirmedEvent event);

    void onOrderCancelled(OrderCancelledEvent event);
}
