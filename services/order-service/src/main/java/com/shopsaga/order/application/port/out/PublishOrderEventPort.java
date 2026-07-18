package com.shopsaga.order.application.port.out;

import com.shopsaga.events.OrderPlacedEvent;

/**
 * 아웃바운드 포트: 주문 이벤트 발행. 메시징 기술(Kafka)은 어댑터의 구현 세부로 숨긴다 —
 * 애플리케이션은 "주문이 발생했음을 알린다"는 의도만 표현한다.
 */
public interface PublishOrderEventPort {

    void orderPlaced(OrderPlacedEvent event);
}
