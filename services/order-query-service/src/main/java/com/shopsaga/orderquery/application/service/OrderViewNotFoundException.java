package com.shopsaga.orderquery.application.service;

import java.util.UUID;

/**
 * 읽기 모델에 해당 주문이 없음.
 *
 * <p>주의: 이것이 곧 "주문이 없다"는 뜻은 아니다 — 방금 생성된 주문은 <b>아직 투영되지 않았을 수</b> 있다
 * (결과적 일관성 lag). 즉 쓰기 측엔 있고 읽기 측엔 없는 짧은 창이 정상적으로 존재한다.
 */
public class OrderViewNotFoundException extends RuntimeException {

    public OrderViewNotFoundException(UUID orderId) {
        super("order view not found (아직 투영되지 않았을 수 있음): " + orderId);
    }
}
