package com.shopsaga.payment.domain;

/**
 * Phase 2: CAPTURED만 영속한다(거절은 Payment 생성 전 예외). 환불/취소 등은 이후 단계에서 추가.
 */
public enum PaymentStatus {
    CAPTURED
}
