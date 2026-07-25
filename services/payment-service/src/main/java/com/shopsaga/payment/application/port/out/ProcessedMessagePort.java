package com.shopsaga.payment.application.port.out;

import java.util.UUID;

/**
 * 아웃바운드 포트: 멱등 소비 지원(inbox 패턴).
 * 결제는 <b>중복이 곧 이중 청구</b>이므로 이 장치가 특히 중요하다.
 */
public interface ProcessedMessagePort {

    boolean isAlreadyProcessed(UUID messageId);

    void markProcessed(UUID messageId);
}
