package com.shopsaga.order.application.port.in;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 인바운드 포트 입력 모델. 웹 DTO와 분리되어 애플리케이션 경계를 보호한다
 * (웹 어댑터가 자신의 요청 DTO를 이 커맨드로 변환해 넘긴다).
 */
public record PlaceOrderCommand(
        UUID customerId,
        List<Item> items
) {
    public record Item(UUID productId, int quantity, BigDecimal unitPrice) {
    }
}
