package com.shopsaga.inventory.application.port.in;

import com.shopsaga.events.OrderPlacedEvent;
import com.shopsaga.events.PaymentDeclinedEvent;

import java.util.UUID;

/**
 * 인바운드 포트: 재고의 Saga 참여(Phase 12).
 *
 * <p>Phase 9~11의 {@code ReserveStockUseCase} 를 대체한다. 달라진 점:
 * <ul>
 *   <li>예약 <b>결과를 이벤트로 알린다</b>(성공 → InventoryReserved, 실패 → InventoryFailed).
 *       Phase 9에서는 실패를 로그만 남겼다 → 주문이 영원히 매달려 있었다.</li>
 *   <li><b>보상</b>을 수행한다: 결제가 거절되면 잡아둔 재고를 되돌린다(InventoryReleased).</li>
 * </ul>
 */
public interface InventorySagaUseCase {

    /** Saga 1단계: 주문 접수 사실을 듣고 재고를 예약한다 → 성공/실패 이벤트 발행. */
    void onOrderPlaced(UUID messageId, OrderPlacedEvent event);

    /** 보상: 결제 거절 사실을 듣고 예약을 해제한다 → InventoryReleased 발행. */
    void onPaymentDeclined(UUID messageId, PaymentDeclinedEvent event);
}
