package com.shopsaga.order.application.port.in;

import com.shopsaga.events.InventoryFailedEvent;
import com.shopsaga.events.InventoryReservedEvent;
import com.shopsaga.events.PaymentChargedEvent;
import com.shopsaga.events.PaymentDeclinedEvent;

import java.util.UUID;

/**
 * 인바운드 포트: Saga 이벤트에 대한 주문의 반응(Phase 12).
 *
 * <p>order-service는 이제 <b>명령하지 않고 반응한다</b> — 재고를 예약하라고 지시하는 대신,
 * "예약됐다/실패했다", "결제됐다/거절됐다"는 <b>사실</b>을 듣고 자기 상태만 바꾼다.
 * 이것이 코레오그래피(choreography): 중앙 조정자가 없고, 각 서비스가 이벤트에 자율적으로 반응한다.
 *
 * <p>모든 메서드는 {@code messageId} 를 받아 <b>멱등</b>하게 처리한다(at-least-once 배달 전제).
 */
public interface OrderSagaUseCase {

    /** 재고 예약 성공 → PENDING에서 INVENTORY_RESERVED로. (다음 단계 결제는 payment가 알아서 한다.) */
    void onInventoryReserved(UUID messageId, InventoryReservedEvent event);

    /** 재고 예약 실패 → 주문 취소(짧은 보상: 되돌릴 것이 없다). */
    void onInventoryFailed(UUID messageId, InventoryFailedEvent event);

    /** 결제 성공 → 주문 확정(Saga 성공 종료). */
    void onPaymentCharged(UUID messageId, PaymentChargedEvent event);

    /** 결제 거절 → 주문 취소(긴 보상: 재고 해제는 inventory가 같은 이벤트를 듣고 수행). */
    void onPaymentDeclined(UUID messageId, PaymentDeclinedEvent event);
}
