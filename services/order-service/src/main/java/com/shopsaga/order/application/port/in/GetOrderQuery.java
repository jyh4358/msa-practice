package com.shopsaga.order.application.port.in;

import java.util.List;
import java.util.UUID;

/** 인바운드 포트(쿼리 측): 주문을 조회한다. 출력은 OrderView(불변 뷰). */
public interface GetOrderQuery {

    OrderView getOrder(UUID id);

    List<OrderView> listOrders();
}
