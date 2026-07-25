package com.shopsaga.inventory.application.port.out;

import java.util.Map;
import java.util.UUID;

/**
 * 아웃바운드 포트: 예약 원장(ledger) — "이 주문에 무엇을 얼마나 잡아뒀는지"를 기억한다.
 *
 * <p>왜 필요한가: 보상을 유발하는 {@code PaymentDeclined} 이벤트에는 <b>품목 정보가 없다</b>(주문 id와 금액만).
 * 그래서 무엇을 되돌려야 하는지는 inventory가 <b>스스로 기록해 둬야</b> 한다.
 * 서비스는 자기 결정의 근거를 자기 DB에 남긴다 — 다른 서비스 DB를 조회하지 않는다.
 */
public interface StockReservationPort {

    /** 예약 성공 시 원장에 기록한다. */
    void record(UUID orderId, Map<UUID, Integer> quantityByProduct);

    /** 해제 시 원장에서 꺼내고 <b>삭제</b>한다 → 두 번 호출하면 빈 결과(자연 멱등). */
    Map<UUID, Integer> takeForRelease(UUID orderId);
}
