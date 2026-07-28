package com.shopsaga.payment.domain;

/**
 * Phase 2: CAPTURED만 영속했다(거절은 Payment 생성 전 예외라 row 자체가 없다).
 *
 * <p>Phase 14: {@link #REFUNDED} 추가 — Saga가 이미 종료된 뒤 뒤늦게 성립한 결제('고아 결제')를
 * 되돌린 상태. row 를 지우지 않고 상태로 남기는 이유는, 보상이 <b>rollback 이 아니라 semantic undo</b>이기
 * 때문이다: 돈이 움직인 사실은 사라지지 않고 "되돌렸다"는 사실이 하나 더 쌓인다.
 */
public enum PaymentStatus {
    CAPTURED,
    REFUNDED
}
