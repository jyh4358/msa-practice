package com.shopsaga.orderquery.application.service;

import com.shopsaga.orderquery.application.UseCase;
import com.shopsaga.orderquery.application.port.in.GetOrderViewQuery;
import com.shopsaga.orderquery.application.port.in.OrderSummary;
import com.shopsaga.orderquery.application.port.out.OrderViewRepositoryPort;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * 조회 유스케이스 — 읽기 모델을 그대로 반환한다.
 *
 * <p>조인·집계·계산이 <b>없다</b>는 점이 CQRS의 이득이다: 필요한 모양으로 미리 만들어 뒀기 때문에
 * 조회는 단일 문서 읽기로 끝난다(쓰기 모델을 건드리지 않으므로 읽기 부하가 주문 처리에 영향 없음).
 */
@UseCase
@RequiredArgsConstructor
class OrderViewQueryService implements GetOrderViewQuery {

    private final OrderViewRepositoryPort repository;

    @Override
    public List<OrderSummary> findByCustomer(UUID customerId) {
        return repository.findByCustomerId(customerId).stream()
                .map(OrderSummary::from)
                .toList();
    }

    @Override
    public OrderSummary getByOrderId(UUID orderId) {
        return repository.findByOrderId(orderId)
                .map(OrderSummary::from)
                .orElseThrow(() -> new OrderViewNotFoundException(orderId));
    }
}
