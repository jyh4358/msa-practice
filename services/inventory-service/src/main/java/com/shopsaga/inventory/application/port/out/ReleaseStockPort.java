package com.shopsaga.inventory.application.port.out;

import java.util.UUID;

/**
 * 아웃바운드 포트: 재고 해제(보상). 예약과 마찬가지로 비관적 락 뒤에서 수행된다.
 * Phase 12에서 추가 — Saga의 보상 경로가 필요해졌기 때문이다.
 */
public interface ReleaseStockPort {

    /** 예약했던 수량을 되돌린다(가용 수량 증가). */
    void release(UUID productId, int quantity);
}
