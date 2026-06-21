package com.shopsaga.order.application.port.out;

import java.util.UUID;

/**
 * 아웃바운드 포트: 재고 예약(차감).
 * 락 전략(비관적 락 등)은 어댑터의 구현 세부사항으로 숨긴다 — 애플리케이션은 "예약한다"는 의도만 표현한다.
 */
public interface ReserveStockPort {

    /** 재고를 차감한다. 부족하면 InsufficientStockException, 미등록 상품이면 StockNotFoundException. */
    void reserve(UUID productId, int quantity);
}
