package com.shopsaga.order.application.port.in;

import com.shopsaga.order.domain.Order;

/** 인바운드 포트(커맨드 측): 주문을 생성한다. */
public interface PlaceOrderUseCase {

    Order placeOrder(PlaceOrderCommand command);
}
