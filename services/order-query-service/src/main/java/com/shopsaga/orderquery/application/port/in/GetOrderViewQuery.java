package com.shopsaga.orderquery.application.port.in;

import java.util.List;
import java.util.UUID;

/**
 * 인바운드 포트(쿼리): 읽기 모델 조회. <b>쓰기 기능이 없다</b> — CQRS의 Q(uery) 쪽만 담당한다.
 * 쓰기(주문 생성)는 order-service의 커맨드 쪽 책임이며, 이 서비스는 그 결과를 이벤트로만 안다.
 */
public interface GetOrderViewQuery {

    /** 고객의 주문 목록(최근 주문 먼저) — 비정규화되어 있어 조인 없이 한 번에 반환된다. */
    List<OrderSummary> findByCustomer(UUID customerId);

    /** 주문 하나. 아직 투영되지 않았으면(결과적 일관성 lag) 빈 결과가 될 수 있다. */
    OrderSummary getByOrderId(UUID orderId);
}
