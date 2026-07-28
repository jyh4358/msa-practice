package com.shopsaga.order.application.port.in;

/**
 * 인바운드 포트(커맨드 측): 주문을 생성한다. 출력은 도메인이 아니라 불변 뷰.
 *
 * <p>Phase 14부터 반환형이 {@link PlaceOrderResult} 다 — 주문과 함께 <b>재고 사전 확인 결과</b>를
 * 돌려주기 위해서다(확인 실패는 오류가 아니라 "모름"이라는 정상 결과다).
 */
public interface PlaceOrderUseCase {

    PlaceOrderResult placeOrder(PlaceOrderCommand command);
}
