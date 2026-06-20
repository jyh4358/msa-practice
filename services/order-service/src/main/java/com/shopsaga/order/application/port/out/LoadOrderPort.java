package com.shopsaga.order.application.port.out;

import com.shopsaga.order.domain.Order;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 아웃바운드 포트: 주문 조회. 영속 어댑터가 구현한다. */
public interface LoadOrderPort {

    Optional<Order> loadById(UUID id);

    List<Order> loadAll();
}
