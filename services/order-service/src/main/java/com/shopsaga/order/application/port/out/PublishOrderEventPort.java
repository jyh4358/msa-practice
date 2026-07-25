package com.shopsaga.order.application.port.out;

import com.shopsaga.events.OrderCancelledEvent;
import com.shopsaga.events.OrderConfirmedEvent;
import com.shopsaga.events.OrderPlacedEvent;

/**
 * 아웃바운드 포트: 주문 이벤트 발행. 메시징 기술(Kafka)과 신뢰성 장치(outbox)는 어댑터의 구현 세부로 숨긴다 —
 * 애플리케이션은 "주문에 이런 일이 일어났음을 알린다"는 의도만 표현한다.
 *
 * <p>Phase 12: Saga의 시작(Placed)과 종료(Confirmed/Cancelled)를 모두 알린다.
 * 발행은 <b>호출자의 트랜잭션에 outbox row 로</b> 기록되므로 상태 전이와 원자적이다.
 */
public interface PublishOrderEventPort {

    void orderPlaced(OrderPlacedEvent event);

    void orderConfirmed(OrderConfirmedEvent event);

    void orderCancelled(OrderCancelledEvent event);
}
