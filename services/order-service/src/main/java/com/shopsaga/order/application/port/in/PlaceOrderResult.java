package com.shopsaga.order.application.port.in;

/**
 * Phase 14: 주문 접수 결과 = 저장된 주문 + <b>참고용</b> 재고 사전 확인 결과.
 *
 * <p>사전 확인을 {@link OrderView} 안에 넣지 않은 이유: {@code OrderView} 는 조회에도 쓰이는
 * 주문의 상태이고, 사전 확인은 <b>이번 요청에만 해당하는 힌트</b>다. 성격이 다른 값을 한 모델에 섞으면
 * 조회 응답에도 의미 없는 필드가 따라다닌다.
 */
public record PlaceOrderResult(OrderView order, StockPrecheck precheck) {
}
