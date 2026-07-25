package com.shopsaga.orderquery.application.port.out;

import com.shopsaga.orderquery.domain.OrderView;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 아웃바운드 포트: 읽기 모델 저장소. MongoDB라는 사실은 어댑터의 세부사항이다
 * (애플리케이션은 "읽기 모델을 저장/조회한다"는 의도만 표현).
 */
public interface OrderViewRepositoryPort {

    /** orderId 기준 덮어쓰기(upsert) — 투영의 멱등성을 저장소 수준에서 보장. */
    void save(OrderView view);

    Optional<OrderView> findByOrderId(UUID orderId);

    /** 최근 주문 먼저(placedAt 내림차순). */
    List<OrderView> findByCustomerId(UUID customerId);
}
