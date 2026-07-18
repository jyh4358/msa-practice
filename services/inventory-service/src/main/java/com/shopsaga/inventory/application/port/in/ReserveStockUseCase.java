package com.shopsaga.inventory.application.port.in;

import java.util.Map;
import java.util.UUID;

/**
 * 인바운드 포트(커맨드): 주문의 상품별 수량을 재고에서 예약한다.
 * Kafka 소비자(OrderPlaced 이벤트)가 호출한다. 부족/미등록 상품은 로그만 남긴다(Phase 9a: 보상 없음).
 *
 * <p>Phase 10: {@code messageId}(발행자 outbox id)로 <b>멱등 처리</b>한다 — 같은 메시지가 두 번 배달돼도
 * 예약은 정확히 한 번만 적용된다(effectively-once).
 */
public interface ReserveStockUseCase {

    void reserveForOrder(UUID messageId, UUID orderId, Map<UUID, Integer> quantityByProduct);
}
