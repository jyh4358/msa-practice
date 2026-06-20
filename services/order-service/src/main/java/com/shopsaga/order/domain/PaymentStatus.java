package com.shopsaga.order.domain;

/**
 * Phase 1은 CAPTURED만 영속한다 — 결제 거절은 Payment 생성 전에 예외를 던지므로 DECLINED 행이 남지 않는다
 * (all-or-nothing 롤백). PENDING/DECLINED/REFUNDED 등은 Phase 2+ payment-service에서 추가된다.
 */
public enum PaymentStatus {
    CAPTURED
}
