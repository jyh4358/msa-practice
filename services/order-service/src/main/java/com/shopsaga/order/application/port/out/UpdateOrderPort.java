package com.shopsaga.order.application.port.out;

import com.shopsaga.order.domain.Order;

/**
 * 아웃바운드 포트: 이미 저장된 주문의 <b>상태 전이를 반영</b>한다(Phase 12 Saga).
 *
 * <p>{@link SaveOrderPort}(신규 INSERT)와 분리한 이유: 전이는 <b>load-then-mutate</b> 로 해야 한다
 * (managed 엔티티를 불러와 필드만 바꿔 dirty checking UPDATE). 새 엔티티를 만들어 merge 하면
 * 자식 컬렉션(order_items)이 지워지거나 중복될 위험이 있다 — docs/HEXAGONAL.md §3.3.
 */
public interface UpdateOrderPort {

    /** 도메인의 현재 상태(status·paymentId)를 영속 상태에 반영한다. */
    void update(Order order);
}
