package com.shopsaga.order.application.port.out;

import com.shopsaga.order.application.port.in.PlaceOrderCommand;
import com.shopsaga.order.domain.Order;

/**
 * Phase 13: 주문이 접수됐을 때 <b>Saga를 어떻게 시작할지</b>를 추상화한 포트.
 *
 * <p>구현이 두 가지라서 포트로 뺐다 — 이 프로젝트는 같은 업무를 두 방식으로 만들어 <b>비교</b>하는 것이 목적이다:
 * <ul>
 *   <li><b>코레오그래피</b>(Phase 12): 시작 신호가 따로 없다. {@code OrderPlaced} 이벤트를 각자 듣고 알아서 움직인다.</li>
 *   <li><b>오케스트레이션</b>(Phase 13): {@code saga_instance} 를 만들고 첫 <b>커맨드</b>를 내보낸다.</li>
 * </ul>
 * {@code saga.mode} 설정으로 어느 구현이 등록될지 결정된다 → {@link com.shopsaga.order.application.service.OrderService}
 * 는 어느 방식인지 몰라도 된다.
 */
public interface SagaStarterPort {

    void start(Order order, PlaceOrderCommand command);
}
