package com.shopsaga.order.application.service;

/**
 * Phase 14: 재고 사전 확인이 "부족"이라 <b>빠르게 거절</b>한 경우.
 * {@code order.stock-precheck.reject-on-insufficient=true} 일 때만 발생한다(기본은 꺼짐).
 *
 * <p>이 예외가 기본값이 아닌 이유: 사전 확인은 락 없이 읽은 값이라 <b>틀릴 수 있다</b>.
 * 틀린 값으로 주문을 거절하면 "실제로는 살 수 있었는데 못 산" 손해가 생긴다.
 * 반대로 켜면 뻔한 실패를 400ms 만에 알려 줄 수 있다 — 어느 쪽이 옳은지는 업무가 정한다.
 */
public class StockPrecheckRejectedException extends RuntimeException {

    public StockPrecheckRejectedException(String detail) {
        super("재고 사전 확인 실패(참고값 기준): " + detail);
    }
}
