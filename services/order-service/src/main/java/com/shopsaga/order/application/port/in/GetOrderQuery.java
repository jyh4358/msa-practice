package com.shopsaga.order.application.port.in;

import com.shopsaga.order.domain.Order;

import java.util.List;
import java.util.UUID;

/** 인바운드 포트(쿼리 측): 주문을 조회한다. */
public interface GetOrderQuery {

    Order getOrder(UUID id);

    List<Order> listOrders();
}


