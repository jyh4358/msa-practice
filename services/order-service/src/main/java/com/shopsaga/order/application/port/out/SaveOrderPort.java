package com.shopsaga.order.application.port.out;

import com.shopsaga.order.domain.Order;

/** 아웃바운드 포트: 주문 저장. 영속 어댑터가 구현한다. */
public interface SaveOrderPort {

    /** 저장 후 식별자가 부여된 도메인 객체를 반환한다. */
    Order save(Order order);
}
