package com.shopsaga.order.application.port.in;

/** 인바운드 포트(커맨드 측): 주문을 생성한다. 출력은 도메인이 아니라 OrderView(불변 뷰). */
public interface PlaceOrderUseCase {

    OrderView placeOrder(PlaceOrderCommand command);
}
